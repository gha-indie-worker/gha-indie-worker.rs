//! Trusted Linux `run`-step execution with a deliberately versioned GitHub
//! Actions compatibility surface.
//!
//! This module is not wired to webhook intake. Calling it requires an explicit
//! host-execution capability because GitHub Actions shell interpolation is code
//! execution by design. The production fixed-profile/container boundary remains
//! the default for untrusted repositories.

use std::collections::BTreeMap;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Serialize;
use serde_json::{Map, Value};
use tokio::fs;
use tokio::process::Command;
use tokio::time::timeout;
use uuid::Uuid;

use crate::expression::{
    evaluate_condition as evaluate_typed_condition, evaluate_wrapped_expression, render_template,
    uses_status_function, validate_expression, validate_template, ExpressionContext,
    ExpressionError, StatusContext,
};
use crate::workflow::{PlannedJob, PlannedStep};

pub const LINUX_RUNNER_SCHEMA_VERSION: &str = "gha-indie-worker.linux-runner.v2";

const DEFAULT_PATH: &str = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
const DEFAULT_OUTPUT_LIMIT: usize = 10 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct TrustedLinuxRunnerConfig {
    pub workspace: PathBuf,
    pub allow_host_process_execution: bool,
    pub default_step_timeout: Duration,
    pub maximum_step_timeout: Duration,
    pub maximum_output_bytes: usize,
}

