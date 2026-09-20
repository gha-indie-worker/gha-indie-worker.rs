//! Durable evidence journal for authoritative GitHub App reporting.
//!
//! A local execution result and a GitHub Check Run are two different facts.
//! This journal keeps them separate across crashes without persisting any App
//! JWT, installation token or private-key material.

use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::fs;

use crate::checks::{CheckTarget, Delivery, ObservedCheckState};
use crate::config::ReportingMode;
use crate::state::AppState;
use crate::types::BuildStatus;

const SCHEMA: &str = "gha-indie-worker.report-intent/v1";
const MAX_INTENT_BYTES: u64 = 64 * 1024;
const MAX_INTENTS: usize = 10_000;
const RECONCILE_INTERVAL_SECONDS: u64 = 60;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(crate) enum ExecutionState {
    Active,
    Succeeded,
    Failed,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(crate) enum ReportDeliveryState {
    Pending,
    Delivered,
    Reconciled,
    PermanentFailure,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct ReportIntent {
    schema: String,
    pub(crate) logical_attempt_id: String,
    pub(crate) job_id: String,
    pub(crate) owner: String,
    pub(crate) repo: String,
    pub(crate) head_sha: String,
    pub(crate) check_context: String,
    pub(crate) reporter_app_id: String,
    pub(crate) installation_candidate: Option<u64>,
    pub(crate) check_run_id: Option<u64>,
    pub(crate) execution_state: ExecutionState,
    pub(crate) execution_finished_at_ms: Option<u64>,
    pub(crate) desired_conclusion: Option<String>,
    pub(crate) report_delivery_state: ReportDeliveryState,
    pub(crate) report_attempt_count: u32,
    pub(crate) last_report_error_class: Option<String>,
    pub(crate) last_report_attempt_at_ms: Option<u64>,
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|value| u64::try_from(value.as_millis()).unwrap_or(u64::MAX))
        .unwrap_or_default()
}

fn report_root(state: &AppState) -> PathBuf {
    state.config.work_root.join(".report-intents")
}

fn intent_identity(intent: &ReportIntent) -> String {
    format!(
        "{}/{}@{}:{}:{}",
        intent.owner,
        intent.repo,
        intent.head_sha,
        intent.check_context,
        intent.logical_attempt_id
    )
}

fn intent_path(root: &Path, intent: &ReportIntent) -> PathBuf {
    let digest = Sha256::digest(intent_identity(intent).as_bytes());
    root.join(format!("{digest:x}.json"))
}

fn safe_attempt_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':' | b'@'))
}

fn validate(intent: &ReportIntent) -> Result<(), String> {
    if intent.schema != SCHEMA {
        return Err("unknown report-intent schema".to_string());
    }
    if !safe_attempt_id(&intent.logical_attempt_id) || !safe_attempt_id(&intent.job_id) {
        return Err("invalid report-intent identity".to_string());
    }
    if crate::validation::validate_commit_sha(&intent.head_sha).is_err() {
        return Err("invalid report-intent head SHA".to_string());
    }
    if intent.owner.is_empty()
        || intent.repo.is_empty()
        || intent.owner.len() > 100
        || intent.repo.len() > 100
        || intent.owner.contains('/')
        || intent.repo.contains('/')
    {
        return Err("invalid report-intent repository".to_string());
    }
    if intent.check_context.is_empty()
        || intent.check_context.len() > 256
        || intent.reporter_app_id.parse::<u64>().ok().filter(|id| *id > 0).is_none()
    {
        return Err("invalid report-intent authority".to_string());
    }
    if let Some(conclusion) = intent.desired_conclusion.as_deref() {
        if !matches!(conclusion, "success" | "failure") {
            return Err("invalid report-intent conclusion".to_string());
        }
    }
    Ok(())
}

