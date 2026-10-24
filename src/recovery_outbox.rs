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
use sea_orm::{
    ConnectionTrait, DatabaseConnection, DbBackend, DbErr, Statement,
    TransactionTrait,
};
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
        ClaimOutcome::Claimed { version, fencing_token } => (version, fencing_token),
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
    }).to_string();
    let idempotency_key = durable_intent_key(key);
    let inserted = tx.query_one(Statement::from_sql_and_values(
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
    )).await?;
    if inserted.is_none() {
        tx.rollback().await?;
        return Err(DbErr::Custom("claim lost before durable outbox insertion".into()));
    }

    let pending = tx.query_one(Statement::from_sql_and_values(
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
    )).await?;
    let Some(row) = pending else {
        tx.rollback().await?;
        return Err(DbErr::Custom("claim-to-outbox state transition was not applied".into()));
    };
    let version: i64 = row.try_get("", "version")?;
    tx.commit().await?;
    Ok(StageOutcome::Staged {
        version,
        fencing_token,
        idempotency_key,
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
            "state = 'claimed'", "claim_owner = $7",
            "version = $8", "fencing_token = $9",
            "RETURNING id",
        ] {
            assert!(INSERT_INTENT.contains(clause));
        }
        assert!(ADVANCE_TO_PENDING.contains("idempotency_key = $8"));
        assert!(ADVANCE_TO_PENDING.contains("state = 'pending'"));
    }
}