impl TrustedLinuxRunnerConfig {
    #[must_use]
    pub fn new(workspace: impl Into<PathBuf>) -> Self {
        Self {
            workspace: workspace.into(),
            allow_host_process_execution: false,
            default_step_timeout: Duration::from_secs(3600),
            maximum_step_timeout: Duration::from_secs(3600),
            maximum_output_bytes: DEFAULT_OUTPUT_LIMIT,
        }
    }
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum StepOutcome {
    Success,
    Failure,
    Skipped,
    TimedOut,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum StepConclusion {
    Success,
    Failure,
    Skipped,
    TimedOut,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum JobConclusion {
    Success,
    Failure,
    TimedOut,
}

#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxStepResult {
    pub index: usize,
    pub id: Option<String>,
    pub name: Option<String>,
    pub outcome: StepOutcome,
    pub conclusion: StepConclusion,
    pub exit_code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub outputs: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxJobResult {
    pub schema_version: &'static str,
    pub job_id: String,
    pub conclusion: JobConclusion,
    pub steps: Vec<LinuxStepResult>,
}

#[derive(Debug, Clone, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinuxRunnerError {
    pub code: &'static str,
    pub message: String,
}

impl LinuxRunnerError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Display for LinuxRunnerError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for LinuxRunnerError {}

#[derive(Clone, Default)]
struct RuntimeContext {
    environment: BTreeMap<String, String>,
    step_outputs: BTreeMap<String, BTreeMap<String, String>>,
    step_outcomes: BTreeMap<String, StepOutcome>,
    step_conclusions: BTreeMap<String, StepConclusion>,
    path_entries: Vec<String>,
    job_failed: bool,
    job_timed_out: bool,
}

struct TemporaryDirectory(PathBuf);

impl TemporaryDirectory {
    async fn create() -> Result<Self, LinuxRunnerError> {
        let path = std::env::temp_dir().join(format!(
            "gha-indie-linux-runner-{}",
            Uuid::new_v4().simple()
        ));
        fs::create_dir(&path).await.map_err(|error| {
            LinuxRunnerError::new(
                "runner_temp_unavailable",
                format!("failed to create runner temporary directory: {error}"),
            )
        })?;
        Ok(Self(path))
    }
}

impl Drop for TemporaryDirectory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Executes a single concrete planned job using GitHub-compatible Linux shell
/// and file-command semantics for the supported subset.
///
/// # Security
///
/// The caller must set `allow_host_process_execution` explicitly. Workflow
/// `run` source is arbitrary code and must only reach this API after the caller
/// has independently established trust. This function intentionally is not
/// called by webhook or HTTP intake.
///
/// # Errors
///
/// Returns a fail-closed error for missing authorization, non-Linux jobs,
/// action steps, unsupported expressions or shells, unsafe working directories,
/// invalid file commands, and local process failures that prevent a step from
/// starting. A nonzero command exit is represented in the returned job result.
pub async fn execute_trusted_linux_job(
    job: &PlannedJob,
    config: &TrustedLinuxRunnerConfig,
) -> Result<LinuxJobResult, LinuxRunnerError> {
    if !config.allow_host_process_execution {
        return Err(LinuxRunnerError::new(
            "host_execution_not_authorized",
            "trusted Linux execution requires explicit host-process authorization",
        ));
    }
    if config.default_step_timeout.is_zero() || config.maximum_step_timeout.is_zero() {
        return Err(LinuxRunnerError::new(
            "invalid_timeout_policy",
            "step timeout policy must be greater than zero",
        ));
    }
    if config.maximum_output_bytes == 0 {
        return Err(LinuxRunnerError::new(
            "invalid_output_policy",
            "step output limit must be greater than zero",
        ));
    }

    preflight_job(job)?;
    let workspace = fs::canonicalize(&config.workspace).await.map_err(|error| {
        LinuxRunnerError::new(
            "invalid_workspace",
            format!(
                "workspace {} is unavailable: {error}",
                config.workspace.display()
            ),
        )
    })?;
    if !workspace.is_dir() {
        return Err(LinuxRunnerError::new(
            "invalid_workspace",
            format!("workspace {} is not a directory", workspace.display()),
        ));
    }

    let mut runtime = RuntimeContext::default();
    for (key, value) in &job.env {
        validate_environment_name(key)?;
        let value = scalar_to_string(value, "job environment")?;
        let value = resolve_templates(&value, &job.matrix, &runtime)?;
        runtime.environment.insert(key.clone(), value);
    }
    validate_linux_labels(job, &runtime)?;

    let runner_temp = TemporaryDirectory::create().await?;
    let mut results = Vec::with_capacity(job.steps.len());
    for step in &job.steps {
        let step_runtime = build_step_runtime(job, step, &runtime)?;
        let should_run = evaluate_condition(step.condition.as_deref(), &job.matrix, &step_runtime)?;
        if !should_run {
            let result = LinuxStepResult {
                index: step.index,
                id: step.id.clone(),
                name: step.name.clone(),
                outcome: StepOutcome::Skipped,
                conclusion: StepConclusion::Skipped,
                exit_code: None,
                stdout: String::new(),
                stderr: String::new(),
                outputs: BTreeMap::new(),
            };
            record_step_context(&mut runtime, &result);
            results.push(result);
            continue;
        }

        let result = execute_step(
            job,
            step,
            config,
            &workspace,
            &runner_temp.0,
            &step_runtime,
            &mut runtime,
        )
        .await?;
        if matches!(result.conclusion, StepConclusion::Failure) {
            runtime.job_failed = true;
        }
        if matches!(result.conclusion, StepConclusion::TimedOut) {
            runtime.job_failed = true;
            runtime.job_timed_out = true;
        }
        record_step_context(&mut runtime, &result);
        results.push(result);
    }

    let conclusion = if runtime.job_timed_out {
        JobConclusion::TimedOut
    } else if runtime.job_failed {
        JobConclusion::Failure
    } else {
        JobConclusion::Success
    };
    Ok(LinuxJobResult {
        schema_version: LINUX_RUNNER_SCHEMA_VERSION,
        job_id: job.id.clone(),
        conclusion,
        steps: results,
    })
}

fn build_step_runtime(
    job: &PlannedJob,
    step: &PlannedStep,
    runtime: &RuntimeContext,
) -> Result<RuntimeContext, LinuxRunnerError> {
    let mut step_runtime = runtime.clone();
    let context = expression_context(&job.matrix, runtime);
    for (key, value) in &step.env {
        validate_environment_name(key)?;
        let value = scalar_to_string(value, "step environment")?;
        step_runtime.environment.insert(
            key.clone(),
            render_template(&value, &context).map_err(expression_error)?,
        );
    }
    Ok(step_runtime)
}

fn record_step_context(runtime: &mut RuntimeContext, result: &LinuxStepResult) {
    let Some(id) = result.id.as_ref() else {
        return;
    };
    runtime
        .step_outputs
        .insert(id.clone(), result.outputs.clone());
    runtime.step_outcomes.insert(id.clone(), result.outcome);
    runtime
        .step_conclusions
        .insert(id.clone(), result.conclusion);
}

fn expression_context(
    matrix: &BTreeMap<String, Value>,
    runtime: &RuntimeContext,
) -> ExpressionContext {
    let matrix = matrix
        .iter()
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect::<Map<_, _>>();
    let environment = runtime
        .environment
        .iter()
        .map(|(key, value)| (key.clone(), Value::String(value.clone())))
        .collect::<Map<_, _>>();
    let mut steps = Map::new();
    for (id, outcome) in &runtime.step_outcomes {
        let outputs = runtime
            .step_outputs
            .get(id)
            .into_iter()
            .flatten()
            .map(|(key, value)| (key.clone(), Value::String(value.clone())))
            .collect::<Map<_, _>>();
        let conclusion = runtime
            .step_conclusions
            .get(id)
            .copied()
            .unwrap_or(StepConclusion::Skipped);
        steps.insert(
            id.clone(),
            Value::Object(Map::from_iter([
                ("outputs".to_string(), Value::Object(outputs)),
                (
                    "outcome".to_string(),
                    Value::String(step_outcome_name(*outcome).to_string()),
                ),
                (
                    "conclusion".to_string(),
                    Value::String(step_conclusion_name(conclusion).to_string()),
                ),
            ])),
        );
    }
    ExpressionContext::new()
        .with_root("matrix", Value::Object(matrix))
        .with_root("env", Value::Object(environment))
        .with_root("steps", Value::Object(steps))
        .with_status(StatusContext {
            success: !runtime.job_failed && !runtime.job_timed_out,
            failure: runtime.job_failed,
            cancelled: false,
        })
}

const fn step_outcome_name(outcome: StepOutcome) -> &'static str {
    match outcome {
        StepOutcome::Success => "success",
        StepOutcome::Failure => "failure",
        StepOutcome::Skipped => "skipped",
        StepOutcome::TimedOut => "timed_out",
    }
}

const fn step_conclusion_name(conclusion: StepConclusion) -> &'static str {
    match conclusion {
        StepConclusion::Success => "success",
        StepConclusion::Failure => "failure",
        StepConclusion::Skipped => "skipped",
        StepConclusion::TimedOut => "timed_out",
    }
}

