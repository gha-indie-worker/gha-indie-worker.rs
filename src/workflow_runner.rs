//! Whole-workflow scheduling for the trusted, run-step-only Linux subset.
//!
//! This layer deliberately remains separate from webhook intake. It accepts an
//! already validated [`WorkflowPlan`], preflights every concrete job before any
//! workflow shell source starts, creates an isolated workspace for each job,
//! and schedules the dependency graph with GitHub-compatible matrix controls.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::path::PathBuf;
use std::time::Duration;

use serde::Serialize;
use serde_json::{Map, Value};
use tokio::fs;
use tokio::task::{AbortHandle, Id, JoinSet};
use uuid::Uuid;

use crate::expression::{
    evaluate_condition, evaluate_wrapped_expression, uses_status_function, validate_expression,
    ExpressionContext, ExpressionError, StatusContext,
};
use crate::linux_runner::{
    execute_trusted_linux_job, preflight_trusted_linux_job, JobConclusion, LinuxJobResult,
    LinuxRunnerError, TrustedLinuxRunnerConfig,
};
use crate::workflow::{PlannedJob, WorkflowPlan};

pub const LINUX_WORKFLOW_SCHEMA_VERSION: &str = "gha-indie-worker.linux-workflow.v1";

const DEFAULT_OUTPUT_LIMIT: usize = 10 * 1024 * 1024;

/// Operator policy and filesystem location for one trusted workflow run.
#[derive(Debug, Clone)]
pub struct TrustedLinuxWorkflowConfig {
    /// Parent directory under which a preserved per-run directory is created.
    pub run_root: PathBuf,
    /// Explicit authority to execute trusted workflow shell source on the host.
    pub allow_host_process_execution: bool,
    /// Operator-wide ceiling across every matrix group in the workflow.
    pub maximum_parallel_jobs: usize,
    /// Default timeout inherited by steps without `timeout-minutes`.
    pub default_step_timeout: Duration,
    /// Hard upper bound for every individual step.
    pub maximum_step_timeout: Duration,
    /// Combined stdout/stderr byte ceiling applied to each step.
    pub maximum_output_bytes: usize,
}

impl TrustedLinuxWorkflowConfig {
    #[must_use]
    pub fn new(run_root: impl Into<PathBuf>) -> Self {
        Self {
            run_root: run_root.into(),
            allow_host_process_execution: false,
            maximum_parallel_jobs: 1,
            default_step_timeout: Duration::from_secs(3600),
            maximum_step_timeout: Duration::from_secs(3600),
            maximum_output_bytes: DEFAULT_OUTPUT_LIMIT,
        }
    }

    fn job_config(&self, workspace: PathBuf) -> TrustedLinuxRunnerConfig {
        TrustedLinuxRunnerConfig {
            workspace,
            allow_host_process_execution: self.allow_host_process_execution,
            default_step_timeout: self.default_step_timeout,
            maximum_step_timeout: self.maximum_step_timeout,
            maximum_output_bytes: self.maximum_output_bytes,
        }
    }
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkflowConclusion {
    Success,
    Failure,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkflowJobOutcome {
    Success,
    Failure,
    TimedOut,
    Skipped,
    Cancelled,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkflowJobConclusion {
    Success,
    Failure,
    TimedOut,
    Skipped,
    Cancelled,
}

/// Result for one concrete job, including matrix instances.
#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxWorkflowJobResult {
    pub id: String,
    pub base_job_id: String,
    pub name: String,
    pub matrix: BTreeMap<String, Value>,
    pub needs: Vec<String>,
    pub outcome: WorkflowJobOutcome,
    /// Concrete GitHub API-style conclusion before job-level tolerance.
    pub conclusion: WorkflowJobConclusion,
    /// Conclusion used for matrix aggregation, dependencies, and workflow state.
    pub effective_conclusion: WorkflowJobConclusion,
    pub continue_on_error: bool,
    pub started_sequence: Option<usize>,
    pub completed_sequence: usize,
    pub workspace: PathBuf,
    pub runner_result: Option<LinuxJobResult>,
}

/// Aggregate result for one base job and all of its matrix instances.
#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxWorkflowJobGroupResult {
    pub base_job_id: String,
    pub instance_ids: Vec<String>,
    pub conclusion: WorkflowJobConclusion,
    pub max_observed_parallel: usize,
}

/// Complete scheduler result in deterministic planner order.
#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxWorkflowResult {
    pub schema_version: &'static str,
    pub workflow_name: Option<String>,
    pub conclusion: WorkflowConclusion,
    pub run_directory: PathBuf,
    pub max_observed_parallel: usize,
    pub jobs: Vec<LinuxWorkflowJobResult>,
    pub job_groups: Vec<LinuxWorkflowJobGroupResult>,
}

/// Fail-closed scheduler or runner-engine error.
#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxWorkflowError {
    pub code: &'static str,
    pub message: String,
}

