#![forbid(unsafe_code)]

//! Fail-closed, *unwired* PostgreSQL claim primitive.
//! A future authorized verifier must first persist independently verified
//! provider evidence. This module never issues such evidence, inserts outbox
//! rows, transfers code or dispatches work.

use crate::recovery_state::RecoveryKey;
use sea_orm::{ConnectionTrait, DbBackend, DbErr, Statement};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ClaimOutcome {
    Claimed { version: i64, fencing_token: i64 },
    NotAcquired,
}

fn valid_owner(owner: &str) -> bool {
    (1..=256).contains(&owner.len()) && owner.bytes().all(|byte| byte.is_ascii_graphic())
}

pub(crate) fn valid_key(key: &RecoveryKey) -> bool {
    let Some((owner, name)) = key.repo.split_once('/') else {
        return false;
    };
    let part = |s: &str| {
        !s.is_empty()
            && s.len() <= 100
            && s != "."
            && s != ".."
            && s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    };
    part(owner)
        && part(name)
        && !name.contains("..")
        && key.repo == key.repo.to_ascii_lowercase()
        && key.head_sha.len() == 40
        && key
            .head_sha
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        && key.run_id > 0
        && i64::try_from(key.run_id).is_ok()
        && key.attempt > 0
        && i64::try_from(key.attempt).is_ok()
}

const CLAIM_SQL: &str = r#"
UPDATE gha_recovery_runs
SET state = 'claimed',
    claim_owner = $1,
    fencing_token = version + 1,
    claimed_at = clock_timestamp(),
    version = version + 1,
    updated_at = clock_timestamp()
WHERE provider = 'github'
  AND repo = $2 AND run_id = $3 AND run_attempt = $4 AND head_sha = $5
  AND version = $6 AND version < 9223372036854775807
  AND state = 'verified' AND claim_owner IS NULL
  AND fallback_allowed = true AND confidence = 'explicit'
  AND classification = verified_diagnostic_kind
  AND verified_diagnostic_kind IN ('billing_or_spending', 'runner_label_or_capacity')
  AND verified_evidence_ref IS NOT NULL AND verified_at IS NOT NULL
  AND steps_observed = 0 AND runner_assigned = false
RETURNING version, fencing_token
"#;

/// Atomic compare-and-swap claim, guarded by verified provider evidence and
/// a precise run identity. Concurrent callers cannot both receive Claimed.
/// Pass a DatabaseTransaction for larger atomic operations in later phases;
/// this function does not commit a transaction on the caller's behalf.
pub(crate) async fn claim_verified<C: ConnectionTrait>(
    db: &C,
    key: &RecoveryKey,
    expected_version: i64,
    owner: &str,
) -> Result<ClaimOutcome, DbErr> {
    if !valid_owner(owner) || !valid_key(key) || !(0..i64::MAX).contains(&expected_version) {
        return Err(DbErr::Custom(
            "invalid recovery claim identity or owner".into(),
        ));
    }
    let statement = Statement::from_sql_and_values(
        DbBackend::Postgres,
        CLAIM_SQL,
        vec![
            owner.to_string().into(),
            key.repo.clone().into(),
            (key.run_id as i64).into(),
            (key.attempt as i64).into(),
            key.head_sha.clone().into(),
            expected_version.into(),
        ],
    );
    match db.query_one(statement).await? {
        Some(row) => Ok(ClaimOutcome::Claimed {
            version: row.try_get("", "version")?,
            fencing_token: row.try_get("", "fencing_token")?,
        }),
        None => Ok(ClaimOutcome::NotAcquired),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_identifiers_fail_before_any_db_attempt() {
        assert!(!valid_owner(""));
        assert!(!valid_owner("with\nnewline"));
        assert!(!valid_owner(&"x".repeat(257)));
        assert!(valid_owner("worker-123"));
        let key = RecoveryKey::new("owner/repo", 3, 1, "a".repeat(40)).unwrap();
        assert!(valid_key(&key));
        let mut bad = key.clone();
        bad.run_id = u64::MAX;
        assert!(!valid_key(&bad));
        bad = key;
        bad.head_sha = "A".repeat(40);
        assert!(!valid_key(&bad));
    }

    #[test]
    fn claim_requires_version_and_provider_evidence_predicates() {
        for required in [
            "version = $6",
            "claim_owner IS NULL",
            "state = 'verified'",
            "fallback_allowed = true",
            "confidence = 'explicit'",
            "classification = verified_diagnostic_kind",
            "verified_evidence_ref IS NOT NULL",
            "verified_at IS NOT NULL",
            "steps_observed = 0",
            "runner_assigned = false",
            "RETURNING version, fencing_token",
        ] {
            assert!(CLAIM_SQL.contains(required), "{required}");
        }
    }
}
