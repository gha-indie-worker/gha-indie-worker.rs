#![forbid(unsafe_code)]

//! Durable, non-dispatching recovery coordination. No code in this module
//! sends work, clones a repository, or issues trusted provider diagnostics.
//! The staged outbox envelope is explicitly marked dispatch_enabled=false.
//!
//! A single database transaction performs the verified claim, creates its
//! one-and-only outbox intent, and advances the guarded recovery state.
//! No intermediate state is committed on an error or process interruption.

use crate::{
    recovery_claim::{claim_verified, ClaimOutcome},
    recovery_state::RecoveryKey,
};
use sea_orm::{ConnectionTrait, DatabaseConnection, DbBackend, DbErr, Statement, TransactionTrait};
use sha2::{Digest, Sha256};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum StageOutcome {
    Staged {
        version: i64,
        fencing_token: i64,
        idempotency_key: String,
    },
    NotAcquired,
}

pub(crate) fn durable_intent_key(key: &RecoveryKey) -> String {
    let digest = Sha256::digest(format!("gha-recovery-outbox-v1\0{}", key.stable_id()).as_bytes());
    format!("gha-recovery-v1:{}", hex::encode(digest))
}

// Parameterized SQL. No INSERT is permitted without a live matching claim.
// The UNIQUE(recovery_id) and UNIQUE(idempotency_key) constraints are an
// indispensable part of this protocol and must be present in the real schema.
const INSERT_INTENT: &str = r#"
INSERT INTO gha_recovery_outbox
    (recovery_id, idempotency_key, payload, state)
SELECT id, $1, CAST($2 AS jsonb), 'pending'
FROM gha_recovery_runs
WHERE provider = 'github' AND repo = $3
  AND run_id = $4 AND run_attempt = $5 AND head_sha = $6
  AND state = 'claimed' AND claim_owner = $7
  AND version = $8 AND fencing_token = $9
RETURNING id
"#;

const ADVANCE_TO_PENDING: &str = r#"
UPDATE gha_recovery_runs
SET state = 'dispatch_pending',
    version = version + 1, updated_at = clock_timestamp()
WHERE provider = 'github' AND repo = $1
  AND run_id = $2 AND run_attempt = $3 AND head_sha = $4
  AND state = 'claimed' AND claim_owner = $5
  AND version = $6 AND version < 9223372036854775807
  AND fencing_token = $7
  AND EXISTS (
      SELECT 1 FROM gha_recovery_outbox
      WHERE recovery_id = gha_recovery_runs.id AND idempotency_key = $8
        AND state = 'pending'
  )
RETURNING version
"#;

/// Stage a verified recovery. This is NOT wired into HTTP or a dispatcher.
/// Any ambiguous commit outcome must be reconciled by canonical identity
/// before retrying; callers must not interpret transport failure as a denial
/// or automatically create another execution.
pub(crate) async fn stage_verified_recovery(
    db: &DatabaseConnection,
    key: &RecoveryKey,
    expected_version: i64,
    owner: &str,
) -> Result<StageOutcome, DbErr> {
    let tx = db.begin().await?;
    let claimed = claim_verified(&tx, key, expected_version, owner).await?;
    let (claim_version, fencing_token) = match claimed {
        ClaimOutcome::NotAcquired => {
            tx.rollback().await?;
            return Ok(StageOutcome::NotAcquired);
        }
        ClaimOutcome::Claimed {
            version,
            fencing_token,
        } => (version, fencing_token),
    };
    // The envelope is intentionally not executable. Enabling an external
    // dispatcher requires a separate authorization and protocol change.
    let envelope = serde_json::json!({
        "schema": "gha.recovery.intent.v1",
        "dispatch_enabled": false,
        "repo": key.repo,
        "run_id": key.run_id,
        "attempt": key.attempt,
        "head_sha": key.head_sha,
        "fencing_token": fencing_token
    })
    .to_string();
    let idempotency_key = durable_intent_key(key);
    let inserted = tx
        .query_one(Statement::from_sql_and_values(
            DbBackend::Postgres,
            INSERT_INTENT,
            vec![
                idempotency_key.clone().into(),
                envelope.into(),
                key.repo.clone().into(),
                (key.run_id as i64).into(),
                (key.attempt as i64).into(),
                key.head_sha.clone().into(),
                owner.to_string().into(),
                claim_version.into(),
                fencing_token.into(),
            ],
        ))
        .await?;
    if inserted.is_none() {
        tx.rollback().await?;
        return Err(DbErr::Custom(
            "claim lost before durable outbox insertion".into(),
        ));
    }

    let pending = tx
        .query_one(Statement::from_sql_and_values(
            DbBackend::Postgres,
            ADVANCE_TO_PENDING,
            vec![
                key.repo.clone().into(),
                (key.run_id as i64).into(),
                (key.attempt as i64).into(),
                key.head_sha.clone().into(),
                owner.to_string().into(),
                claim_version.into(),
                fencing_token.into(),
                idempotency_key.clone().into(),
            ],
        ))
        .await?;
    let Some(row) = pending else {
        tx.rollback().await?;
        return Err(DbErr::Custom(
            "claim-to-outbox state transition was not applied".into(),
        ));
    };
    let version: i64 = row.try_get("", "version")?;
    tx.commit().await?;
    Ok(StageOutcome::Staged {
        version,
        fencing_token,
        idempotency_key,
    })
}

/// A read-only result for an ambiguous commit outcome. This type deliberately
/// contains no authorization to start work or deliver an outbox message.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum ReconcileOutcome {
    Missing,
    ConfirmedDisabledIntent {
        version: i64,
        fencing_token: i64,
        idempotency_key: String,
    },
    RequiresOperatorReview,
}