fn expression_error(error: ExpressionError) -> LinuxRunnerError {
    LinuxRunnerError::new(error.code, error.message)
}

fn preflight_job(job: &PlannedJob) -> Result<(), LinuxRunnerError> {
    if job.reusable_workflow.is_some() {
        return Err(LinuxRunnerError::new(
            "unsupported_reusable_workflow",
            format!(
                "job {:?} invokes a reusable workflow; the v1 trusted Linux runner executes concrete run-step jobs only",
                job.id
            ),
        ));
    }
    if job.condition.is_some() {
        return Err(LinuxRunnerError::new(
            "unsupported_job_condition",
            format!(
                "job {:?} defines if; job-level conditions require the workflow scheduler",
                job.id
            ),
        ));
    }
    if job.timeout_minutes.is_some() {
        return Err(LinuxRunnerError::new(
            "unsupported_job_timeout",
            format!(
                "job {:?} defines timeout-minutes; v1 bounds individual steps only",
                job.id
            ),
        ));
    }
    if job.continue_on_error.is_some() {
        return Err(LinuxRunnerError::new(
            "unsupported_job_continue_on_error",
            format!(
                "job {:?} defines continue-on-error; v1 supports that field on steps only",
                job.id
            ),
        ));
    }
    for label in &job.runs_on {
        validate_templates(label)?;
    }
    for (key, value) in &job.env {
        validate_environment_name(key)?;
        validate_templates(&scalar_to_string(value, "job environment")?)?;
    }
    for step in &job.steps {
        if step.uses.is_some() {
            return Err(LinuxRunnerError::new(
                "unsupported_action",
                format!(
                    "step {} uses an action; the v1 trusted Linux runner supports run steps only",
                    step.index
                ),
            ));
        }
        let Some(run) = step.run.as_deref().filter(|run| !run.trim().is_empty()) else {
            return Err(LinuxRunnerError::new(
                "missing_run_source",
                format!("step {} has no non-empty run source", step.index),
            ));
        };
        if !step.with.is_empty() {
            return Err(LinuxRunnerError::new(
                "unsupported_run_inputs",
                format!(
                    "run step {} defines with; inputs are valid only for supported action steps",
                    step.index
                ),
            ));
        }
        validate_templates(run)?;
        if step.timeout_minutes == Some(0) {
            return Err(LinuxRunnerError::new(
                "invalid_step_timeout",
                format!("step {} timeout-minutes must exceed zero", step.index),
            ));
        }
        if let Some(shell) = step.shell.as_deref() {
            validate_shell_template(shell)?;
            validate_templates(shell)?;
        }
        if let Some(condition) = step.condition.as_deref() {
            validate_condition(condition)?;
        }
        if let Some(working_directory) = step.working_directory.as_deref() {
            validate_templates(working_directory)?;
        }
        for (key, value) in &step.env {
            validate_environment_name(key)?;
            validate_templates(&scalar_to_string(value, "step environment")?)?;
        }
        match step.continue_on_error.as_ref() {
            None | Some(Value::Bool(_)) => {}
            Some(Value::String(value)) => validate_templates(value)?,
            Some(_) => {
                return Err(LinuxRunnerError::new(
                    "invalid_continue_on_error",
                    format!(
                        "step {} continue-on-error must be a boolean or supported expression",
                        step.index
                    ),
                ));
            }
        }
    }
    Ok(())
}

fn validate_linux_labels(
    job: &PlannedJob,
    runtime: &RuntimeContext,
) -> Result<(), LinuxRunnerError> {
    let mut labels = Vec::with_capacity(job.runs_on.len());
    for label in &job.runs_on {
        labels.push(resolve_templates(label, &job.matrix, runtime)?);
    }
    let linux = labels.iter().any(|label| {
        let label = label.trim().to_ascii_lowercase();
        label == "linux" || label == "ubuntu-latest" || label.starts_with("ubuntu-")
    });
    let foreign = labels.iter().any(|label| {
        let label = label.to_ascii_lowercase();
        label.contains("windows") || label.contains("macos")
    });
    if !linux || foreign {
        return Err(LinuxRunnerError::new(
            "unsupported_runner",
            format!(
                "job {:?} does not resolve to an unambiguous Linux runner: {labels:?}",
                job.id
            ),
        ));
    }
    Ok(())
}

