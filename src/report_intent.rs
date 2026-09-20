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
use tokio::io::AsyncWriteExt;

use crate::checks::{CheckTarget, Delivery, ObservedCheckState};
use crate::config::ReportingMode;
use crate::state::AppState;
use crate::types::BuildStatus;

const SCHEMA: &str = "gha-indie-worker.report-intent/v1";
const MAX_INTENT_BYTES: u64 = 64 * 1024;
const MAX_INTENTS: usize = 10_000;
const RECONCILE_INTERVAL_SECONDS: u64 = 60;

// The laptop authority is currently one worker process. Serialize every journal
// read/modify/write transaction in that process so webhook redelivery, live job
// completion and the reconciler cannot last-writer-win durable evidence. A
// future multi-replica worker still needs a cross-process lease/database CAS.
static REPORT_JOURNAL_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

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
    pub(crate) last_observed_check_state: Option<String>,
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

fn safe_repo_component(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 100
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
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
    if !safe_repo_component(&intent.owner) || !safe_repo_component(&intent.repo) {
        return Err("invalid report-intent repository".to_string());
    }
    if intent.check_context.is_empty()
        || intent.check_context.len() > 256
        || intent.check_context.chars().any(char::is_control)
        || intent.reporter_app_id.parse::<u64>().ok().filter(|id| *id > 0).is_none()
        || intent.installation_candidate == Some(0)
    {
        return Err("invalid report-intent authority".to_string());
    }
    if let Some(conclusion) = intent.desired_conclusion.as_deref() {
        if !matches!(conclusion, "success" | "failure") {
            return Err("invalid report-intent conclusion".to_string());
        }
    }
    if intent.execution_state == ExecutionState::Active
        && (intent.execution_finished_at_ms.is_some() || intent.desired_conclusion.is_some())
    {
        return Err("active report intent cannot contain terminal execution evidence".to_string());
    }
    if intent.execution_state != ExecutionState::Active
        && (intent.execution_finished_at_ms.is_none() || intent.desired_conclusion.is_none())
    {
        return Err("terminal report intent is missing execution evidence".to_string());
    }
    Ok(())
}

fn bind_check_id(intent: &mut ReportIntent, check_run_id: u64) -> Result<bool, String> {
    if check_run_id == 0 {
        return Err("invalid zero Check Run id".to_string());
    }
    match intent.check_run_id {
        Some(existing) if existing != check_run_id => {
            Err("logical attempt is already bound to another Check Run id".to_string())
        }
        Some(_) => Ok(false),
        None => {
            intent.check_run_id = Some(check_run_id);
            Ok(true)
        }
    }
}

fn apply_terminal_execution(
    intent: &mut ReportIntent,
    succeeded: bool,
    finished_at_ms: u64,
) -> Result<bool, String> {
    match (&intent.execution_state, succeeded) {
        (ExecutionState::Active, _) => {
            intent.execution_state = if succeeded {
                ExecutionState::Succeeded
            } else {
                ExecutionState::Failed
            };
            intent.execution_finished_at_ms = Some(finished_at_ms);
            intent.desired_conclusion = Some(if succeeded { "success" } else { "failure" }.to_string());
            Ok(true)
        }
        (ExecutionState::Succeeded, true) | (ExecutionState::Failed, false) => Ok(false),
        (ExecutionState::Succeeded, false) | (ExecutionState::Failed, true) => {
            Err("terminal execution evidence cannot change outcome".to_string())
        }
    }
}

async fn ensure_report_root(state: &AppState) -> Result<PathBuf, String> {
    let root = report_root(state);
    match fs::symlink_metadata(&root).await {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err("report-intent root must be an unaliased directory".to_string());
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            match fs::create_dir(&root).await {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(error) => {
                    return Err(format!("could not create report-intent directory: {error}"));
                }
            }
            // Even an AlreadyExists race is accepted only after re-lstat proves
            // the winner created the exact object type we permit.
            let metadata = fs::symlink_metadata(&root)
                .await
                .map_err(|error| format!("could not inspect report-intent directory: {error}"))?;
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err("report-intent root must be an unaliased directory".to_string());
            }
        }
        Err(error) => return Err(format!("could not inspect report-intent directory: {error}")),
    }
    Ok(root)
}