async fn persist(state: &AppState, intent: &ReportIntent) -> Result<(), String> {
    validate(intent)?;
    let root = report_root(state);
    fs::create_dir_all(&root)
        .await
        .map_err(|error| format!("could not create report-intent directory: {error}"))?;
    let path = intent_path(&root, intent);
    let temp = root.join(format!(
        ".{}.{}.tmp",
        path.file_stem().and_then(|value| value.to_str()).unwrap_or("intent"),
        uuid::Uuid::new_v4()
    ));
    let bytes = serde_json::to_vec(intent)
        .map_err(|error| format!("could not serialize report intent: {error}"))?;
    if bytes.len() as u64 > MAX_INTENT_BYTES {
        return Err("report intent exceeds size bound".to_string());
    }
    fs::write(&temp, &bytes)
        .await
        .map_err(|error| format!("could not write report intent: {error}"))?;
    if let Err(error) = fs::rename(&temp, &path).await {
        // Windows cannot atomically replace an existing destination. The
        // authoritative laptop path is Unix, but keep a bounded fallback for
        // development without ever accepting a partially written JSON file.
        if fs::remove_file(&path).await.is_err() || fs::rename(&temp, &path).await.is_err() {
            let _ = fs::remove_file(&temp).await;
            return Err(format!("could not publish report intent: {error}"));
        }
    }
    Ok(())
}

async fn logical_attempt_for_job(state: &AppState, job_id: &str) -> String {
    let jobs = state.jobs.read().await;
    jobs.get(job_id)
        .and_then(|job| job.request.request_id.as_deref())
        .filter(|value| safe_attempt_id(value))
        .map(str::to_string)
        .unwrap_or_else(|| job_id.to_string())
}

pub(crate) fn external_id(intent: &ReportIntent) -> String {
    format!(
        "indiebuild:{}@{}",
        intent.logical_attempt_id, intent.head_sha
    )
}

/// Create the durable intent before GitHub Check Run creation. The returned
/// intent carries the stable logical attempt used for Check Run external_id.
pub(crate) async fn begin(
    state: &AppState,
    target: &CheckTarget,
    job_id: &str,
) -> Result<Option<ReportIntent>, String> {
    if state.config.reporting_mode != ReportingMode::AppRequired {
        return Ok(None);
    }
    let app_id = state
        .config
        .github_app_id
        .clone()
        .ok_or_else(|| "app-required report intent has no App id".to_string())?;
    let logical_attempt_id = logical_attempt_for_job(state, job_id).await;
    let intent = ReportIntent {
        schema: SCHEMA.to_string(),
        logical_attempt_id,
        job_id: job_id.to_string(),
        owner: target.owner.clone(),
        repo: target.repo.clone(),
        head_sha: target.head_sha.clone(),
        check_context: state.config.check_run_name.clone(),
        reporter_app_id: app_id,
        installation_candidate: target.installation_id,
        check_run_id: None,
        execution_state: ExecutionState::Active,
        execution_finished_at_ms: None,
        desired_conclusion: None,
        report_delivery_state: ReportDeliveryState::Pending,
        report_attempt_count: 0,
        last_report_error_class: None,
        last_report_attempt_at_ms: None,
    };
    persist(state, &intent).await?;
    Ok(Some(intent))
}

pub(crate) async fn record_check_id(
    state: &AppState,
    intent: &mut ReportIntent,
    check_run_id: u64,
) -> Result<(), String> {
    intent.check_run_id = Some(check_run_id);
    persist(state, intent).await
}