async fn execute_step(
    job: &PlannedJob,
    step: &PlannedStep,
    config: &TrustedLinuxRunnerConfig,
    workspace: &Path,
    runner_temp: &Path,
    step_runtime: &RuntimeContext,
    runtime: &mut RuntimeContext,
) -> Result<LinuxStepResult, LinuxRunnerError> {
    let script_source = resolve_templates(
        step.run.as_deref().expect("preflight requires run source"),
        &job.matrix,
        step_runtime,
    )?;
    let shell = step
        .shell
        .as_deref()
        .map(|value| resolve_templates(value, &job.matrix, step_runtime))
        .transpose()?;
    let working_directory = resolve_working_directory(
        workspace,
        step.working_directory.as_deref(),
        &job.matrix,
        step_runtime,
    )
    .await?;

    let prefix = format!("step-{}-{}", step.index, Uuid::new_v4().simple());
    let script_path = runner_temp.join(format!("{prefix}.sh"));
    let env_path = runner_temp.join(format!("{prefix}-env"));
    let output_path = runner_temp.join(format!("{prefix}-output"));
    let path_path = runner_temp.join(format!("{prefix}-path"));
    let summary_path = runner_temp.join(format!("{prefix}-summary"));
    fs::write(&script_path, script_source)
        .await
        .map_err(|error| LinuxRunnerError::new("script_write_failed", error.to_string()))?;
    for path in [&env_path, &output_path, &path_path, &summary_path] {
        fs::write(path, b"").await.map_err(|error| {
            LinuxRunnerError::new("file_command_setup_failed", error.to_string())
        })?;
    }

    let (program, arguments) = shell_command(shell.as_deref(), &script_path)?;
    let mut environment = step_runtime.environment.clone();

    let inherited_path = std::env::var("PATH").unwrap_or_else(|_| DEFAULT_PATH.to_string());
    let mut effective_path = runtime
        .path_entries
        .iter()
        .rev()
        .cloned()
        .collect::<Vec<_>>();
    effective_path.push(inherited_path);
    environment.insert("PATH".to_string(), effective_path.join(":"));
    environment.insert("CI".to_string(), "true".to_string());
    environment.insert(
        "GITHUB_WORKSPACE".to_string(),
        workspace.to_string_lossy().to_string(),
    );
    environment.insert(
        "GITHUB_ENV".to_string(),
        env_path.to_string_lossy().to_string(),
    );
    environment.insert(
        "GITHUB_OUTPUT".to_string(),
        output_path.to_string_lossy().to_string(),
    );
    environment.insert(
        "GITHUB_PATH".to_string(),
        path_path.to_string_lossy().to_string(),
    );
    environment.insert(
        "GITHUB_STEP_SUMMARY".to_string(),
        summary_path.to_string_lossy().to_string(),
    );
    environment.insert("RUNNER_OS".to_string(), "Linux".to_string());
    environment.insert(
        "RUNNER_ARCH".to_string(),
        match std::env::consts::ARCH {
            "x86_64" => "X64",
            "aarch64" => "ARM64",
            _ => "UNKNOWN",
        }
        .to_string(),
    );
    environment
        .entry("HOME".to_string())
        .or_insert_with(|| workspace.to_string_lossy().to_string());

    let mut command = Command::new(program);
    command
        .args(arguments)
        .current_dir(&working_directory)
        .env_clear()
        .envs(environment)
        .kill_on_drop(true);

    let configured_timeout = step
        .timeout_minutes
        .map(|minutes| Duration::from_secs(minutes.saturating_mul(60)))
        .unwrap_or(config.default_step_timeout)
        .min(config.maximum_step_timeout);
    let execution = timeout(configured_timeout, command.output()).await;

    let (outcome, exit_code, stdout, stderr) = match execution {
        Err(_) => (
            StepOutcome::TimedOut,
            None,
            String::new(),
            format!(
                "step exceeded {} second timeout",
                configured_timeout.as_secs()
            ),
        ),
        Ok(Err(error)) => {
            return Err(LinuxRunnerError::new(
                "step_start_failed",
                format!("step {} could not start: {error}", step.index),
            ));
        }
        Ok(Ok(output)) => {
            let total = output.stdout.len().saturating_add(output.stderr.len());
            if total > config.maximum_output_bytes {
                return Err(LinuxRunnerError::new(
                    "step_output_too_large",
                    format!(
                        "step {} emitted {total} bytes; maximum is {}",
                        step.index, config.maximum_output_bytes
                    ),
                ));
            }
            (
                if output.status.success() {
                    StepOutcome::Success
                } else {
                    StepOutcome::Failure
                },
                output.status.code(),
                String::from_utf8_lossy(&output.stdout).into_owned(),
                String::from_utf8_lossy(&output.stderr).into_owned(),
            )
        }
    };

    let environment_updates = parse_file_commands(&env_path, "environment").await?;
    for (key, value) in environment_updates {
        validate_environment_name(&key)?;
        runtime.environment.insert(key, value);
    }
    let outputs = parse_file_commands(&output_path, "output").await?;
    let path_updates = fs::read_to_string(&path_path)
        .await
        .map_err(|error| LinuxRunnerError::new("path_command_read_failed", error.to_string()))?;
    runtime.path_entries.extend(
        path_updates
            .lines()
            .map(str::trim)
            .filter(|entry| !entry.is_empty())
            .map(str::to_string),
    );

    let continue_on_error =
        evaluate_continue_on_error(step.continue_on_error.as_ref(), job, runtime)?;
    let conclusion = match outcome {
        StepOutcome::Success => StepConclusion::Success,
        StepOutcome::Failure if continue_on_error => StepConclusion::Success,
        StepOutcome::Failure => StepConclusion::Failure,
        StepOutcome::TimedOut if continue_on_error => StepConclusion::Success,
        StepOutcome::TimedOut => StepConclusion::TimedOut,
        StepOutcome::Skipped => StepConclusion::Skipped,
    };

    Ok(LinuxStepResult {
        index: step.index,
        id: step.id.clone(),
        name: step.name.clone(),
        outcome,
        conclusion,
        exit_code,
        stdout,
        stderr,
        outputs,
    })
}