impl LinuxWorkflowError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Display for LinuxWorkflowError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for LinuxWorkflowError {}

impl From<ExpressionError> for LinuxWorkflowError {
    fn from(error: ExpressionError) -> Self {
        Self::new(error.code, error.message)
    }
}

struct PreparedWorkflow {
    continue_on_error: BTreeMap<String, bool>,
}

struct RunningJob {
    abort: AbortHandle,
    base_job_id: String,
    started_sequence: usize,
    workspace: PathBuf,
}

struct JobCompletion {
    id: String,
    result: Result<LinuxJobResult, LinuxRunnerError>,
}

/// Executes a complete trusted Linux workflow plan.
///
/// Every concrete job is structurally and expression-preflighted before the
/// first shell process starts. Jobs receive isolated empty workspaces, matching
/// a hosted runner before an explicit checkout or artifact action. The current
/// contract supports static matrices and `run` steps only; actions, reusable
/// workflows, dynamic matrices, job outputs, and job timeouts fail closed.
///
/// # Security
///
/// The caller must independently establish that every `run` source is trusted
/// and explicitly set `allow_host_process_execution`.
///
/// # Errors
///
/// Returns an error before execution for unsupported syntax or policy, and
/// aborts all running jobs if a local runner-engine error occurs.
pub async fn execute_trusted_linux_workflow(
    plan: &WorkflowPlan,
    config: &TrustedLinuxWorkflowConfig,
) -> Result<LinuxWorkflowResult, LinuxWorkflowError> {
    let prepared = preflight_workflow(plan, config)?;
    let workspaces = create_isolated_workspaces(plan, config).await?;
    schedule_workflow(plan, config, prepared, workspaces).await
}

fn preflight_workflow(
    plan: &WorkflowPlan,
    config: &TrustedLinuxWorkflowConfig,
) -> Result<PreparedWorkflow, LinuxWorkflowError> {
    if !config.allow_host_process_execution {
        return Err(LinuxWorkflowError::new(
            "host_execution_not_authorized",
            "trusted Linux workflow execution requires explicit host-process authorization",
        ));
    }
    if config.maximum_parallel_jobs == 0 {
        return Err(LinuxWorkflowError::new(
            "invalid_parallel_policy",
            "maximum_parallel_jobs must be greater than zero",
        ));
    }
    if config.default_step_timeout.is_zero() || config.maximum_step_timeout.is_zero() {
        return Err(LinuxWorkflowError::new(
            "invalid_timeout_policy",
            "step timeout policy must be greater than zero",
        ));
    }
    if config.maximum_output_bytes == 0 {
        return Err(LinuxWorkflowError::new(
            "invalid_output_policy",
            "step output limit must be greater than zero",
        ));
    }
    if plan.jobs.is_empty() {
        return Err(LinuxWorkflowError::new(
            "empty_workflow_plan",
            "workflow plan must contain at least one concrete job",
        ));
    }

    let all_ids = plan
        .jobs
        .iter()
        .map(|job| job.id.as_str())
        .collect::<BTreeSet<_>>();
    if all_ids.len() != plan.jobs.len() {
        return Err(LinuxWorkflowError::new(
            "duplicate_job_instance",
            "workflow plan contains duplicate concrete job identifiers",
        ));
    }
    let base_ids = plan
        .job_order
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    let mut continue_on_error = BTreeMap::new();
    let mut controls_by_base = BTreeMap::<String, (bool, Option<usize>)>::new();

    for job in &plan.jobs {
        if !base_ids.contains(job.base_job_id.as_str()) {
            return Err(LinuxWorkflowError::new(
                "unknown_base_job",
                format!(
                    "concrete job {:?} refers to base job {:?} outside job_order",
                    job.id, job.base_job_id
                ),
            ));
        }
        if let Some(missing) = job
            .needs_instances
            .iter()
            .find(|dependency| !all_ids.contains(dependency.as_str()))
        {
            return Err(LinuxWorkflowError::new(
                "unknown_dependency_instance",
                format!("job {:?} needs unknown concrete job {missing:?}", job.id),
            ));
        }
        match controls_by_base.get(&job.base_job_id) {
            Some(controls) if controls != &(job.fail_fast, job.max_parallel) => {
                return Err(LinuxWorkflowError::new(
                    "inconsistent_matrix_controls",
                    format!(
                        "matrix instances for {:?} disagree on fail-fast or max-parallel",
                        job.base_job_id
                    ),
                ));
            }
            None => {
                controls_by_base.insert(job.base_job_id.clone(), (job.fail_fast, job.max_parallel));
            }
            Some(_) => {}
        }

        if let Some(condition) = job.condition.as_deref() {
            validate_expression(unwrap_expression(condition), &["needs"], true)?;
        }
        let tolerated = evaluate_job_continue_on_error(job)?;
        continue_on_error.insert(job.id.clone(), tolerated);

        let mut runner_job = job.clone();
        runner_job.condition = None;
        runner_job.continue_on_error = None;
        preflight_trusted_linux_job(&runner_job).map_err(|error| {
            LinuxWorkflowError::new(
                error.code,
                format!(
                    "job {:?} failed whole-workflow preflight: {}",
                    job.id, error.message
                ),
            )
        })?;
    }

    Ok(PreparedWorkflow { continue_on_error })
}

