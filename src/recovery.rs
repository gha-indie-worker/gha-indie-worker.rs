//! Observe-only GitHub Actions recovery controller.
//!
//! This module may authenticate and inspect a failed workflow run, then persist
//! a durable receipt. It deliberately has no dispatcher. A future activation
//! must add a transactional outbox in the schema-owning repository before any
//! fallback execution is reachable.

use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::{
    admission::{
        inspect_authenticated, AdmissionEvidenceConfidence, ObserveError, RunIdentity, RunReceipt,
    },
    db,
    state::AppState,
};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RecoveryCandidate {
    pub(crate) identity: RunIdentity,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CandidateError {
    MissingRun,
    InvalidIdentity,
}

#[derive(Debug)]
pub(crate) enum RecoveryOutcome {
    Disabled,
    BlockedMissingDatabase,
    BlockedMissingToken,
    ObservationFailed(ObserveError),
    PersistenceFailed,
    Recorded {
        key: String,
        receipt: RunReceipt,
    },
    Duplicate {
        key: String,
        receipt: RunReceipt,
    },
}

fn valid_repo(repo: &str) -> bool {
    let Some((owner, name)) = repo.split_once('/') else {
        return false;
    };
    let valid = |part: &str| {
        !part.is_empty()
            && part.len() <= 100
            && part != "."
            && part != ".."
            && part
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_' | b'.'))
    };
    valid(owner) && valid(name) && !name.contains("..")
}

fn valid_head_sha(sha: &str) -> bool {
    sha.len() == 40 && sha.bytes().all(|c| c.is_ascii_hexdigit())
}


pub(crate) fn workflow_run_candidate(
    payload: &Value,
) -> Result<Option<RecoveryCandidate>, CandidateError> {
    if payload.get("action").and_then(Value::as_str) != Some("completed") {
        return Ok(None);
    }
    let run = payload.get("workflow_run").ok_or(CandidateError::MissingRun)?;
    let status = run.get("status").and_then(Value::as_str).unwrap_or_default();
    let conclusion = run
        .get("conclusion")
        .and_then(Value::as_str)
        .unwrap_or_default();

    // Recovery is relevant only to terminal admission-failure shapes. Success
    // continues through the ordinary webhook path; cancellation/supersession is
    // never a fallback candidate.
    if status != "completed"
        || !matches!(
            conclusion,
            "failure" | "action_required" | "startup_failure"
        )
    {
        return Ok(None);
    }

    let repo = payload
        .pointer("/repository/full_name")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let run_id = run.get("id").and_then(Value::as_u64).unwrap_or(0);
    let attempt = run
        .get("run_attempt")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let head_sha = run
        .get("head_sha")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !valid_repo(repo) || run_id == 0 || attempt == 0 || !valid_head_sha(head_sha) {
        return Err(CandidateError::InvalidIdentity);
    }
    Ok(Some(RecoveryCandidate {
        identity: RunIdentity {
            repo: repo.to_ascii_lowercase(),
            run_id,
            attempt,
            head_sha: head_sha.to_ascii_lowercase(),
        },
    }))
}

pub(crate) fn recovery_key(identity: &RunIdentity) -> String {
    let mut digest = Sha256::new();
    digest.update(b"github-actions-recovery-v1\0");
    digest.update(identity.repo.to_ascii_lowercase().as_bytes());
    digest.update(b"\0");
    digest.update(identity.run_id.to_be_bytes());
    digest.update(identity.attempt.to_be_bytes());
    digest.update(identity.head_sha.to_ascii_lowercase().as_bytes());
    format!("v1:{}", hex::encode(digest.finalize()))
}

fn confidence_label(confidence: AdmissionEvidenceConfidence) -> &'static str {
    match confidence {
        AdmissionEvidenceConfidence::Explicit => "explicit",
        AdmissionEvidenceConfidence::Ambiguous => "ambiguous",
    }
}

fn durable_action(receipt: &RunReceipt) -> String {
    format!(
        "observed:{}:{}:allow={}:jobs={}:steps={}:runner={}",
        receipt.classification.kind.as_str(),
        confidence_label(receipt.classification.confidence),
        receipt.classification.fallback_allowed,
        receipt.jobs_observed,
        receipt.steps_observed,
        receipt.runner_assigned
    )
}