fn shell_command(
    shell: Option<&str>,
    script_path: &Path,
) -> Result<(PathBuf, Vec<String>), LinuxRunnerError> {
    let script = script_path.to_string_lossy().to_string();
    match shell.map(str::trim) {
        None | Some("") => Ok((find_shell("bash")?, vec!["-e".to_string(), script])),
        Some("bash") => Ok((
            find_shell("bash")?,
            vec![
                "--noprofile".to_string(),
                "--norc".to_string(),
                "-eo".to_string(),
                "pipefail".to_string(),
                script,
            ],
        )),
        Some("sh") => Ok((find_shell("sh")?, vec!["-e".to_string(), script])),
        Some(other) => Err(LinuxRunnerError::new(
            "unsupported_shell",
            format!("shell {other:?} is outside the v1 Linux parity subset"),
        )),
    }
}

fn validate_shell_template(shell: &str) -> Result<(), LinuxRunnerError> {
    let trimmed = shell.trim();
    if matches!(trimmed, "bash" | "sh") || trimmed.contains("${{") {
        Ok(())
    } else {
        Err(LinuxRunnerError::new(
            "unsupported_shell",
            format!("shell {trimmed:?} is outside the v1 Linux parity subset"),
        ))
    }
}

fn find_shell(name: &str) -> Result<PathBuf, LinuxRunnerError> {
    let candidates: &[&str] = match name {
        "bash" => &["/usr/bin/bash", "/bin/bash", "/opt/homebrew/bin/bash"],
        "sh" => &["/usr/bin/sh", "/bin/sh"],
        _ => &[],
    };
    candidates
        .iter()
        .map(PathBuf::from)
        .find(|path| path.is_file())
        .ok_or_else(|| {
            LinuxRunnerError::new(
                "shell_unavailable",
                format!("required shell {name:?} is not installed"),
            )
        })
}

async fn resolve_working_directory(
    workspace: &Path,
    requested: Option<&str>,
    matrix: &BTreeMap<String, Value>,
    runtime: &RuntimeContext,
) -> Result<PathBuf, LinuxRunnerError> {
    let requested = requested.unwrap_or(".");
    let resolved = resolve_templates(requested, matrix, runtime)?;
    let relative = Path::new(&resolved);
    if relative.is_absolute()
        || relative
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        return Err(LinuxRunnerError::new(
            "unsafe_working_directory",
            format!("working-directory {resolved:?} must remain below the workspace"),
        ));
    }
    let candidate = fs::canonicalize(workspace.join(relative))
        .await
        .map_err(|error| {
            LinuxRunnerError::new(
                "invalid_working_directory",
                format!("working-directory {resolved:?} is unavailable: {error}"),
            )
        })?;
    if !candidate.starts_with(workspace) || !candidate.is_dir() {
        return Err(LinuxRunnerError::new(
            "unsafe_working_directory",
            format!("working-directory {resolved:?} escapes the workspace"),
        ));
    }
    Ok(candidate)
}

fn evaluate_condition(
    condition: Option<&str>,
    matrix: &BTreeMap<String, Value>,
    runtime: &RuntimeContext,
) -> Result<bool, LinuxRunnerError> {
    let Some(condition) = condition else {
        return Ok(!runtime.job_failed && !runtime.job_timed_out);
    };
    let condition = unwrap_expression(condition);
    let has_explicit_status = uses_status_function(condition).map_err(expression_error)?;
    let context = expression_context(matrix, runtime);
    let matches = evaluate_typed_condition(condition, &context).map_err(expression_error)?;
    Ok(matches && (has_explicit_status || (!runtime.job_failed && !runtime.job_timed_out)))
}

fn validate_condition(condition: &str) -> Result<(), LinuxRunnerError> {
    let condition = unwrap_expression(condition);
    validate_expression(condition, &["matrix", "env", "steps"], true).map_err(expression_error)
}

fn unwrap_expression(value: &str) -> &str {
    let value = value.trim();
    value
        .strip_prefix("${{")
        .and_then(|inner| inner.strip_suffix("}}"))
        .map(str::trim)
        .unwrap_or(value)
}