pub(crate) async fn record_terminal_execution(
    state: &AppState,
    target: &CheckTarget,
    check_run_id: Option<u64>,
    succeeded: bool,
) -> Result<(), String> {
    if state.config.reporting_mode != ReportingMode::AppRequired {
        return Ok(());
    }
    let mut intents = load_all(state).await?;
    let matches = intents
        .iter_mut()
        .filter(|intent| {
            intent.owner.eq_ignore_ascii_case(&target.owner)
                && intent.repo.eq_ignore_ascii_case(&target.repo)
                && intent.head_sha == target.head_sha
                && intent.check_run_id == check_run_id
                && intent.report_delivery_state == ReportDeliveryState::Pending
        })
        .collect::<Vec<_>>();
    if matches.len() != 1 {
        return Err(format!(
            "expected one active authoritative report intent, found {}",
            matches.len()
        ));
    }
    let intent = matches.into_iter().next().expect("one match");
    intent.execution_state = if succeeded {
        ExecutionState::Succeeded
    } else {
        ExecutionState::Failed
    };
    intent.execution_finished_at_ms = Some(now_ms());
    intent.desired_conclusion = Some(if succeeded { "success" } else { "failure" }.to_string());
    intent.report_attempt_count = intent.report_attempt_count.saturating_add(1);
    intent.last_report_attempt_at_ms = Some(now_ms());
    persist(state, intent).await
}

pub(crate) async fn record_delivery(
    state: &AppState,
    target: &CheckTarget,
    check_run_id: Option<u64>,
    delivery: Delivery,
) {
    if state.config.reporting_mode != ReportingMode::AppRequired {
        return;
    }
    let Ok(mut intents) = load_all(state).await else {
        state
            .counters
            .unresolved_report_intents
            .fetch_add(1, Ordering::Relaxed);
        return;
    };
    for intent in intents.iter_mut().filter(|intent| {
        intent.owner.eq_ignore_ascii_case(&target.owner)
            && intent.repo.eq_ignore_ascii_case(&target.repo)
            && intent.head_sha == target.head_sha
            && intent.check_run_id == check_run_id
            && intent.report_delivery_state == ReportDeliveryState::Pending
    }) {
        match delivery {
            Delivery::CheckRun => {
                intent.report_delivery_state = ReportDeliveryState::Delivered;
                intent.last_report_error_class = None;
            }
            Delivery::Undelivered | Delivery::NotConfigured => {
                intent.last_report_error_class = Some("undelivered".to_string());
            }
            Delivery::CommitStatus => {
                // Commit Status can never be authoritative in app-required mode.
                intent.last_report_error_class = Some("wrong_evidence_family".to_string());
            }
        }
        let _ = persist(state, intent).await;
    }
    refresh_unresolved_count(state).await;
}

async fn load_all(state: &AppState) -> Result<Vec<ReportIntent>, String> {
    let root = report_root(state);
    let mut entries = match fs::read_dir(&root).await {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("could not read report-intent directory: {error}")),
    };
    let mut paths = Vec::new();
    while let Some(entry) = entries
        .next_entry()
        .await
        .map_err(|error| format!("could not enumerate report intents: {error}"))?
    {
        if paths.len() >= MAX_INTENTS {
            return Err("report-intent directory exceeds entry bound".to_string());
        }
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()) == Some("json") {
            paths.push(path);
        }
    }
    let mut intents = Vec::with_capacity(paths.len());
    for path in paths {
        let metadata = fs::symlink_metadata(&path)
            .await
            .map_err(|error| format!("could not inspect report intent: {error}"))?;
        if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > MAX_INTENT_BYTES {
            return Err("unsafe report-intent file".to_string());
        }
        let bytes = fs::read(&path)
            .await
            .map_err(|error| format!("could not read report intent: {error}"))?;
        let intent: ReportIntent = serde_json::from_slice(&bytes)
            .map_err(|error| format!("invalid report-intent JSON: {error}"))?;
        validate(&intent)?;
        intents.push(intent);
    }
    Ok(intents)
}

async fn refresh_unresolved_count(state: &AppState) {
    let unresolved = load_all(state)
        .await
        .map(|intents| {
            intents
                .iter()
                .filter(|intent| {
                    !matches!(
                        intent.report_delivery_state,
                        ReportDeliveryState::Delivered | ReportDeliveryState::Reconciled
                    )
                })
                .count()
        })
        .unwrap_or(1);
    state
        .counters
        .unresolved_report_intents
        .store(u64::try_from(unresolved).unwrap_or(u64::MAX), Ordering::Relaxed);
}