fn evaluate_job_continue_on_error(job: &PlannedJob) -> Result<bool, LinuxWorkflowError> {
    let Some(value) = job.continue_on_error.as_ref() else {
        return Ok(false);
    };
    if let Value::Bool(value) = value {
        return Ok(*value);
    }
    let Value::String(source) = value else {
        return Err(LinuxWorkflowError::new(
            "invalid_job_continue_on_error",
            format!(
                "job {:?} continue-on-error must be a boolean or whole expression",
                job.id
            ),
        ));
    };
    let expression = wrapped_expression(source).ok_or_else(|| {
        LinuxWorkflowError::new(
            "invalid_job_continue_on_error",
            format!(
                "job {:?} continue-on-error string must be one whole expression",
                job.id
            ),
        )
    })?;
    validate_expression(expression, &["matrix"], false)?;
    let context = ExpressionContext::new().with_root("matrix", matrix_value(&job.matrix));
    let evaluated = evaluate_wrapped_expression(source, &context)?.ok_or_else(|| {
        LinuxWorkflowError::new(
            "invalid_job_continue_on_error",
            "expression wrapper disappeared during evaluation",
        )
    })?;
    evaluated.as_bool().ok_or_else(|| {
        LinuxWorkflowError::new(
            "invalid_job_continue_on_error",
            format!(
                "job {:?} continue-on-error resolved to {evaluated}, not a boolean",
                job.id
            ),
        )
    })
}

async fn create_isolated_workspaces(
    plan: &WorkflowPlan,
    config: &TrustedLinuxWorkflowConfig,
) -> Result<Vec<PathBuf>, LinuxWorkflowError> {
    fs::create_dir_all(&config.run_root)
        .await
        .map_err(|error| {
            LinuxWorkflowError::new(
                "run_root_unavailable",
                format!(
                    "could not create workflow run root {}: {error}",
                    config.run_root.display()
                ),
            )
        })?;
    let root = fs::canonicalize(&config.run_root).await.map_err(|error| {
        LinuxWorkflowError::new(
            "run_root_unavailable",
            format!(
                "could not resolve workflow run root {}: {error}",
                config.run_root.display()
            ),
        )
    })?;
    if !root.is_dir() {
        return Err(LinuxWorkflowError::new(
            "invalid_run_root",
            format!("workflow run root {} is not a directory", root.display()),
        ));
    }

    let run_directory = root.join(format!("gha-indie-workflow-{}", Uuid::new_v4().simple()));
    fs::create_dir(&run_directory).await.map_err(|error| {
        LinuxWorkflowError::new(
            "run_directory_unavailable",
            format!("could not create isolated workflow run directory: {error}"),
        )
    })?;

    let mut workspaces = Vec::with_capacity(plan.jobs.len());
    for (index, job) in plan.jobs.iter().enumerate() {
        let workspace = run_directory.join(format!(
            "job-{index:04}-{}",
            filesystem_component(&job.base_job_id)
        ));
        fs::create_dir(&workspace).await.map_err(|error| {
            LinuxWorkflowError::new(
                "job_workspace_unavailable",
                format!("could not create workspace for job {:?}: {error}", job.id),
            )
        })?;
        workspaces.push(workspace);
    }
    Ok(workspaces)
}