fn evaluate_continue_on_error(
    value: Option<&Value>,
    job: &PlannedJob,
    runtime: &RuntimeContext,
) -> Result<bool, LinuxRunnerError> {
    let Some(value) = value else {
        return Ok(false);
    };
    match value {
        Value::Bool(value) => Ok(*value),
        Value::String(value) => {
            let context = expression_context(&job.matrix, runtime);
            let evaluated = evaluate_wrapped_expression(value, &context)
                .map_err(expression_error)?
                .unwrap_or(Value::String(
                    render_template(value, &context).map_err(expression_error)?,
                ));
            evaluated.as_bool().ok_or_else(|| {
                LinuxRunnerError::new(
                    "invalid_continue_on_error",
                    format!("continue-on-error resolved to {evaluated}, not a boolean"),
                )
            })
        }
        _ => Err(LinuxRunnerError::new(
            "invalid_continue_on_error",
            "continue-on-error must be a boolean or a supported scalar expression",
        )),
    }
}

fn resolve_templates(
    source: &str,
    matrix: &BTreeMap<String, Value>,
    runtime: &RuntimeContext,
) -> Result<String, LinuxRunnerError> {
    render_template(source, &expression_context(matrix, runtime)).map_err(expression_error)
}

fn validate_templates(source: &str) -> Result<(), LinuxRunnerError> {
    validate_template(source, &["matrix", "env", "steps"], false).map_err(expression_error)
}

fn scalar_to_string(value: &Value, context: &str) -> Result<String, LinuxRunnerError> {
    match value {
        Value::Null => Ok(String::new()),
        Value::Bool(value) => Ok(value.to_string()),
        Value::Number(value) => Ok(value.to_string()),
        Value::String(value) => Ok(value.clone()),
        Value::Array(_) | Value::Object(_) => Err(LinuxRunnerError::new(
            "non_scalar_value",
            format!("{context} values must be scalar"),
        )),
    }
}

fn validate_environment_name(name: &str) -> Result<(), LinuxRunnerError> {
    let valid = !name.is_empty()
        && name.bytes().enumerate().all(|(index, byte)| {
            byte == b'_' || byte.is_ascii_alphanumeric() && (index > 0 || !byte.is_ascii_digit())
        });
    if !valid {
        return Err(LinuxRunnerError::new(
            "invalid_environment_name",
            format!("environment variable name {name:?} is invalid"),
        ));
    }
    if name.starts_with("GITHUB_") || name.starts_with("RUNNER_") {
        return Err(LinuxRunnerError::new(
            "protected_environment_name",
            format!("environment variable {name:?} is runner-controlled"),
        ));
    }
    Ok(())
}

async fn parse_file_commands(
    path: &Path,
    kind: &str,
) -> Result<BTreeMap<String, String>, LinuxRunnerError> {
    let contents = fs::read_to_string(path).await.map_err(|error| {
        LinuxRunnerError::new(
            "file_command_read_failed",
            format!("failed to read {kind} command file: {error}"),
        )
    })?;
    let lines = contents.lines().collect::<Vec<_>>();
    let mut parsed = BTreeMap::new();
    let mut index = 0;
    while index < lines.len() {
        let line = lines[index];
        if line.trim().is_empty() {
            index += 1;
            continue;
        }
        if let Some((name, delimiter)) = line.split_once("<<") {
            if name.is_empty() || delimiter.is_empty() {
                return Err(invalid_file_command(kind, line));
            }
            index += 1;
            let start = index;
            while index < lines.len() && lines[index] != delimiter {
                index += 1;
            }
            if index == lines.len() {
                return Err(invalid_file_command(kind, line));
            }
            parsed.insert(name.to_string(), lines[start..index].join("\n"));
            index += 1;
            continue;
        }
        let Some((name, value)) = line.split_once('=') else {
            return Err(invalid_file_command(kind, line));
        };
        if name.is_empty() {
            return Err(invalid_file_command(kind, line));
        }
        parsed.insert(name.to_string(), value.to_string());
        index += 1;
    }
    Ok(parsed)
}