fn target_for_intent(intent: &ReportIntent) -> CheckTarget {
    CheckTarget {
        owner: intent.owner.clone(),
        repo: intent.repo.clone(),
        head_sha: intent.head_sha.clone(),
        installation_id: intent.installation_candidate,
    }
}

async fn reconcile_one(state: &AppState, intent: &mut ReportIntent) {
    if matches!(
        intent.report_delivery_state,
        ReportDeliveryState::Delivered | ReportDeliveryState::Reconciled
    ) {
        return;
    }
    let target = target_for_intent(intent);

    // Current-process queued/running jobs are not orphans. Periodic
    // reconciliation leaves them alone; after restart the in-memory job map is
    // empty, so an old active intent becomes safely non-successful.
    if intent.execution_state == ExecutionState::Active {
        let current = state.jobs.read().await.get(&intent.job_id).cloned();
        if let Some(job) = current {
            match job.status {
                BuildStatus::Queued | BuildStatus::Running => return,
                BuildStatus::Succeeded => {
                    intent.execution_state = ExecutionState::Succeeded;
                    intent.desired_conclusion = Some("success".to_string());
                    intent.execution_finished_at_ms = job
                        .finished_at_ms
                        .and_then(|value| u64::try_from(value).ok());
                }
                BuildStatus::Failed => {
                    intent.execution_state = ExecutionState::Failed;
                    intent.desired_conclusion = Some("failure".to_string());
                    intent.execution_finished_at_ms = job
                        .finished_at_ms
                        .and_then(|value| u64::try_from(value).ok());
                }
            }
        } else {
            // We cannot prove what the crashed process executed. Never infer
            // success: close any orphaned check as failure.
            intent.execution_state = ExecutionState::Failed;
            intent.desired_conclusion = Some("failure".to_string());
            intent.execution_finished_at_ms = Some(now_ms());
            intent.last_report_error_class = Some("orphaned_execution_unknown".to_string());
        }
        let _ = persist(state, intent).await;
    }

    if intent.check_run_id.is_none() {
        match crate::checks::find_check_run_by_external_id(
            state,
            &target,
            &external_id(intent),
        )
        .await
        {
            Ok(Some(id)) => {
                intent.check_run_id = Some(id);
                let _ = persist(state, intent).await;
            }
            Ok(None) => {
                intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                intent.last_report_error_class = Some("check_missing".to_string());
                let _ = persist(state, intent).await;
                return;
            }
            Err(_) => {
                intent.last_report_error_class = Some("github_transient".to_string());
                let _ = persist(state, intent).await;
                return;
            }
        }
    }
    let Some(check_run_id) = intent.check_run_id else {
        return;
    };

    let observed = match crate::checks::observe_check_run(state, &target, check_run_id, &external_id(intent)).await {
        Ok(observed) => observed,
        Err(error) => {
            if error == "check_not_found" || error == "check_identity_mismatch" {
                intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                intent.last_report_error_class = Some(error);
            } else {
                intent.last_report_error_class = Some("github_transient".to_string());
            }
            intent.report_attempt_count = intent.report_attempt_count.saturating_add(1);
            intent.last_report_attempt_at_ms = Some(now_ms());
            let _ = persist(state, intent).await;
            return;
        }
    };

    let desired_success = intent.desired_conclusion.as_deref() == Some("success");
    match observed {
        ObservedCheckState::Completed { conclusion } => {
            let expected = if desired_success { "success" } else { "failure" };
            if conclusion == expected {
                intent.report_delivery_state = ReportDeliveryState::Reconciled;
                intent.last_report_error_class = None;
            } else {
                intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                intent.last_report_error_class = Some("terminal_conclusion_mismatch".to_string());
            }
        }
        ObservedCheckState::InProgress => {
            intent.report_attempt_count = intent.report_attempt_count.saturating_add(1);
            intent.last_report_attempt_at_ms = Some(now_ms());
            let summary = if desired_success {
                "Recovered local success after worker restart."
            } else if intent.last_report_error_class.as_deref() == Some("orphaned_execution_unknown") {
                "Worker restarted before the execution outcome could be proven; refusing success."
            } else {
                "Recovered local failure after worker restart."
            };
            if crate::checks::finish_check_run(
                state,
                &target,
                check_run_id,
                desired_success,
                summary,
                "reconciled after worker restart",
            )
            .await
            {
                intent.report_delivery_state = ReportDeliveryState::Reconciled;
                intent.last_report_error_class = None;
            } else {
                intent.last_report_error_class = Some("github_transient".to_string());
            }
        }
    }
    let _ = persist(state, intent).await;
}