const RECONCILE_SQL: &str = r#"
SELECT r.state AS recovery_state, r.version, r.claim_owner,
       r.fencing_token, r.fallback_allowed, r.confidence,
       r.classification, r.verified_diagnostic_kind,
       r.verified_evidence_ref, (r.verified_at IS NOT NULL) AS has_verified_at,
       r.steps_observed, r.runner_assigned,
       o.state AS intent_state, o.idempotency_key, o.payload
FROM gha_recovery_runs r
LEFT JOIN gha_recovery_outbox o ON o.recovery_id = r.id
WHERE r.provider = 'github' AND r.repo = $1
  AND r.run_id = $2 AND r.run_attempt = $3 AND r.head_sha = $4
"#;

/// After a disconnect/ambiguous commit, this read-only lookup determines if
/// the *same* canonical recovery already has a disabled, internally consistent
/// durable intent. Missing, unusual or corrupt records never grant authority.
/// Query errors are propagated (fail closed), never interpreted as missing.
pub(crate) async fn reconcile_disabled_intent(
    db: &DatabaseConnection,
    key: &RecoveryKey,
) -> Result<ReconcileOutcome, DbErr> {
    if !crate::recovery_claim::valid_key(key) {
        return Err(DbErr::Custom("invalid canonical recovery identity".into()));
    }
    let row = db
        .query_one(Statement::from_sql_and_values(
            DbBackend::Postgres,
            RECONCILE_SQL,
            vec![
                key.repo.clone().into(),
                (key.run_id as i64).into(),
                (key.attempt as i64).into(),
                key.head_sha.clone().into(),
            ],
        ))
        .await?;
    let Some(row) = row else {
        return Ok(ReconcileOutcome::Missing);
    };
    let state: String = row.try_get("", "recovery_state")?;
    let version: i64 = row.try_get("", "version")?;
    let owner: Option<String> = row.try_get("", "claim_owner")?;
    let fencing: Option<i64> = row.try_get("", "fencing_token")?;
    let eligible: bool = row.try_get("", "fallback_allowed")?;
    let confidence: String = row.try_get("", "confidence")?;
    let classification: String = row.try_get("", "classification")?;
    let kind: Option<String> = row.try_get("", "verified_diagnostic_kind")?;
    let reference: Option<String> = row.try_get("", "verified_evidence_ref")?;
    let has_verified_at: bool = row.try_get("", "has_verified_at")?;
    let steps: i32 = row.try_get("", "steps_observed")?;
    let runner_assigned: bool = row.try_get("", "runner_assigned")?;
    let outbox_state: Option<String> = row.try_get("", "intent_state")?;
    let outbox_key: Option<String> = row.try_get("", "idempotency_key")?;
    let payload: Option<serde_json::Value> = row.try_get("", "payload")?;
    let Some(fence) = fencing else {
        return Ok(ReconcileOutcome::RequiresOperatorReview);
    };
    // The pending intent has one extra state transition after the claim; the
    // fence never changes during that transition.
    let expected_version = fence.checked_add(1);
    let expected_key = durable_intent_key(key);
    let expected_payload = serde_json::json!({
        "schema": "gha.recovery.intent.v1",
        "dispatch_enabled": false,
        "repo": key.repo,
        "run_id": key.run_id,
        "attempt": key.attempt,
        "head_sha": key.head_sha,
        "fencing_token": fence
    });
    if state != "dispatch_pending"
        || version < 0
        || Some(version) != expected_version
        || owner.as_deref().is_none_or(str::is_empty)
        || !eligible
        || confidence != "explicit"
        || !matches!(classification.as_str(), "billing_or_spending" | "runner_label_or_capacity")
        || kind.as_deref() != Some(classification.as_str())
        || reference.as_deref().is_none_or(str::is_empty)
        || !has_verified_at
        || steps != 0
        || runner_assigned
        || outbox_state.as_deref() != Some("pending")
        || outbox_key.as_deref() != Some(expected_key.as_str())
        || payload.as_ref() != Some(&expected_payload)
    {
        return Ok(ReconcileOutcome::RequiresOperatorReview);
    }
    Ok(ReconcileOutcome::ConfirmedDisabledIntent {
        version,
        fencing_token: fence,
        idempotency_key: expected_key,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idempotency_is_stable_and_binds_attempt_and_sha() {
        let a = RecoveryKey::new("owner/repo", 100, 1, "a".repeat(40)).unwrap();
        let b = RecoveryKey::new("owner/repo", 100, 2, "a".repeat(40)).unwrap();
        let c = RecoveryKey::new("owner/repo", 100, 1, "b".repeat(40)).unwrap();
        assert_eq!(durable_intent_key(&a), durable_intent_key(&a));
        assert_ne!(durable_intent_key(&a), durable_intent_key(&b));
        assert_ne!(durable_intent_key(&a), durable_intent_key(&c));
        assert!(durable_intent_key(&a).len() <= 160);
    }

    #[test]
    fn outbox_is_never_admitted_without_a_matching_claim() {
        for clause in [
            "state = 'claimed'",
            "claim_owner = $7",
            "version = $8",
            "fencing_token = $9",
            "RETURNING id",
        ] {
            assert!(INSERT_INTENT.contains(clause));
        }
        assert!(ADVANCE_TO_PENDING.contains("idempotency_key = $8"));
        assert!(ADVANCE_TO_PENDING.contains("state = 'pending'"));
    }
}