#[cfg(unix)]
fn sync_parent_directory(path: &Path) -> Result<(), String> {
    std::fs::File::open(path)
        .and_then(|file| file.sync_all())
        .map_err(|error| format!("could not fsync report-intent directory: {error}"))
}

#[cfg(not(unix))]
fn sync_parent_directory(_path: &Path) -> Result<(), String> {
    Ok(())
}

// Callers that mutate evidence must hold REPORT_JOURNAL_LOCK across their full
// read/modify/write transaction. This function deliberately does not lock on
// its own so a transaction can include selection/validation plus publication.
async fn persist(state: &AppState, intent: &ReportIntent) -> Result<(), String> {
    validate(intent)?;
    let root = ensure_report_root(state).await?;
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

    let mut file = fs::File::create(&temp)
        .await
        .map_err(|error| format!("could not create report intent: {error}"))?;
    file.write_all(&bytes)
        .await
        .map_err(|error| format!("could not write report intent: {error}"))?;
    file.sync_all()
        .await
        .map_err(|error| format!("could not fsync report intent: {error}"))?;
    drop(file);

    if let Err(first_error) = fs::rename(&temp, &path).await {
        // Windows cannot atomically replace an existing destination. Keep the
        // compatibility fallback bounded and never leave a partial temp file.
        if fs::remove_file(&path).await.is_err() || fs::rename(&temp, &path).await.is_err() {
            let _ = fs::remove_file(&temp).await;
            return Err(format!("could not publish report intent: {first_error}"));
        }
    }
    sync_parent_directory(&root)?;
    Ok(())
}