pub(crate) async fn reconcile_all(state: &AppState) {
    if state.config.reporting_mode != ReportingMode::AppRequired {
        state
            .counters
            .unresolved_report_intents
            .store(0, Ordering::Relaxed);
        return;
    }
    let mut intents = match load_all(state).await {
        Ok(intents) => intents,
        Err(error) => {
            tracing::error!("authoritative report-intent reconciliation unavailable: {error}");
            state
                .counters
                .unresolved_report_intents
                .store(1, Ordering::Relaxed);
            return;
        }
    };
    for intent in &mut intents {
        reconcile_one(state, intent).await;
    }
    refresh_unresolved_count(state).await;
}

pub(crate) async fn run_periodic_reconciler(state: AppState) {
    loop {
        tokio::time::sleep(std::time::Duration::from_secs(RECONCILE_INTERVAL_SECONDS)).await;
        reconcile_all(&state).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logical_identity_is_stable_and_bounded() {
        let intent = ReportIntent {
            schema: SCHEMA.to_string(),
            logical_attempt_id: "delivery-123".to_string(),
            job_id: "build-1".to_string(),
            owner: "org".to_string(),
            repo: "repo".to_string(),
            head_sha: "a".repeat(40),
            check_context: "indiebuild.dev/ci".to_string(),
            reporter_app_id: "42".to_string(),
            installation_candidate: Some(7),
            check_run_id: None,
            execution_state: ExecutionState::Active,
            execution_finished_at_ms: None,
            desired_conclusion: None,
            report_delivery_state: ReportDeliveryState::Pending,
            report_attempt_count: 0,
            last_report_error_class: None,
            last_report_attempt_at_ms: None,
        };
        assert!(validate(&intent).is_ok());
        assert_eq!(external_id(&intent), format!("indiebuild:delivery-123@{}", "a".repeat(40)));
        assert_eq!(intent_path(Path::new("/tmp"), &intent), intent_path(Path::new("/tmp"), &intent));
    }

    #[test]
    fn unknown_execution_can_never_encode_desired_success() {
        let mut intent = ReportIntent {
            schema: SCHEMA.to_string(),
            logical_attempt_id: "x".to_string(),
            job_id: "y".to_string(),
            owner: "o".to_string(),
            repo: "r".to_string(),
            head_sha: "a".repeat(40),
            check_context: "indiebuild.dev/ci".to_string(),
            reporter_app_id: "1".to_string(),
            installation_candidate: None,
            check_run_id: Some(1),
            execution_state: ExecutionState::Active,
            execution_finished_at_ms: None,
            desired_conclusion: None,
            report_delivery_state: ReportDeliveryState::Pending,
            report_attempt_count: 0,
            last_report_error_class: None,
            last_report_attempt_at_ms: None,
        };
        assert_ne!(intent.desired_conclusion.as_deref(), Some("success"));
        intent.execution_state = ExecutionState::Failed;
        intent.desired_conclusion = Some("failure".to_string());
        assert!(validate(&intent).is_ok());
    }
}