/// Authenticates the exact run identity against GitHub and durably records the
/// normalized result. There is intentionally no fallback dispatcher here.
pub(crate) async fn observe_candidate(
    state: &AppState,
    candidate: RecoveryCandidate,
) -> RecoveryOutcome {
    if !state.config.github_recovery_enabled {
        return RecoveryOutcome::Disabled;
    }
    let Some(db) = state.db.as_ref() else {
        return RecoveryOutcome::BlockedMissingDatabase;
    };
    let Some(token) = state.config.github_recovery_token.as_deref() else {
        return RecoveryOutcome::BlockedMissingToken;
    };

    let receipt = match inspect_authenticated(token, &candidate.identity).await {
        Ok(receipt) => receipt,
        Err(error) => return RecoveryOutcome::ObservationFailed(error),
    };

    // Re-check the identity returned by the observer before persistence. The API
    // adapter already enforces this, but keeping the controller invariant
    // explicit prevents a future observer implementation from weakening it.
    if receipt.identity != candidate.identity {
        return RecoveryOutcome::ObservationFailed(ObserveError::MismatchedIdentity);
    }

    let key = recovery_key(&candidate.identity);
    let action = durable_action(&receipt);
    match db::record_recovery_receipt_strict(
        db,
        &key,
        &candidate.identity.repo,
        &candidate.identity.head_sha,
        &action,
    )
    .await
    {
        Ok(true) => RecoveryOutcome::Recorded { key, receipt },
        Ok(false) => RecoveryOutcome::Duplicate { key, receipt },
        Err(_) => RecoveryOutcome::PersistenceFailed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn payload(conclusion: &str) -> Value {
        json!({
            "action": "completed",
            "repository": {"full_name": "Example/Repo"},
            "workflow_run": {
                "id": 42,
                "run_attempt": 3,
                "status": "completed",
                "conclusion": conclusion,
                "head_sha": "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                "head_branch": "main"
            }
        })
    }

    #[test]
    fn terminal_failure_binds_exact_identity() {
        let candidate = workflow_run_candidate(&payload("failure"))
            .unwrap()
            .expect("failure should be a candidate");
        assert_eq!(candidate.identity.repo, "example/repo");
        assert_eq!(candidate.identity.run_id, 42);
        assert_eq!(candidate.identity.attempt, 3);
        assert_eq!(
            candidate.identity.head_sha,
            "abcdef0123456789abcdef0123456789abcdef01"
        );
    }

    #[test]
    fn success_cancel_and_noncompleted_delivery_are_not_candidates() {
        assert!(workflow_run_candidate(&payload("success")).unwrap().is_none());
        assert!(workflow_run_candidate(&payload("cancelled")).unwrap().is_none());
        let mut pending = payload("failure");
        pending["action"] = json!("requested");
        assert!(workflow_run_candidate(&pending).unwrap().is_none());
    }

    #[test]
    fn malformed_identity_fails_closed() {
        let mut bad = payload("failure");
        bad["workflow_run"]["run_attempt"] = json!(0);
        assert_eq!(
            workflow_run_candidate(&bad),
            Err(CandidateError::InvalidIdentity)
        );

        let mut bad_sha = payload("failure");
        bad_sha["workflow_run"]["head_sha"] = json!("not-a-sha");
        assert_eq!(
            workflow_run_candidate(&bad_sha),
            Err(CandidateError::InvalidIdentity)
        );
    }

    #[test]
    fn durable_key_is_canonical_and_attempt_scoped() {
        let a = workflow_run_candidate(&payload("failure"))
            .unwrap()
            .unwrap()
            .identity;
        let mut b = a.clone();
        b.repo = "EXAMPLE/REPO".to_string();
        b.head_sha = b.head_sha.to_ascii_uppercase();
        assert_eq!(recovery_key(&a), recovery_key(&b));

        b.attempt += 1;
        assert_ne!(recovery_key(&a), recovery_key(&b));
        assert!(recovery_key(&a).len() < 128);
    }
}