async fn read_intent_path(path: &Path) -> Result<ReportIntent, String> {
    let metadata = fs::symlink_metadata(path)
        .await
        .map_err(|error| format!("could not inspect report intent: {error}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > MAX_INTENT_BYTES {
        return Err("unsafe report-intent file".to_string());
    }
    let bytes = fs::read(path)
        .await
        .map_err(|error| format!("could not read report intent: {error}"))?;
    let intent: ReportIntent = serde_json::from_slice(&bytes)
        .map_err(|error| format!("invalid report-intent JSON: {error}"))?;
    validate(&intent)?;
    Ok(intent)
}

async fn load_durable_intent(state: &AppState, expected: &ReportIntent) -> Result<ReportIntent, String> {
    let root = ensure_report_root(state).await?;
    let path = intent_path(&root, expected);
    let current = read_intent_path(&path).await?;
    if intent_identity(&current) != intent_identity(expected) {
        return Err("report-intent path collision".to_string());
    }
    Ok(current)
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

/// Create the durable intent before GitHub Check Run creation. A repeated
/// logical attempt returns its existing journal entry instead of resetting any
/// already-persisted execution or delivery evidence.
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
        last_observed_check_state: None,
    };
    validate(&intent)?;

    let _guard = REPORT_JOURNAL_LOCK.lock().await;
    let root = ensure_report_root(state).await?;
    let path = intent_path(&root, &intent);
    match fs::symlink_metadata(&path).await {
        Ok(_) => {
            let existing = read_intent_path(&path).await?;
            if intent_identity(&existing) != intent_identity(&intent) {
                return Err("report-intent path collision".to_string());
            }
            return Ok(Some(existing));
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(format!("could not inspect existing report intent: {error}")),
    }
    persist(state, &intent).await?;
    Ok(Some(intent))
}

pub(crate) async fn record_check_id(
    state: &AppState,
    intent: &mut ReportIntent,
    check_run_id: u64,
) -> Result<(), String> {
    let _guard = REPORT_JOURNAL_LOCK.lock().await;
    // Never publish the caller's potentially stale snapshot. Reload the durable
    // version inside the transaction, apply the immutable binding, then copy the
    // committed state back to the caller.
    let mut current = load_durable_intent(state, intent).await?;
    if bind_check_id(&mut current, check_run_id)? {
        persist(state, &current).await?;
    }
    *intent = current;
    Ok(())
}

fn intent_matches_target(intent: &ReportIntent, target: &CheckTarget) -> bool {
    intent.owner.eq_ignore_ascii_case(&target.owner)
        && intent.repo.eq_ignore_ascii_case(&target.repo)
        && intent.head_sha == target.head_sha
        && intent.report_delivery_state == ReportDeliveryState::Pending
}

fn check_binding_matches(intent: &ReportIntent, check_run_id: Option<u64>) -> bool {
    intent.check_run_id == check_run_id
        || (check_run_id.is_some() && intent.check_run_id.is_none())
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
    let _guard = REPORT_JOURNAL_LOCK.lock().await;
    let mut intents = load_all(state).await?;
    let matches = intents
        .iter_mut()
        .filter(|intent| intent_matches_target(intent, target) && check_binding_matches(intent, check_run_id))
        .collect::<Vec<_>>();
    if matches.len() != 1 {
        return Err(format!(
            "expected one active authoritative report intent, found {}",
            matches.len()
        ));
    }
    let intent = matches.into_iter().next().expect("one match");
    let binding_changed = if let Some(check_run_id) = check_run_id {
        bind_check_id(intent, check_run_id)?
    } else {
        false
    };
    let execution_changed = apply_terminal_execution(intent, succeeded, now_ms())?;
    if execution_changed {
        intent.report_attempt_count = intent.report_attempt_count.saturating_add(1);
        intent.last_report_attempt_at_ms = Some(now_ms());
    }
    if binding_changed || execution_changed {
        persist(state, intent).await?;
    }
    Ok(())
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
    let _guard = REPORT_JOURNAL_LOCK.lock().await;
    let Ok(mut intents) = load_all(state).await else {
        state
            .counters
            .unresolved_report_intents
            .fetch_add(1, Ordering::Relaxed);
        return;
    };
    let matches = intents
        .iter_mut()
        .filter(|intent| intent_matches_target(intent, target) && check_binding_matches(intent, check_run_id))
        .collect::<Vec<_>>();
    if matches.len() != 1 {
        state
            .counters
            .unresolved_report_intents
            .fetch_add(1, Ordering::Relaxed);
        return;
    }
    let intent = matches.into_iter().next().expect("one match");
    if let Some(check_run_id) = check_run_id {
        if bind_check_id(intent, check_run_id).is_err() {
            intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
            intent.last_report_error_class = Some("check_binding_conflict".to_string());
            intent.last_observed_check_state = Some("binding_conflict".to_string());
            let _ = persist(state, intent).await;
            refresh_unresolved_count(state).await;
            return;
        }
    }
    match delivery {
        Delivery::CheckRun => {
            intent.report_delivery_state = ReportDeliveryState::Delivered;
            intent.last_report_error_class = None;
            intent.last_observed_check_state = Some(
                format!("completed:{}", intent.desired_conclusion.as_deref().unwrap_or("unknown")),
            );
        }
        Delivery::Undelivered | Delivery::NotConfigured => {
            intent.last_report_error_class = Some("undelivered".to_string());
        }
        Delivery::CommitStatus => {
            intent.last_report_error_class = Some("wrong_evidence_family".to_string());
        }
    }
    let _ = persist(state, intent).await;
    refresh_unresolved_count(state).await;
}

async fn load_all(state: &AppState) -> Result<Vec<ReportIntent>, String> {
    let root = match fs::symlink_metadata(report_root(state)).await {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err("report-intent root must be an unaliased directory".to_string());
            }
            report_root(state)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("could not inspect report-intent directory: {error}")),
    };
    let mut entries = fs::read_dir(&root)
        .await
        .map_err(|error| format!("could not read report-intent directory: {error}"))?;
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
        intents.push(read_intent_path(&path).await?);
    }
    Ok(intents)
}

fn counts_as_unresolved(intent: &ReportIntent) -> bool {
    intent.report_delivery_state == ReportDeliveryState::PermanentFailure
        || (intent.report_delivery_state == ReportDeliveryState::Pending
            && intent.execution_state != ExecutionState::Active)
}

