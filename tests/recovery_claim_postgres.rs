//! Exercises the exact production claim implementation against ephemeral PG.
#![allow(dead_code)]
#[path = "../src/recovery_state.rs"]
mod recovery_state;
#[path = "../src/recovery_claim.rs"]
mod recovery_claim;

use recovery_claim::{claim_verified, ClaimOutcome};
use recovery_state::RecoveryKey;
use sea_orm::{
    ConnectionTrait, Database, DatabaseConnection, DbBackend, Statement,
    TransactionTrait,
};

async fn exec(db: &impl ConnectionTrait, sql: &str) {
    db.execute(Statement::from_string(DbBackend::Postgres, sql.to_string()))
        .await
        .unwrap();
}

async fn load(db: &DatabaseConnection, run_id: u64, state: &str, version: u64, runner: bool) {
    let sql = format!(
        "INSERT INTO gha_recovery_runs (provider,repo,run_id,run_attempt,head_sha,
          classification,confidence,fallback_allowed,steps_observed,runner_assigned,
          verified_diagnostic_kind,verified_evidence_ref,verified_at,state,version)
         VALUES ('github','ci/repo',{run_id},1,repeat('a',40),
          'billing_or_spending','explicit',true,0,{runner},
          'billing_or_spending','ci.synthetic.receipt',now(),'{state}',{version})"
    );
    exec(db, &sql).await;
}

async fn version_and_owner(db: &DatabaseConnection, run_id: u64) -> (String, i64, Option<String>) {
    let row = db.query_one(Statement::from_string(
        DbBackend::Postgres,
        format!(
            "SELECT state,version,claim_owner FROM gha_recovery_runs WHERE run_id={run_id}"
        ),
    ))
    .await
    .unwrap()
    .unwrap();
    (
        row.try_get("", "state").unwrap(),
        row.try_get("", "version").unwrap(),
        row.try_get("", "claim_owner").unwrap(),
    )
}

#[tokio::test]
async fn live_pg_concurrent_cas_stale_identity_and_transaction_rollback() {
    let url = std::env::var("RECOVERY_TEST_DATABASE_URL")
        .expect("the test requires an isolated ephemeral PostgreSQL service");
    let db = Database::connect(url).await.unwrap();
    // Fixture is deliberately minimal. Schema PR #133 validates the complete
    // declarative contract independently using the unabridged schema.sql.
    exec(&db, "CREATE TABLE gha_recovery_runs (
      id bigint generated always as identity primary key,
      provider text not null, repo text not null,
      run_id bigint not null, run_attempt bigint not null, head_sha text not null,
      classification text not null, confidence text not null,
      fallback_allowed boolean not null, steps_observed integer not null,
      runner_assigned boolean not null, verified_diagnostic_kind text,
      verified_evidence_ref text, verified_at timestamptz, state text not null,
      version bigint not null default 0, claim_owner text,
      fencing_token bigint, claimed_at timestamptz,
      updated_at timestamptz default now(),
      unique(provider,repo,run_id,run_attempt,head_sha),
      constraint nonnegative_version check(version >= 0))").await;

    let key = |id| RecoveryKey::new("ci/repo", id, 1, "a".repeat(40)).unwrap();
    load(&db, 90, "verified", 1, false).await;
    let (a, b) = tokio::join!(
        claim_verified(&db, &key(90), 1, "worker-a"),
        claim_verified(&db, &key(90), 1, "worker-b")
    );
    let wins = [a.unwrap(), b.unwrap()]
        .into_iter()
        .filter(|r| *r == ClaimOutcome::Claimed { version: 2, fencing_token: 2 })
        .count();
    assert_eq!(wins, 1, "two concurrent workers must never win the same claim");
    let (state, version, owner) = version_and_owner(&db, 90).await;
    assert_eq!((state.as_str(), version), ("claimed", 2));
    assert!(matches!(owner.as_deref(), Some("worker-a" | "worker-b")));
    assert_eq!(claim_verified(&db, &key(90), 1, "stale").await.unwrap(), ClaimOutcome::NotAcquired);
    assert_eq!(claim_verified(&db, &key(91), 1, "other-run").await.unwrap(), ClaimOutcome::NotAcquired);

    // Neither unverified rows nor observed executions can acquire a claim.
    load(&db, 91, "observed", 0, false).await;
    load(&db, 92, "verified", 1, true).await;
    assert_eq!(claim_verified(&db, &key(91), 0, "worker").await.unwrap(), ClaimOutcome::NotAcquired);
    assert_eq!(claim_verified(&db, &key(92), 1, "worker").await.unwrap(), ClaimOutcome::NotAcquired);
    assert!(claim_verified(&db, &key(90), 1, "bad\nowner").await.is_err());

    // CAS is composable with a caller-owned transaction and rollback.
    load(&db, 93, "verified", 1, false).await;
    let tx = db.begin().await.unwrap();
    let result = claim_verified(&tx, &key(93), 1, "crashed-before-commit").await.unwrap();
    assert_eq!(result, ClaimOutcome::Claimed { version: 2, fencing_token: 2 });
    tx.rollback().await.unwrap();
    assert_eq!(
        version_and_owner(&db, 93).await,
        ("verified".to_string(), 1, None),
        "an interrupted transaction must not leak a claim"
    );
}