fn invalid_file_command(kind: &str, line: &str) -> LinuxRunnerError {
    LinuxRunnerError::new(
        "invalid_file_command",
        format!("invalid {kind} file command line {line:?}"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::workflow::plan_workflow;

    struct TestWorkspace(PathBuf);

    impl TestWorkspace {
        fn create() -> Self {
            let path = std::env::temp_dir().join(format!(
                "gha-indie-linux-runner-test-{}",
                Uuid::new_v4().simple()
            ));
            std::fs::create_dir(&path).expect("create test workspace");
            Self(path)
        }

        fn config(&self) -> TrustedLinuxRunnerConfig {
            let mut config = TrustedLinuxRunnerConfig::new(&self.0);
            config.allow_host_process_execution = true;
            config.default_step_timeout = Duration::from_secs(5);
            config.maximum_step_timeout = Duration::from_secs(5);
            config
        }
    }

    impl Drop for TestWorkspace {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn one_job(yaml: &str) -> PlannedJob {
        let plan = plan_workflow(yaml).expect("workflow should plan");
        assert_eq!(plan.jobs.len(), 1);
        plan.jobs.into_iter().next().expect("one planned job")
    }

    #[tokio::test]
    async fn requires_explicit_trusted_execution_authority() {
        let workspace = TestWorkspace::create();
        let job = one_job(
            "jobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo safe\n",
        );
        let error = execute_trusted_linux_job(&job, &TrustedLinuxRunnerConfig::new(&workspace.0))
            .await
            .expect_err("host execution must fail closed");
        assert_eq!(error.code, "host_execution_not_authorized");
    }

    #[tokio::test]
    async fn preflights_unsupported_actions_before_running_any_step() {
        let workspace = TestWorkspace::create();
        let marker = workspace.0.join("must-not-exist");
        let job = one_job(
            "jobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: touch must-not-exist\n      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567\n",
        );
        let error = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect_err("action steps are not in v1");
        assert_eq!(error.code, "unsupported_action");
        assert!(!marker.exists());
    }

    #[tokio::test]
    async fn preflights_unsupported_secret_context_before_running_any_step() {
        let workspace = TestWorkspace::create();
        let marker = workspace.0.join("must-not-exist");
        let job = one_job(
            r#"
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: touch must-not-exist
      - run: echo '${{ secrets.TOKEN }}'
"#,
        );
        let error = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect_err("secret context must fail closed before execution");
        assert_eq!(error.code, "unsupported_context");
        assert!(!marker.exists());
    }

    #[tokio::test]
    async fn preflights_unsupported_expression_functions_before_running_any_step() {
        let workspace = TestWorkspace::create();
        let marker = workspace.0.join("must-not-exist");
        let job = one_job(
            r#"
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: touch must-not-exist
      - if: hashFiles('**/Cargo.lock') != ''
        run: echo unreachable
"#,
        );
        let error = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect_err("unsupported functions must fail closed before execution");
        assert_eq!(error.code, "unsupported_function");
        assert!(!marker.exists());
    }

    #[tokio::test]
    async fn preflights_unsupported_job_semantics_and_run_inputs() {
        let workspace = TestWorkspace::create();
        let cases = [
            (
                "jobs:\n  test:\n    if: 'false'\n    runs-on: ubuntu-latest\n    steps:\n      - run: touch must-not-exist\n",
                "unsupported_job_condition",
            ),
            (
                "jobs:\n  test:\n    timeout-minutes: 1\n    runs-on: ubuntu-latest\n    steps:\n      - run: touch must-not-exist\n",
                "unsupported_job_timeout",
            ),
            (
                "jobs:\n  test:\n    continue-on-error: true\n    runs-on: ubuntu-latest\n    steps:\n      - run: touch must-not-exist\n",
                "unsupported_job_continue_on_error",
            ),
            (
                "jobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: touch must-not-exist\n        with: { ignored: value }\n",
                "unsupported_run_inputs",
            ),
        ];

        for (yaml, expected_code) in cases {
            let job = one_job(yaml);
            let error = execute_trusted_linux_job(&job, &workspace.config())
                .await
                .expect_err("unsupported job semantics must fail closed");
            assert_eq!(error.code, expected_code);
            assert!(!workspace.0.join("must-not-exist").exists());
        }

        let reusable = one_job(
            "jobs:\n  test:\n    uses: owner/repository/.github/workflows/test.yml@0123456789abcdef0123456789abcdef01234567\n",
        );
        let error = execute_trusted_linux_job(&reusable, &workspace.config())
            .await
            .expect_err("reusable workflow execution must fail closed");
        assert_eq!(error.code, "unsupported_reusable_workflow");
    }

    #[tokio::test]
    async fn matches_step_env_output_and_continue_on_error_semantics() {
        let workspace = TestWorkspace::create();
        let job = one_job(
            r#"
env:
  WORKFLOW_ONLY: workflow
defaults:
  run:
    shell: sh
jobs:
  parity:
    runs-on: ubuntu-latest
    env:
      LEVEL: job
    defaults:
      run:
        working-directory: work
    steps:
      - working-directory: .
        run: mkdir -p work
      - id: producer
        env:
          LEVEL: step
        run: |
          printf '%s:%s:%s:%s' "$LEVEL" '${{ env.LEVEL }}' "$WORKFLOW_ONLY" '${{ env.WORKFLOW_ONLY }}' > result.txt
          echo 'PERSISTED=from-env' >> "$GITHUB_ENV"
          echo 'value=42' >> "$GITHUB_OUTPUT"
      - id: tolerated
        continue-on-error: true
        shell: bash
        run: false | true
      - id: after
        if: success()
        run: printf '|%s|${{ steps.producer.outputs.value }}' "$PERSISTED" >> result.txt
      - id: must_skip
        if: failure()
        run: echo wrong >> result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect("trusted job should execute");

        assert_eq!(result.conclusion, JobConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("work/result.txt")).unwrap(),
            "step:step:workflow:workflow|from-env|42"
        );
        assert_eq!(result.steps[2].outcome, StepOutcome::Failure);
        assert_eq!(result.steps[2].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[3].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[4].conclusion, StepConclusion::Skipped);
        assert_eq!(
            result.steps[1].outputs.get("value").map(String::as_str),
            Some("42")
        );
    }

    #[tokio::test]
    async fn evaluates_typed_conditions_functions_and_step_result_contexts() {
        let workspace = TestWorkspace::create();
        let job = one_job(
            r#"
env:
  CONTINUE: "true"
jobs:
  parity:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        enabled: [true]
        word: [Alpha]
    steps:
      - id: producer
        run: |
          echo 'count=7' >> "$GITHUB_OUTPUT"
          echo 'word=HeLLo' >> "$GITHUB_OUTPUT"
      - id: tolerated
        if: >-
          success() && matrix.enabled &&
          env.CONTINUE == 'TRUE' &&
          fromJSON(steps.producer.outputs.count) >= 7 &&
          contains(fromJSON('["push","pull_request"]'), 'PUSH') &&
          startsWith(matrix.word, 'al') &&
          endsWith(steps.producer.outputs.word, 'LO') &&
          matrix.missing == ''
        continue-on-error: ${{ fromJSON(env.CONTINUE) }}
        run: 'false'
      - id: after
        if: >-
          success() &&
          steps.tolerated.outcome == 'failure' &&
          steps.tolerated.conclusion == 'success'
        run: |
          printf '%s' '${{ format('{0}:{1}:{2}', join(fromJSON('["a","b"]'), '-'), true && 'yes' || 'no', fromJSON('[0,7]')[1]) }}' > result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect("typed expression job should execute");

        assert_eq!(result.schema_version, "gha-indie-worker.linux-runner.v2");
        assert_eq!(result.conclusion, JobConclusion::Success);
        assert_eq!(result.steps[1].outcome, StepOutcome::Failure);
        assert_eq!(result.steps[1].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[2].conclusion, StepConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("result.txt")).unwrap(),
            "a-b:yes:7"
        );
    }

    #[tokio::test]
    async fn applies_implicit_success_to_conditions_without_status_functions() {
        let workspace = TestWorkspace::create();
        let job = one_job(
            r#"
env:
  FLAG: yes
jobs:
  parity:
    runs-on: ubuntu-latest
    steps:
      - id: fail
        run: 'false'
      - id: literal_true
        if: 'true'
        run: echo wrong > result.txt
      - id: context_true
        if: env.FLAG == 'yes'
        run: echo wrong > result.txt
      - id: recovery
        if: failure() && env.FLAG == 'YES'
        run: echo recovered > result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect("status-aware condition job should execute");

        assert_eq!(result.conclusion, JobConclusion::Failure);
        assert_eq!(result.steps[1].conclusion, StepConclusion::Skipped);
        assert_eq!(result.steps[2].conclusion, StepConclusion::Skipped);
        assert_eq!(result.steps[3].conclusion, StepConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("result.txt")).unwrap(),
            "recovered\n"
        );
    }

    #[tokio::test]
    async fn distinguishes_default_and_explicit_bash_and_runs_failure_cleanup() {
        let workspace = TestWorkspace::create();
        let job = one_job(
            r#"
jobs:
  parity:
    runs-on: ubuntu-latest
    steps:
      - id: default_shell
        run: false | true
      - id: explicit_bash
        shell: bash
        run: false | true
      - id: default_after_failure
        run: echo wrong > result.txt
      - id: failure_cleanup
        if: failure()
        run: echo failure > result.txt
      - id: not_cancelled_cleanup
        if: ${{ !cancelled() }}
        run: echo not-cancelled >> result.txt
      - id: always_cleanup
        if: always()
        run: echo always >> result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect("trusted job should execute");

        assert_eq!(result.conclusion, JobConclusion::Failure);
        assert_eq!(result.steps[0].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[1].conclusion, StepConclusion::Failure);
        assert_eq!(result.steps[2].conclusion, StepConclusion::Skipped);
        assert_eq!(result.steps[3].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[4].conclusion, StepConclusion::Success);
        assert_eq!(result.steps[5].conclusion, StepConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("result.txt")).unwrap(),
            "failure\nnot-cancelled\nalways\n"
        );
    }

    #[tokio::test]
    async fn resolves_matrix_runner_and_working_directory() {
        let workspace = TestWorkspace::create();
        std::fs::create_dir(workspace.0.join("nested")).unwrap();
        let job = one_job(
            r#"
jobs:
  parity:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest]
        value: [alpha]
    steps:
      - working-directory: nested
        run: printf '${{ matrix.value }}' > result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &workspace.config())
            .await
            .expect("matrix job should execute");
        assert_eq!(result.conclusion, JobConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("nested/result.txt")).unwrap(),
            "alpha"
        );
    }

    #[tokio::test]
    async fn enforces_step_timeout_and_always_cleanup() {
        let workspace = TestWorkspace::create();
        let mut config = workspace.config();
        config.default_step_timeout = Duration::from_millis(50);
        config.maximum_step_timeout = Duration::from_millis(50);
        let job = one_job(
            r#"
jobs:
  parity:
    runs-on: ubuntu-latest
    steps:
      - run: sleep 2
      - if: always()
        run: echo cleaned > result.txt
"#,
        );
        let result = execute_trusted_linux_job(&job, &config)
            .await
            .expect("timeout is a job result, not an engine failure");
        assert_eq!(result.conclusion, JobConclusion::TimedOut);
        assert_eq!(result.steps[0].conclusion, StepConclusion::TimedOut);
        assert_eq!(result.steps[1].conclusion, StepConclusion::Success);
        assert_eq!(
            std::fs::read_to_string(workspace.0.join("result.txt")).unwrap(),
            "cleaned\n"
        );
    }
}