async fn refresh_unresolved_count(state: &AppState) {
    let unresolved = load_all(state)
        .await
        .map(|intents| intents.iter().filter(|intent| counts_as_unresolved(intent)).count())
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

fn authority_still_matches(state: &AppState, intent: &ReportIntent) -> bool {
    intent.check_context == state.config.check_run_name
        && state.config.github_app_id.as_deref() == Some(intent.reporter_app_id.as_str())
}

async fn reconcile_one(state: &AppState, intent: &mut ReportIntent) {
    // Every non-Pending state is terminal. In particular PermanentFailure must
    // never later become Reconciled merely because a subsequent API call works.
    if intent.report_delivery_state != ReportDeliveryState::Pending {
        return;
    }
    if !authority_still_matches(state, intent) {
        intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
        intent.last_report_error_class = Some("reporting_authority_changed".to_string());
        let _ = persist(state, intent).await;
        return;
    }
    let target = target_for_intent(intent);

    if intent.execution_state == ExecutionState::Active {
        let current = state.jobs.read().await.get(&intent.job_id).cloned();
        if let Some(job) = current {
            match job.status {
                BuildStatus::Queued | BuildStatus::Running => return,
                BuildStatus::Succeeded => {
                    if apply_terminal_execution(
                        intent,
                        true,
                        job.finished_at_ms
                            .and_then(|value| u64::try_from(value).ok())
                            .unwrap_or_else(now_ms),
                    )
                    .is_err()
                    {
                        intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                        intent.last_report_error_class = Some("execution_outcome_conflict".to_string());
                    }
                }
                BuildStatus::Failed => {
                    if apply_terminal_execution(
                        intent,
                        false,
                        job.finished_at_ms
                            .and_then(|value| u64::try_from(value).ok())
                            .unwrap_or_else(now_ms),
                    )
                    .is_err()
                    {
                        intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                        intent.last_report_error_class = Some("execution_outcome_conflict".to_string());
                    }
                }
            }
        } else {
            // A restarted process cannot prove whether an active execution
            // reached success before the crash. Never infer success.
            let _ = apply_terminal_execution(intent, false, now_ms());
            intent.last_report_error_class = Some("orphaned_execution_unknown".to_string());
        }
        let _ = persist(state, intent).await;
        if intent.report_delivery_state != ReportDeliveryState::Pending {
            return;
        }
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
                if bind_check_id(intent, id).is_err() {
                    intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                    intent.last_report_error_class = Some("check_binding_conflict".to_string());
                    let _ = persist(state, intent).await;
                    return;
                }
                intent.last_observed_check_state = Some("discovered".to_string());
                let _ = persist(state, intent).await;
            }
            Ok(None) => {
                intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                intent.last_report_error_class = Some("check_missing".to_string());
                intent.last_observed_check_state = Some("missing".to_string());
                let _ = persist(state, intent).await;
                return;
            }
            Err(error) => {
                intent.last_report_error_class = Some(error);
                intent.last_observed_check_state = Some("lookup_error".to_string());
                let _ = persist(state, intent).await;
                return;
            }
        }
    }
    let Some(check_run_id) = intent.check_run_id else {
        return;
    };

    let observed = match crate::checks::observe_check_run(
        state,
        &target,
        check_run_id,
        &external_id(intent),
    )
    .await
    {
        Ok(observed) => observed,
        Err(error) => {
            if matches!(error.as_str(), "check_not_found" | "check_identity_mismatch") {
                intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
                intent.last_report_error_class = Some(error);
            } else {
                intent.last_report_error_class = Some(error);
            }
            intent.last_observed_check_state = Some("lookup_error".to_string());
            intent.report_attempt_count = intent.report_attempt_count.saturating_add(1);
            intent.last_report_attempt_at_ms = Some(now_ms());
            let _ = persist(state, intent).await;
            return;
        }
    };

    let desired_success = intent.desired_conclusion.as_deref() == Some("success");
    match observed {
        ObservedCheckState::Completed { conclusion } => {
            intent.last_observed_check_state = Some(format!("completed:{conclusion}"));
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
            intent.last_observed_check_state = Some("in_progress".to_string());
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
                intent.last_observed_check_state = Some(format!(
                    "completed:{}",
                    if desired_success { "success" } else { "failure" }
                ));
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
    // Correctness first for the single-laptop authority: hold one async
    // transaction lock across selection, any reconciliation I/O and durable
    // publication so live completion/redelivery cannot overwrite the snapshot.
    // #80 documents that a multi-replica future needs a cross-process CAS.
    let _guard = REPORT_JOURNAL_LOCK.lock().await;
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

    fn test_intent() -> ReportIntent {
        ReportIntent {
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
            last_observed_check_state: None,
        }
    }

    #[test]
    fn logical_identity_is_stable_and_bounded() {
        let intent = test_intent();
        assert!(validate(&intent).is_ok());
        assert_eq!(
            external_id(&intent),
            format!("indiebuild:delivery-123@{}", "a".repeat(40))
        );
        assert_eq!(
            intent_path(Path::new("/tmp"), &intent),
            intent_path(Path::new("/tmp"), &intent)
        );
    }

    #[test]
    fn unknown_execution_can_never_encode_desired_success() {
        let mut intent = test_intent();
        assert_ne!(intent.desired_conclusion.as_deref(), Some("success"));
        intent.execution_state = ExecutionState::Failed;
        intent.execution_finished_at_ms = Some(1);
        intent.desired_conclusion = Some("failure".to_string());
        assert!(validate(&intent).is_ok());
    }

    #[test]
    fn active_jobs_do_not_degrade_readiness_but_terminal_undelivered_jobs_do() {
        let mut intent = test_intent();
        assert!(!counts_as_unresolved(&intent));
        intent.execution_state = ExecutionState::Succeeded;
        intent.execution_finished_at_ms = Some(1);
        intent.desired_conclusion = Some("success".to_string());
        assert!(counts_as_unresolved(&intent));
        intent.report_delivery_state = ReportDeliveryState::Delivered;
        assert!(!counts_as_unresolved(&intent));
    }

    #[test]
    fn journal_rejects_repository_and_authority_path_tricks() {
        let mut intent = test_intent();
        intent.owner = "../org".to_string();
        assert!(validate(&intent).is_err());
        let mut intent = test_intent();
        intent.installation_candidate = Some(0);
        assert!(validate(&intent).is_err());
    }

    #[test]
    fn check_run_binding_is_immutable_and_idempotent() {
        let mut intent = test_intent();
        assert_eq!(bind_check_id(&mut intent, 7), Ok(true));
        assert_eq!(bind_check_id(&mut intent, 7), Ok(false));
        assert!(bind_check_id(&mut intent, 8).is_err());
        assert_eq!(intent.check_run_id, Some(7));
    }

    #[test]
    fn terminal_execution_cannot_regress_or_flip() {
        let mut intent = test_intent();
        assert_eq!(apply_terminal_execution(&mut intent, true, 10), Ok(true));
        assert_eq!(intent.execution_state, ExecutionState::Succeeded);
        assert_eq!(intent.desired_conclusion.as_deref(), Some("success"));
        assert_eq!(apply_terminal_execution(&mut intent, true, 20), Ok(false));
        assert_eq!(intent.execution_finished_at_ms, Some(10));
        assert!(apply_terminal_execution(&mut intent, false, 30).is_err());
        assert_eq!(intent.execution_state, ExecutionState::Succeeded);
        assert_eq!(intent.desired_conclusion.as_deref(), Some("success"));
    }

    #[test]
    fn late_check_binding_still_requires_a_durable_write() {
        let mut intent = test_intent();
        assert_eq!(apply_terminal_execution(&mut intent, true, 10), Ok(true));
        let binding_changed = bind_check_id(&mut intent, 7).expect("late binding");
        let execution_changed = apply_terminal_execution(&mut intent, true, 20).expect("same outcome");
        assert!(binding_changed);
        assert!(!execution_changed);
        assert!(binding_changed || execution_changed);
        assert_eq!(intent.check_run_id, Some(7));
        assert_eq!(intent.execution_finished_at_ms, Some(10));
    }

    #[test]
    fn permanent_failure_is_a_terminal_delivery_state() {
        let mut intent = test_intent();
        intent.execution_state = ExecutionState::Failed;
        intent.execution_finished_at_ms = Some(1);
        intent.desired_conclusion = Some("failure".to_string());
        intent.report_delivery_state = ReportDeliveryState::PermanentFailure;
        assert!(counts_as_unresolved(&intent));
        assert_ne!(intent.report_delivery_state, ReportDeliveryState::Pending);
    }
}
