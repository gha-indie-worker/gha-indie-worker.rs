//! Whole-workflow scheduling for the trusted, run-step-only Linux subset.
//!
//! This layer deliberately remains separate from webhook intake. It accepts an
//! already validated [`WorkflowPlan`], preflights every concrete job before any
//! workflow shell source starts, creates an isolated workspace for each job,
//! and schedules the dependency graph with GitHub-compatible matrix controls.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::path::{Path, PathBuf};
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
    execute_trusted_linux_job_with_context, preflight_trusted_linux_job_syntax, JobConclusion,
    LinuxJobResult, LinuxRunnerError, TrustedLinuxJobContext, TrustedLinuxRunnerConfig,
};
use crate::workflow::{expand_dynamic_matrix, materialize_dynamic_jobs, PlannedJob, WorkflowPlan};

pub const LINUX_WORKFLOW_SCHEMA_VERSION: &str = "gha-indie-worker.linux-workflow.v2";

const DEFAULT_OUTPUT_LIMIT: usize = 10 * 1024 * 1024;
const MAX_WORKFLOW_OUTPUT_UTF16_BYTES: usize = 50 * 1024 * 1024;

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
    pub outputs: BTreeMap<String, String>,
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
    pub outputs: BTreeMap<String, String>,
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

struct PreparedWorkflow;

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
/// first shell process starts. Deferred values are resolved after their direct
/// dependencies finish and before the dependent job starts. Jobs receive
/// isolated empty workspaces, matching a hosted runner before an explicit
/// checkout or artifact action. The current contract supports static and
/// output-driven matrices plus `run` steps; actions, reusable workflows,
/// secret contexts, and job timeouts fail closed.
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
    let run_directory = create_run_directory(config).await?;
    schedule_workflow(plan, config, prepared, run_directory).await
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
        validate_job_continue_on_error(job)?;
        if let Some(source) = job.matrix_expression.as_deref() {
            let expression = wrapped_expression(source).ok_or_else(|| {
                LinuxWorkflowError::new(
                    "invalid_matrix",
                    format!(
                        "job {:?} dynamic matrix must be one whole expression",
                        job.id
                    ),
                )
            })?;
            validate_expression(expression, &["needs"], false)?;
        }

        let mut runner_job = job.clone();
        runner_job.condition = None;
        runner_job.continue_on_error = None;
        preflight_trusted_linux_job_syntax(&runner_job).map_err(|error| {
            LinuxWorkflowError::new(
                error.code,
                format!(
                    "job {:?} failed whole-workflow preflight: {}",
                    job.id, error.message
                ),
            )
        })?;
    }

    Ok(PreparedWorkflow)
}