async fn schedule_workflow(
    plan: &WorkflowPlan,
    config: &TrustedLinuxWorkflowConfig,
    prepared: PreparedWorkflow,
    workspaces: Vec<PathBuf>,
) -> Result<LinuxWorkflowResult, LinuxWorkflowError> {
    let run_directory = workspaces
        .first()
        .and_then(|workspace| workspace.parent())
        .map(PathBuf::from)
        .ok_or_else(|| {
            LinuxWorkflowError::new(
                "internal_scheduler_error",
                "workflow workspaces have no run directory",
            )
        })?;
    let workspace_by_id = plan
        .jobs
        .iter()
        .zip(workspaces)
        .map(|(job, workspace)| (job.id.clone(), workspace))
        .collect::<BTreeMap<_, _>>();
    let jobs_by_id = plan
        .jobs
        .iter()
        .map(|job| (job.id.as_str(), job))
        .collect::<BTreeMap<_, _>>();
    let mut pending = plan
        .jobs
        .iter()
        .map(|job| job.id.clone())
        .collect::<BTreeSet<_>>();
    let mut terminal = BTreeMap::<String, LinuxWorkflowJobResult>::new();
    let mut running = BTreeMap::<String, RunningJob>::new();
    let mut running_by_base = BTreeMap::<String, usize>::new();
    let mut max_by_base = BTreeMap::<String, usize>::new();
    let mut tasks = JoinSet::<JobCompletion>::new();
    let mut intentionally_cancelled = HashSet::<Id>::new();
    let mut started_sequence = 0usize;
    let mut completed_sequence = 0usize;
    let mut max_observed_parallel = 0usize;

    while terminal.len() < plan.jobs.len() {
        let mut progressed = false;
        for job in &plan.jobs {
            if !pending.contains(&job.id)
                || !job
                    .needs_instances
                    .iter()
                    .all(|dependency| terminal.contains_key(dependency))
            {
                continue;
            }

            let should_run = evaluate_job_condition(job, plan, &terminal)?;
            if !should_run {
                pending.remove(&job.id);
                completed_sequence += 1;
                terminal.insert(
                    job.id.clone(),
                    terminal_job(
                        job,
                        WorkflowJobOutcome::Skipped,
                        WorkflowJobConclusion::Skipped,
                        prepared.continue_on_error[&job.id],
                        None,
                        completed_sequence,
                        workspace_by_id[&job.id].clone(),
                        None,
                    ),
                );
                progressed = true;
                continue;
            }

            if running.len() >= config.maximum_parallel_jobs {
                continue;
            }
            let base_running = running_by_base.get(&job.base_job_id).copied().unwrap_or(0);
            let base_limit = job.max_parallel.unwrap_or(config.maximum_parallel_jobs);
            if base_running >= base_limit {
                continue;
            }

            let mut runner_job = job.clone();
            runner_job.condition = None;
            runner_job.continue_on_error = None;
            let workspace = workspace_by_id[&job.id].clone();
            let runner_config = config.job_config(workspace.clone());
            let id = job.id.clone();
            let task_id = id.clone();
            let abort = tasks.spawn(async move {
                let result = execute_trusted_linux_job(&runner_job, &runner_config).await;
                JobCompletion {
                    id: task_id,
                    result,
                }
            });

            started_sequence += 1;
            pending.remove(&job.id);
            running.insert(
                job.id.clone(),
                RunningJob {
                    abort,
                    base_job_id: job.base_job_id.clone(),
                    started_sequence,
                    workspace,
                },
            );
            let base_running = base_running + 1;
            running_by_base.insert(job.base_job_id.clone(), base_running);
            max_by_base
                .entry(job.base_job_id.clone())
                .and_modify(|maximum| *maximum = (*maximum).max(base_running))
                .or_insert(base_running);
            max_observed_parallel = max_observed_parallel.max(running.len());
            progressed = true;
        }

        if terminal.len() == plan.jobs.len() {
            break;
        }

        if !running.is_empty() || !intentionally_cancelled.is_empty() {
            let Some(joined) = tasks.join_next_with_id().await else {
                abort_all(&mut tasks).await;
                return Err(LinuxWorkflowError::new(
                    "scheduler_task_lost",
                    "a running workflow job disappeared without a completion",
                ));
            };
            let (task_id, completion) = match joined {
                Ok(completion) => completion,
                Err(error) if intentionally_cancelled.remove(&error.id()) => continue,
                Err(error) => {
                    abort_all(&mut tasks).await;
                    return Err(LinuxWorkflowError::new(
                        "scheduler_task_failed",
                        format!("workflow job task failed: {error}"),
                    ));
                }
            };
            let Some(running_job) = running.remove(&completion.id) else {
                if intentionally_cancelled.remove(&task_id) {
                    continue;
                }
                abort_all(&mut tasks).await;
                return Err(LinuxWorkflowError::new(
                    "unexpected_job_completion",
                    format!("job {:?} completed while not running", completion.id),
                ));
            };
            decrement_running(&mut running_by_base, &running_job.base_job_id);
            let job = jobs_by_id[completion.id.as_str()];
            let runner_result = match completion.result {
                Ok(result) => result,
                Err(error) => {
                    abort_all(&mut tasks).await;
                    return Err(LinuxWorkflowError::new(
                        error.code,
                        format!("job {:?} runner engine failed: {}", job.id, error.message),
                    ));
                }
            };
            let outcome = match runner_result.conclusion {
                JobConclusion::Success => WorkflowJobOutcome::Success,
                JobConclusion::Failure => WorkflowJobOutcome::Failure,
                JobConclusion::TimedOut => WorkflowJobOutcome::TimedOut,
            };
            let tolerated = prepared.continue_on_error[&job.id];
            let conclusion = match outcome {
                WorkflowJobOutcome::Success => WorkflowJobConclusion::Success,
                WorkflowJobOutcome::Failure => WorkflowJobConclusion::Failure,
                WorkflowJobOutcome::TimedOut => WorkflowJobConclusion::TimedOut,
                WorkflowJobOutcome::Skipped | WorkflowJobOutcome::Cancelled => unreachable!(),
            };
            completed_sequence += 1;
            terminal.insert(
                job.id.clone(),
                terminal_job(
                    job,
                    outcome,
                    conclusion,
                    tolerated,
                    Some(running_job.started_sequence),
                    completed_sequence,
                    running_job.workspace,
                    Some(runner_result),
                ),
            );

            if matches!(
                outcome,
                WorkflowJobOutcome::Failure | WorkflowJobOutcome::TimedOut
            ) && !tolerated
                && job.fail_fast
            {
                cancel_matrix_siblings(
                    job,
                    plan,
                    &prepared,
                    &workspace_by_id,
                    &mut pending,
                    &mut running,
                    &mut running_by_base,
                    &mut terminal,
                    &mut intentionally_cancelled,
                    &mut completed_sequence,
                );
            }
            continue;
        }

        if !progressed {
            abort_all(&mut tasks).await;
            return Err(LinuxWorkflowError::new(
                "scheduler_stalled",
                "workflow graph has pending jobs but no runnable or running job",
            ));
        }
    }

    while let Some(joined) = tasks.join_next_with_id().await {
        if let Err(error) = joined {
            if intentionally_cancelled.remove(&error.id()) {
                continue;
            }
            return Err(LinuxWorkflowError::new(
                "scheduler_task_failed",
                format!("workflow job task failed during shutdown: {error}"),
            ));
        }
    }

    let jobs = plan
        .jobs
        .iter()
        .map(|job| {
            terminal.remove(&job.id).ok_or_else(|| {
                LinuxWorkflowError::new(
                    "missing_job_result",
                    format!("job {:?} has no terminal result", job.id),
                )
            })
        })
        .collect::<Result<Vec<_>, _>>()?;
    let job_groups = plan
        .job_order
        .iter()
        .map(|base_job_id| {
            let instances = jobs
                .iter()
                .filter(|job| &job.base_job_id == base_job_id)
                .collect::<Vec<_>>();
            LinuxWorkflowJobGroupResult {
                base_job_id: base_job_id.clone(),
                instance_ids: instances.iter().map(|job| job.id.clone()).collect(),
                conclusion: aggregate_conclusion(
                    instances.into_iter().map(|job| job.effective_conclusion),
                ),
                max_observed_parallel: max_by_base.get(base_job_id).copied().unwrap_or(0),
            }
        })
        .collect::<Vec<_>>();
    let conclusion = if jobs.iter().any(|job| {
        matches!(
            job.effective_conclusion,
            WorkflowJobConclusion::Failure | WorkflowJobConclusion::TimedOut
        )
    }) {
        WorkflowConclusion::Failure
    } else {
        WorkflowConclusion::Success
    };

    Ok(LinuxWorkflowResult {
        schema_version: LINUX_WORKFLOW_SCHEMA_VERSION,
        workflow_name: plan.name.clone(),
        conclusion,
        run_directory,
        max_observed_parallel,
        jobs,
        job_groups,
    })
}