fn validate_job_continue_on_error(job: &PlannedJob) -> Result<(), LinuxWorkflowError> {
    let Some(value) = job.continue_on_error.as_ref() else {
        return Ok(());
    };
    if value.is_boolean() {
        return Ok(());
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
    validate_expression(expression, &["matrix", "needs"], false)?;
    Ok(())
}

fn evaluate_job_continue_on_error(
    job: &PlannedJob,
    needs: Value,
) -> Result<bool, LinuxWorkflowError> {
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
    let context = ExpressionContext::new()
        .with_root("matrix", matrix_value(&job.matrix))
        .with_root("needs", needs);
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

async fn create_run_directory(
    config: &TrustedLinuxWorkflowConfig,
) -> Result<PathBuf, LinuxWorkflowError> {
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

    Ok(run_directory)
}

async fn create_job_workspace(
    run_directory: &Path,
    index: usize,
    job: &PlannedJob,
) -> Result<PathBuf, LinuxWorkflowError> {
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
    Ok(workspace)
}

async fn schedule_workflow(
    plan: &WorkflowPlan,
    config: &TrustedLinuxWorkflowConfig,
    _prepared: PreparedWorkflow,
    run_directory: PathBuf,
) -> Result<LinuxWorkflowResult, LinuxWorkflowError> {
    let mut jobs = plan.jobs.clone();
    let mut workspace_by_id = BTreeMap::new();
    let mut workspace_sequence = 0usize;
    for job in jobs.iter().filter(|job| job.matrix_expression.is_none()) {
        let workspace = create_job_workspace(&run_directory, workspace_sequence, job).await?;
        workspace_sequence += 1;
        workspace_by_id.insert(job.id.clone(), workspace);
    }
    let mut expanded_groups = jobs
        .iter()
        .filter(|job| job.matrix_expression.is_none())
        .map(|job| job.base_job_id.clone())
        .collect::<BTreeSet<_>>();
    let mut pending = jobs
        .iter()
        .filter(|job| job.matrix_expression.is_none())
        .map(|job| job.id.clone())
        .collect::<BTreeSet<_>>();
    let mut terminal = BTreeMap::<String, LinuxWorkflowJobResult>::new();
    let mut running = BTreeMap::<String, RunningJob>::new();
    let mut running_by_base = BTreeMap::<String, usize>::new();
    let mut max_by_base = BTreeMap::<String, usize>::new();
    let mut continue_on_error = BTreeMap::<String, bool>::new();
    let mut tasks = JoinSet::<JobCompletion>::new();
    let mut intentionally_cancelled = HashSet::<Id>::new();
    let mut started_sequence = 0usize;
    let mut completed_sequence = 0usize;
    let mut max_observed_parallel = 0usize;
    let mut workflow_output_utf16_bytes = 0usize;

    while !all_job_groups_terminal(plan, &jobs, &expanded_groups, &terminal) {
        let mut progressed = false;

        for base_job_id in &plan.job_order {
            if expanded_groups.contains(base_job_id) {
                continue;
            }
            let Some(template_index) = jobs.iter().position(|job| &job.base_job_id == base_job_id)
            else {
                abort_all(&mut tasks).await;
                return Err(LinuxWorkflowError::new(
                    "missing_dynamic_template",
                    format!("deferred job {base_job_id:?} has no template"),
                ));
            };
            let template = jobs[template_index].clone();
            if !template
                .needs
                .iter()
                .all(|dependency| group_is_terminal(dependency, &jobs, &expanded_groups, &terminal))
            {
                continue;
            }

            if !evaluate_job_condition(&template, &jobs, &terminal)? {
                let workspace =
                    create_job_workspace(&run_directory, workspace_sequence, &template).await?;
                workspace_sequence += 1;
                workspace_by_id.insert(template.id.clone(), workspace.clone());
                completed_sequence += 1;
                terminal.insert(
                    template.id.clone(),
                    terminal_job(
                        &template,
                        WorkflowJobOutcome::Skipped,
                        WorkflowJobConclusion::Skipped,
                        false,
                        None,
                        completed_sequence,
                        workspace,
                        None,
                    ),
                );
                expanded_groups.insert(base_job_id.clone());
                progressed = true;
                continue;
            }

            let matrices = evaluate_dynamic_matrix(&template, &jobs, &terminal)?;
            let instances = materialize_dynamic_jobs(&template, matrices);
            jobs.splice(template_index..=template_index, instances.clone());
            for instance in instances {
                let workspace =
                    create_job_workspace(&run_directory, workspace_sequence, &instance).await?;
                workspace_sequence += 1;
                workspace_by_id.insert(instance.id.clone(), workspace);
                pending.insert(instance.id);
            }
            expanded_groups.insert(base_job_id.clone());
            progressed = true;
        }

        for job in jobs.clone() {
            if !pending.contains(&job.id)
                || !job.needs.iter().all(|dependency| {
                    group_is_terminal(dependency, &jobs, &expanded_groups, &terminal)
                })
            {
                continue;
            }

            let should_run = evaluate_job_condition(&job, &jobs, &terminal)?;
            if !should_run {
                pending.remove(&job.id);
                completed_sequence += 1;
                terminal.insert(
                    job.id.clone(),
                    terminal_job(
                        &job,
                        WorkflowJobOutcome::Skipped,
                        WorkflowJobConclusion::Skipped,
                        false,
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

            let (needs, _) = needs_context(&job, &jobs, &terminal);
            let tolerated = evaluate_job_continue_on_error(&job, needs.clone())?;
            continue_on_error.insert(job.id.clone(), tolerated);
            let mut runner_job = job.clone();
            runner_job.condition = None;
            runner_job.continue_on_error = None;
            let workspace = workspace_by_id[&job.id].clone();
            let runner_config = config.job_config(workspace.clone());
            let runner_context = TrustedLinuxJobContext { needs };
            let id = job.id.clone();
            let task_id = id.clone();
            let abort = tasks.spawn(async move {
                let result = execute_trusted_linux_job_with_context(
                    &runner_job,
                    &runner_config,
                    &runner_context,
                )
                .await;
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

        if all_job_groups_terminal(plan, &jobs, &expanded_groups, &terminal) {
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
            let Some(job) = jobs.iter().find(|job| job.id == completion.id).cloned() else {
                abort_all(&mut tasks).await;
                return Err(LinuxWorkflowError::new(
                    "unexpected_job_completion",
                    format!(
                        "completed job {:?} is absent from the runtime plan",
                        completion.id
                    ),
                ));
            };
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
            let tolerated = continue_on_error.get(&job.id).copied().unwrap_or(false);
            let conclusion = match outcome {
                WorkflowJobOutcome::Success => WorkflowJobConclusion::Success,
                WorkflowJobOutcome::Failure => WorkflowJobConclusion::Failure,
                WorkflowJobOutcome::TimedOut => WorkflowJobConclusion::TimedOut,
                WorkflowJobOutcome::Skipped | WorkflowJobOutcome::Cancelled => unreachable!(),
            };
            workflow_output_utf16_bytes =
                add_output_size(workflow_output_utf16_bytes, &runner_result.outputs, &job.id)?;
            completed_sequence += 1;
            terminal.insert(
                job.id.clone(),
                terminal_job(
                    &job,
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
                    &job,
                    &jobs,
                    &continue_on_error,
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

    let jobs = jobs
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
                    instances.iter().map(|job| job.effective_conclusion),
                ),
                max_observed_parallel: max_by_base.get(base_job_id).copied().unwrap_or(0),
                outputs: aggregate_group_outputs(base_job_id, &jobs),
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
    jobs: &[PlannedJob],
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> Result<bool, LinuxWorkflowError> {
    let (needs, status) = needs_context(job, jobs, terminal);
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
    jobs: &[PlannedJob],
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> (Value, StatusContext) {
    let mut needs = Map::new();
    let mut success = true;
    let mut failure = false;
    let mut cancelled = false;
    for base_job_id in &job.needs {
        let conclusion = aggregate_conclusion(
            jobs.iter()
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
        let outputs = aggregate_terminal_group_outputs(base_job_id, terminal);
        needs.insert(
            base_job_id.clone(),
            Value::Object(Map::from_iter([
                (
                    "result".to_string(),
                    Value::String(needs_result_name(conclusion).to_string()),
                ),
                (
                    "outputs".to_string(),
                    Value::Object(
                        outputs
                            .into_iter()
                            .map(|(name, value)| (name, Value::String(value)))
                            .collect(),
                    ),
                ),
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

fn evaluate_dynamic_matrix(
    template: &PlannedJob,
    jobs: &[PlannedJob],
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> Result<Vec<BTreeMap<String, Value>>, LinuxWorkflowError> {
    let source = template.matrix_expression.as_deref().ok_or_else(|| {
        LinuxWorkflowError::new(
            "missing_dynamic_matrix",
            format!("job {:?} has no deferred matrix expression", template.id),
        )
    })?;
    let (needs, status) = needs_context(template, jobs, terminal);
    let context = ExpressionContext::new()
        .with_root("needs", needs)
        .with_status(status);
    let value = evaluate_wrapped_expression(source, &context)?.ok_or_else(|| {
        LinuxWorkflowError::new(
            "invalid_matrix",
            format!(
                "job {:?} dynamic matrix is not one whole expression",
                template.id
            ),
        )
    })?;
    expand_dynamic_matrix(&template.base_job_id, &value)
        .map_err(|error| LinuxWorkflowError::new(error.code, error.message))
}

fn all_job_groups_terminal(
    plan: &WorkflowPlan,
    jobs: &[PlannedJob],
    expanded_groups: &BTreeSet<String>,
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> bool {
    plan.job_order
        .iter()
        .all(|base_job_id| group_is_terminal(base_job_id, jobs, expanded_groups, terminal))
}

fn group_is_terminal(
    base_job_id: &str,
    jobs: &[PlannedJob],
    expanded_groups: &BTreeSet<String>,
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> bool {
    expanded_groups.contains(base_job_id)
        && jobs
            .iter()
            .filter(|job| job.base_job_id == base_job_id)
            .all(|job| terminal.contains_key(&job.id))
}

fn aggregate_terminal_group_outputs(
    base_job_id: &str,
    terminal: &BTreeMap<String, LinuxWorkflowJobResult>,
) -> BTreeMap<String, String> {
    let mut instances = terminal
        .values()
        .filter(|result| result.base_job_id == base_job_id)
        .collect::<Vec<_>>();
    instances.sort_by_key(|result| result.completed_sequence);
    let mut outputs = BTreeMap::new();
    for instance in instances {
        outputs.extend(instance.outputs.clone());
    }
    outputs
}

fn aggregate_group_outputs(
    base_job_id: &str,
    jobs: &[LinuxWorkflowJobResult],
) -> BTreeMap<String, String> {
    let terminal = jobs
        .iter()
        .map(|job| (job.id.clone(), job.clone()))
        .collect::<BTreeMap<_, _>>();
    aggregate_terminal_group_outputs(base_job_id, &terminal)
}

fn add_output_size(
    current: usize,
    outputs: &BTreeMap<String, String>,
    job_id: &str,
) -> Result<usize, LinuxWorkflowError> {
    let additional = outputs.iter().try_fold(0usize, |total, (name, value)| {
        total
            .checked_add(name.encode_utf16().count().saturating_mul(2))
            .and_then(|sum| sum.checked_add(value.encode_utf16().count().saturating_mul(2)))
            .ok_or_else(|| {
                LinuxWorkflowError::new(
                    "workflow_outputs_too_large",
                    "workflow output size overflowed",
                )
            })
    })?;
    let total = current.checked_add(additional).ok_or_else(|| {
        LinuxWorkflowError::new(
            "workflow_outputs_too_large",
            "workflow output size overflowed",
        )
    })?;
    if total > MAX_WORKFLOW_OUTPUT_UTF16_BYTES {
        return Err(LinuxWorkflowError::new(
            "workflow_outputs_too_large",
            format!(
                "job {job_id:?} raised declared workflow outputs to {total} UTF-16 bytes; maximum is {MAX_WORKFLOW_OUTPUT_UTF16_BYTES}"
            ),
        ));
    }
    Ok(total)
}

#[allow(clippy::too_many_arguments)]
fn cancel_matrix_siblings(
    failed: &PlannedJob,
    jobs: &[PlannedJob],
    continue_on_error: &BTreeMap<String, bool>,
    workspace_by_id: &BTreeMap<String, PathBuf>,
    pending: &mut BTreeSet<String>,
    running: &mut BTreeMap<String, RunningJob>,
    running_by_base: &mut BTreeMap<String, usize>,
    terminal: &mut BTreeMap<String, LinuxWorkflowJobResult>,
    intentionally_cancelled: &mut HashSet<Id>,
    completed_sequence: &mut usize,
) {
    for sibling in jobs
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
                    continue_on_error.get(&sibling.id).copied().unwrap_or(false),
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
                    continue_on_error.get(&sibling.id).copied().unwrap_or(false),
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
    let outputs = runner_result
        .as_ref()
        .map(|result| result.outputs.clone())
        .unwrap_or_default();
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
        outputs,
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
    async fn propagates_declared_outputs_and_expands_needs_driven_matrix() {
        let plan = plan_workflow(
            r#"
jobs:
  define:
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.values.outputs.matrix }}
      runner: ${{ steps.values.outputs.runner }}
      greeting: ${{ steps.values.outputs.greeting }}
    steps:
      - id: values
        run: |
          printf '%s\n' 'matrix={"include":[{"color":"red"},{"color":"green"}]}' >> "$GITHUB_OUTPUT"
          printf '%s\n' 'runner=ubuntu-latest' >> "$GITHUB_OUTPUT"
          printf '%s\n' 'greeting=hello' >> "$GITHUB_OUTPUT"
  fanout:
    needs: define
    runs-on: ${{ needs.define.outputs.runner }}
    strategy:
      max-parallel: 2
      matrix: ${{ fromJSON(needs.define.outputs.matrix) }}
    env:
      GREETING: ${{ needs.define.outputs.greeting }}
    outputs:
      seen: ${{ steps.verify.outputs.seen }}
    steps:
      - id: verify
        env:
          NEEDS_RESULT: ${{ needs.define.result }}
        run: |
          test "$GREETING" = hello
          test "$NEEDS_RESULT" = success
          printf 'seen=%s/%s\n' '${{ matrix.color }}' '${{ needs.define.outputs.greeting }}' >> "$GITHUB_OUTPUT"
  observe:
    needs: fanout
    if: ${{ always() && needs.fanout.result == 'success' }}
    runs-on: ubuntu-latest
    env:
      TRANSITIVE: ${{ needs.define.outputs.greeting }}
      MATRIX_SEEN: ${{ needs.fanout.outputs.seen }}
    steps:
      - run: |
          test -z "$TRANSITIVE"
          test -n "$MATRIX_SEEN"
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let result = execute_trusted_linux_workflow(&plan, &root.config(3))
            .await
            .expect("output-driven workflow should execute");

        assert_eq!(result.schema_version, "gha-indie-worker.linux-workflow.v2");
        assert_eq!(result.conclusion, WorkflowConclusion::Success);
        assert_eq!(
            group(&result, "define")
                .outputs
                .get("greeting")
                .map(String::as_str),
            Some("hello")
        );
        let fanout = group(&result, "fanout");
        assert_eq!(fanout.instance_ids, ["fanout[1]", "fanout[2]"]);
        assert_eq!(fanout.max_observed_parallel, 2);
        assert!(matches!(
            fanout.outputs.get("seen").map(String::as_str),
            Some("red/hello" | "green/hello")
        ));
        assert_eq!(
            job(&result, "fanout[1]").matrix.get("color"),
            Some(&Value::String("red".to_string()))
        );
        assert_eq!(
            job(&result, "fanout[2]").matrix.get("color"),
            Some(&Value::String("green".to_string()))
        );
        assert_eq!(
            job(&result, "observe").conclusion,
            WorkflowJobConclusion::Success
        );
    }

    #[tokio::test]
    async fn bounds_deferred_matrix_after_producer_finishes() {
        let plan = plan_workflow(
            r#"
jobs:
  define:
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.values.outputs.matrix }}
    steps:
      - id: values
        run: |
          printf 'matrix={"value":[' >> "$GITHUB_OUTPUT"
          for value in $(seq 1 257); do
            if [ "$value" -gt 1 ]; then printf ',' >> "$GITHUB_OUTPUT"; fi
            printf '%s' "$value" >> "$GITHUB_OUTPUT"
          done
          printf ']}\n' >> "$GITHUB_OUTPUT"
  fanout:
    needs: define
    runs-on: ubuntu-latest
    strategy:
      matrix: ${{ fromJSON(needs.define.outputs.matrix) }}
    steps:
      - run: true
"#,
        )
        .expect("workflow should plan as a deferred matrix");
        let root = TestRoot::create();
        let error = execute_trusted_linux_workflow(&plan, &root.config(2))
            .await
            .expect_err("257 deferred jobs must exceed the per-matrix bound");
        assert_eq!(error.code, "matrix_too_large");
    }

    #[test]
    fn bounds_total_workflow_outputs_using_utf16_estimate() {
        let outputs = BTreeMap::from([("a".to_string(), "b".to_string())]);
        assert_eq!(
            add_output_size(MAX_WORKFLOW_OUTPUT_UTF16_BYTES - 4, &outputs, "job")
                .expect("the exact workflow output limit should be accepted"),
            MAX_WORKFLOW_OUTPUT_UTF16_BYTES
        );
        let error = add_output_size(MAX_WORKFLOW_OUTPUT_UTF16_BYTES - 2, &outputs, "job")
            .expect_err("more than 50 MiB of workflow outputs must fail closed");
        assert_eq!(error.code, "workflow_outputs_too_large");
    }

    #[tokio::test]
    async fn rejects_secret_job_output_context_before_any_shell_runs() {
        let plan = plan_workflow(
            r#"
jobs:
  first:
    runs-on: ubuntu-latest
    steps:
      - run: printf started > marker
  unsupported:
    runs-on: ubuntu-latest
    outputs:
      leak: ${{ secrets.VALUE }}
    steps:
      - run: true
"#,
        )
        .expect("workflow should plan");
        let root = TestRoot::create();
        let error = execute_trusted_linux_workflow(&plan, &root.config(2))
            .await
            .expect_err("secret context must fail closed");
        assert_eq!(error.code, "unsupported_context");
        assert!(!contains_file_named(&root.0, "marker"));
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