fn evaluate_job_condition(
    job: &PlannedJob,
    plan: &WorkflowPlan,
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> Result<bool, LinuxWorkflowError> {
    let (needs, status) = needs_context(job, plan, terminal);
    let Some(condition) = job.condition.as_deref() else {
        return Ok(status.success);
    };
    let condition = unwrap_expression(condition);
    let explicit_status = uses_status_function(condition)?;
    let context = ExpressionContext::new()
        .with_root("needs", needs)
        .with_status(status);
    let matches = evaluate_condition(condition, &context)?;
    Ok(matches && (explicit_status || status.success))
}

fn needs_context(
    job: &PlannedJob,
    plan: &WorkflowPlan,
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> (Value, StatusContext) {
    let mut needs = Map::new();
    let mut success = true;
    let mut failure = false;
    let mut cancelled = false;
    for base_job_id in &job.needs {
        let conclusion = aggregate_conclusion(
            plan.jobs
                .iter()
                .filter(|candidate| &candidate.base_job_id == base_job_id)
                .filter_map(|candidate| terminal.get(&candidate.id))
                .map(|result| result.effective_conclusion),
        );
        success &= conclusion == WorkflowJobConclusion::Success;
        failure |= matches!(
            conclusion,
            WorkflowJobConclusion::Failure | WorkflowJobConclusion::TimedOut
        );
        cancelled |= conclusion == WorkflowJobConclusion::Cancelled;
        needs.insert(
            base_job_id.clone(),
            Value::Object(Map::from_iter([
                (
                    "result".to_string(),
                    Value::String(needs_result_name(conclusion).to_string()),
                ),
                ("outputs".to_string(), Value::Object(Map::new())),
            ])),
        );
    }
    (
        Value::Object(needs),
        StatusContext {
            success,
            failure,
            cancelled,
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn cancel_matrix_siblings(
    failed: &PlannedJob,
    plan: &WorkflowPlan,
    prepared: &PreparedWorkflow,
    workspace_by_id: &BTreeMap<String, PathBuf>,
    pending: &mut BTreeSet<String>,
    running: &mut BTreeMap<String, RunningJob>,
    running_by_base: &mut BTreeMap<String, usize>,
    terminal: &mut BTreeMap<String, LinuxWorkflowJobResult>,
    intentionally_cancelled: &mut HashSet<Id>,
    completed_sequence: &mut usize,
) {
    for sibling in plan
        .jobs
        .iter()
        .filter(|job| job.base_job_id == failed.base_job_id && job.id != failed.id)
    {
        if pending.remove(&sibling.id) {
            *completed_sequence += 1;
            terminal.insert(
                sibling.id.clone(),
                terminal_job(
                    sibling,
                    WorkflowJobOutcome::Cancelled,
                    WorkflowJobConclusion::Cancelled,
                    prepared.continue_on_error[&sibling.id],
                    None,
                    *completed_sequence,
                    workspace_by_id[&sibling.id].clone(),
                    None,
                ),
            );
            continue;
        }
        let should_abort = running
            .get(&sibling.id)
            .is_some_and(|running_job| !running_job.abort.is_finished());
        if !should_abort {
            continue;
        }
        if let Some(running_job) = running.remove(&sibling.id) {
            let task_id = running_job.abort.id();
            running_job.abort.abort();
            intentionally_cancelled.insert(task_id);
            decrement_running(running_by_base, &running_job.base_job_id);
            *completed_sequence += 1;
            terminal.insert(
                sibling.id.clone(),
                terminal_job(
                    sibling,
                    WorkflowJobOutcome::Cancelled,
                    WorkflowJobConclusion::Cancelled,
                    prepared.continue_on_error[&sibling.id],
                    Some(running_job.started_sequence),
                    *completed_sequence,
                    running_job.workspace,
                    None,
                ),
            );
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn terminal_job(
    job: &PlannedJob,
    outcome: WorkflowJobOutcome,
    conclusion: WorkflowJobConclusion,
    continue_on_error: bool,
    started_sequence: Option<usize>,
    completed_sequence: usize,
    workspace: PathBuf,
    runner_result: Option<LinuxJobResult>,
) -> LinuxWorkflowJobResult {
    let effective_conclusion = if continue_on_error
        && matches!(
            conclusion,
            WorkflowJobConclusion::Failure | WorkflowJobConclusion::TimedOut
        ) {
        WorkflowJobConclusion::Success
    } else {
        conclusion
    };
    LinuxWorkflowJobResult {
        id: job.id.clone(),
        base_job_id: job.base_job_id.clone(),
        name: job.name.clone(),
        matrix: job.matrix.clone(),
        needs: job.needs.clone(),
        outcome,
        conclusion,
        effective_conclusion,
        continue_on_error,
        started_sequence,
        completed_sequence,
        workspace,
        runner_result,
    }
}

fn aggregate_conclusion(
    conclusions: impl Iterator<Item = WorkflowJobConclusion>,
) -> WorkflowJobConclusion {
    let conclusions = conclusions.collect::<Vec<_>>();
    if conclusions.contains(&WorkflowJobConclusion::TimedOut) {
        return WorkflowJobConclusion::TimedOut;
    }
    if conclusions.contains(&WorkflowJobConclusion::Failure) {
        return WorkflowJobConclusion::Failure;
    }
    if conclusions.contains(&WorkflowJobConclusion::Cancelled) {
        return WorkflowJobConclusion::Cancelled;
    }
    if !conclusions.is_empty()
        && conclusions
            .iter()
            .all(|value| *value == WorkflowJobConclusion::Skipped)
    {
        return WorkflowJobConclusion::Skipped;
    }
    WorkflowJobConclusion::Success
}

const fn needs_result_name(conclusion: WorkflowJobConclusion) -> &'static str {
    match conclusion {
        WorkflowJobConclusion::Success => "success",
        WorkflowJobConclusion::Failure | WorkflowJobConclusion::TimedOut => "failure",
        WorkflowJobConclusion::Skipped => "skipped",
        WorkflowJobConclusion::Cancelled => "cancelled",
    }
}

fn decrement_running(running_by_base: &mut BTreeMap<String, usize>, base_job_id: &str) {
    if let Some(running) = running_by_base.get_mut(base_job_id) {
        *running = running.saturating_sub(1);
    }
}

async fn abort_all(tasks: &mut JoinSet<JobCompletion>) {
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
}

fn matrix_value(matrix: &BTreeMap<String, Value>) -> Value {
    Value::Object(
        matrix
            .iter()
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect(),
    )
}

fn unwrap_expression(value: &str) -> &str {
    wrapped_expression(value).unwrap_or_else(|| value.trim())
}

fn wrapped_expression(value: &str) -> Option<&str> {
    let value = value.trim();
    value
        .strip_prefix("${{")
        .and_then(|inner| inner.strip_suffix("}}"))
        .map(str::trim)
}

fn filesystem_component(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '-' | '_') {
                character
            } else {
                '_'
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use crate::workflow::plan_workflow;

    use super::*;

    struct TestRoot(PathBuf);

    impl TestRoot {
        fn create() -> Self {
            let path = std::env::temp_dir().join(format!(
                "gha-indie-workflow-test-{}",
                Uuid::new_v4().simple()
            ));
            std::fs::create_dir(&path).expect("test run root should be created");
            Self(path)
        }

        fn config(&self, maximum_parallel_jobs: usize) -> TrustedLinuxWorkflowConfig {
            let mut config = TrustedLinuxWorkflowConfig::new(&self.0);
            config.allow_host_process_execution = true;
            config.maximum_parallel_jobs = maximum_parallel_jobs;
            config.default_step_timeout = Duration::from_secs(5);
            config.maximum_step_timeout = Duration::from_secs(5);
            config
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn job<'a>(result: &'a LinuxWorkflowResult, id: &str) -> &'a LinuxWorkflowJobResult {
        result
            .jobs
            .iter()
            .find(|job| job.id == id)
            .expect("job result should exist")
    }

    fn group<'a>(result: &'a LinuxWorkflowResult, id: &str) -> &'a LinuxWorkflowJobGroupResult {
        result
            .job_groups
            .iter()
            .find(|group| group.base_job_id == id)
            .expect("job group should exist")
    }

    #[tokio::test]
    async fn schedules_matrix_needs_skips_and_tolerated_failures() {
        let plan = plan_workflow(
            r#"
name: Scheduler parity
jobs:
  matrix_job:
    runs-on: ubuntu-latest
    continue-on-error: ${{ matrix.experimental }}
    strategy:
      fail-fast: true
      max-parallel: 2
      matrix:
        include:
          - label: stable-one
            experimental: false
          - label: experimental
            experimental: true
          - label: stable-two
            experimental: false
    steps:
      - run: |
          printf '%s' '${{ matrix.label }}' > marker
          sleep 0.05
          if [ '${{ matrix.experimental }}' = 'true' ]; then false; fi
  after_matrix:
    needs: matrix_job
    runs-on: ubuntu-latest
    steps:
      - run: printf 'after' > marker
  skip_job:
    if: false
    runs-on: ubuntu-latest
    steps:
      - run: printf 'must-not-run' > marker
  recover_skip:
    needs: skip_job
    if: ${{ always() && needs.skip_job.result == 'skipped' }}
    runs-on: ubuntu-latest
    steps:
      - run: printf 'recovered' > marker
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let result = execute_trusted_linux_workflow(&plan, &root.config(4))
            .await
            .expect("workflow should execute");

        assert_eq!(result.schema_version, LINUX_WORKFLOW_SCHEMA_VERSION);
        assert_eq!(result.conclusion, WorkflowConclusion::Success);
        assert_eq!(
            group(&result, "matrix_job").conclusion,
            WorkflowJobConclusion::Success
        );
        assert_eq!(group(&result, "matrix_job").max_observed_parallel, 2);
        assert_eq!(result.max_observed_parallel, 3);
        assert_eq!(
            job(&result, "matrix_job[1]").outcome,
            WorkflowJobOutcome::Success
        );
        assert_eq!(
            job(&result, "matrix_job[2]").outcome,
            WorkflowJobOutcome::Failure
        );
        assert_eq!(
            job(&result, "matrix_job[2]").conclusion,
            WorkflowJobConclusion::Failure
        );
        assert_eq!(
            job(&result, "matrix_job[2]").effective_conclusion,
            WorkflowJobConclusion::Success
        );
        assert!(job(&result, "matrix_job[2]").continue_on_error);
        assert_eq!(
            job(&result, "matrix_job[3]").outcome,
            WorkflowJobOutcome::Success
        );
        assert_eq!(
            job(&result, "after_matrix").conclusion,
            WorkflowJobConclusion::Success
        );
        assert_eq!(
            job(&result, "skip_job").conclusion,
            WorkflowJobConclusion::Skipped
        );
        assert_eq!(
            job(&result, "recover_skip").conclusion,
            WorkflowJobConclusion::Success
        );

        for matrix_id in ["matrix_job[1]", "matrix_job[2]", "matrix_job[3]"] {
            let matrix_job = job(&result, matrix_id);
            assert!(matrix_job.workspace.join("marker").is_file());
            assert_eq!(
                std::fs::read_dir(&matrix_job.workspace)
                    .expect("workspace should be readable")
                    .count(),
                1
            );
        }
        assert!(!job(&result, "skip_job").workspace.join("marker").exists());
    }

    #[tokio::test]
    async fn fail_fast_cancels_queued_matrix_siblings() {
        let plan = plan_workflow(
            r#"
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: true
      max-parallel: 1
      matrix:
        value: [first, second, third]
    steps:
      - run: |
          printf '%s' '${{ matrix.value }}' > marker
          if [ '${{ matrix.value }}' = 'first' ]; then false; fi
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let result = execute_trusted_linux_workflow(&plan, &root.config(3))
            .await
            .expect("command failure should be a workflow result");

        assert_eq!(result.conclusion, WorkflowConclusion::Failure);
        assert_eq!(
            job(&result, "build[1]").conclusion,
            WorkflowJobConclusion::Failure
        );
        for id in ["build[2]", "build[3]"] {
            let cancelled = job(&result, id);
            assert_eq!(cancelled.conclusion, WorkflowJobConclusion::Cancelled);
            assert!(cancelled.started_sequence.is_none());
            assert!(!cancelled.workspace.join("marker").exists());
        }
        assert_eq!(group(&result, "build").max_observed_parallel, 1);
    }

    #[tokio::test]
    async fn fail_fast_aborts_an_in_progress_matrix_sibling() {
        let plan = plan_workflow(
            r#"
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: true
      max-parallel: 2
      matrix:
        value: [failure, long-running, queued]
    steps:
      - run: |
          if [ '${{ matrix.value }}' = 'failure' ]; then sleep 0.05; false; fi
          sleep 5
          printf finished > finished
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let result = execute_trusted_linux_workflow(&plan, &root.config(2))
            .await
            .expect("command failure should be a workflow result");

        assert_eq!(result.conclusion, WorkflowConclusion::Failure);
        assert_eq!(
            job(&result, "build[1]").conclusion,
            WorkflowJobConclusion::Failure
        );
        let in_progress = job(&result, "build[2]");
        assert_eq!(in_progress.conclusion, WorkflowJobConclusion::Cancelled);
        assert!(in_progress.started_sequence.is_some());
        assert!(!in_progress.workspace.join("finished").exists());
        let queued = job(&result, "build[3]");
        assert_eq!(queued.conclusion, WorkflowJobConclusion::Cancelled);
        assert!(queued.started_sequence.is_none());
        assert_eq!(group(&result, "build").max_observed_parallel, 2);
    }

    #[tokio::test]
    async fn dependency_failure_skips_default_and_runs_failure_handler() {
        let plan = plan_workflow(
            r#"
jobs:
  root:
    runs-on: ubuntu-latest
    steps:
      - run: false
  default_child:
    needs: root
    runs-on: ubuntu-latest
    steps:
      - run: false
  handler:
    needs: root
    if: ${{ failure() && needs.root.result == 'failure' }}
    runs-on: ubuntu-latest
    steps:
      - run: true
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let result = execute_trusted_linux_workflow(&plan, &root.config(2))
            .await
            .expect("workflow should execute");

        assert_eq!(result.conclusion, WorkflowConclusion::Failure);
        assert_eq!(
            job(&result, "root").conclusion,
            WorkflowJobConclusion::Failure
        );
        assert_eq!(
            job(&result, "default_child").conclusion,
            WorkflowJobConclusion::Skipped
        );
        assert_eq!(
            job(&result, "handler").conclusion,
            WorkflowJobConclusion::Success
        );
    }

    #[tokio::test]
    async fn preflights_every_job_before_starting_shell_source() {
        let plan = plan_workflow(
            r#"
jobs:
  first:
    runs-on: ubuntu-latest
    steps:
      - run: printf started > marker
  unsupported:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let error = execute_trusted_linux_workflow(&plan, &root.config(2))
            .await
            .expect_err("action step must fail closed");

        assert_eq!(error.code, "unsupported_action");
        assert!(!contains_file_named(&root.0, "marker"));
    }

    #[tokio::test]
    async fn rejects_unsupported_job_context_and_timeout_before_execution() {
        let invalid_context = plan_workflow(
            r#"
jobs:
  build:
    if: ${{ matrix.enabled }}
    runs-on: ubuntu-latest
    strategy:
      matrix:
        enabled: [true]
    steps:
      - run: true
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let error = execute_trusted_linux_workflow(&invalid_context, &root.config(1))
            .await
            .expect_err("matrix in job if must fail closed");
        assert_eq!(error.code, "unsupported_context");

        let timeout = plan_workflow(
            r#"
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 2
    steps:
      - run: true
"#,
        )
        .expect("workflow should plan");
        let error = execute_trusted_linux_workflow(&timeout, &root.config(1))
            .await
            .expect_err("job timeout must fail closed");
        assert_eq!(error.code, "unsupported_job_timeout");
    }

    fn contains_file_named(root: &Path, name: &str) -> bool {
        let Ok(entries) = std::fs::read_dir(root) else {
            return false;
        };
        entries.flatten().any(|entry| {
            let path = entry.path();
            path.file_name().is_some_and(|value| value == name)
                || path.is_dir() && contains_file_named(&path, name)
        })
    }
}
