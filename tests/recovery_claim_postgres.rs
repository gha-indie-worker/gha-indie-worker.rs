//! Exercises the exact production claim implementation against ephemeral PG.
#![allow(dead_code)]
#[path = "../src/recovery_claim.rs"]
mod recovery_claim;
#[path = "../src/recovery_state.rs"]
mod recovery_state;

use recovery_claim::{claim_verified, ClaimOutcome};
#[path = "../src/recovery_outbox.rs"]
mod recovery_outbox;
use recovery_outbox::{
    durable_intent_key, reconcile_disabled_intent, stage_verified_recovery, ReconcileOutcome,
    StageOutcome,
};
use recovery_state::RecoveryKey;
use sea_orm::{
    ConnectionTrait, Database, DatabaseConnection, DbBackend, Statement, TransactionTrait,
};

async fn exec(db: &impl ConnectionTrait, sql: &str) {
    db.execute(Statement::from_string(DbBackend::Postgres, sql.to_string()))
        .await
        .unwrap();
}

async fn load(db: &DatabaseConnection, run_id: u64, state: &str, version: u64, runner: bool) {
    let allowed = state == "verified";
    let kind = if allowed {
        "'billing_or_spending'"
    } else {
        "NULL"
    };
    let evidence = if allowed {
        "'ci.synthetic.receipt'"
    } else {
        "NULL"
    };
    let verified_at = if allowed { "now()" } else { "NULL" };
    let sql = format!(
        "INSERT INTO gha_recovery_runs (provider,repo,run_id,run_attempt,head_sha,
          classification,confidence,fallback_allowed,jobs_observed,steps_observed,runner_assigned,
          verified_diagnostic_kind,verified_evidence_ref,verified_at,state,version)
         VALUES ('github','ci/repo',{run_id},1,repeat('a',40),
          'billing_or_spending','explicit',{allowed},0,0,{runner},
          {kind},{evidence},{verified_at},'{state}',{version})"
    );
    exec(db, &sql).await;
}

async fn version_and_owner(db: &DatabaseConnection, run_id: u64) -> (String, i64, Option<String>) {
    let row = db
        .query_one(Statement::from_string(
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
    let db = Database::connect(url.clone()).await.unwrap();
    // Fixture is deliberately minimal. Schema PR #133 validates the complete
    // declarative contract independently using the unabridged schema.sql.
    if std::env::var_os("RECOVERY_USE_FULL_SCHEMA").is_none() {
        exec(
            &db,
            "CREATE TABLE gha_recovery_runs (
      id bigint generated always as identity primary key,
      provider text not null, repo text not null,
      run_id bigint not null, run_attempt bigint not null, head_sha text not null,
      classification text not null, confidence text not null,
      fallback_allowed boolean not null, jobs_observed integer not null, steps_observed integer not null,
      runner_assigned boolean not null, verified_diagnostic_kind text,
      verified_evidence_ref text, verified_at timestamptz, state text not null,
      version bigint not null default 0, claim_owner text,
      fencing_token bigint, claimed_at timestamptz,
      updated_at timestamptz default now(),
      unique(provider,repo,run_id,run_attempt,head_sha),
      constraint nonnegative_version check(version >= 0))",
        )
        .await;
        exec(
            &db,
            "CREATE TABLE gha_recovery_outbox (
      id bigint generated always as identity primary key,
      recovery_id bigint not null REFERENCES gha_recovery_runs(id),
      idempotency_key text not null UNIQUE,
      payload jsonb not null,
      state text not null default 'pending',
      UNIQUE(recovery_id),
      CHECK(char_length(idempotency_key) between 1 and 160),
      CHECK(state IN ('pending', 'delivering', 'delivered', 'failed','canceled')),
      CHECK(payload @> '{\"dispatch_enabled\":false}'::jsonb)
    )",
        )
        .await;
    }

    let key = |id| RecoveryKey::new("ci/repo", id, 1, "a".repeat(40)).unwrap();
    load(&db, 90, "verified", 1, false).await;
    let run90 = key(90);
    let (a, b) = tokio::join!(
        claim_verified(&db, &run90, 1, "worker-a"),
        claim_verified(&db, &run90, 1, "worker-b")
    );
    let wins = [a.unwrap(), b.unwrap()]
        .into_iter()
        .filter(|r| {
            *r == ClaimOutcome::Claimed {
                version: 2,
                fencing_token: 2,
            }
        })
        .count();
    assert_eq!(
        wins, 1,
        "two concurrent workers must never win the same claim"
    );
    let (state, version, owner) = version_and_owner(&db, 90).await;
    assert_eq!((state.as_str(), version), ("claimed", 2));
    assert!(matches!(owner.as_deref(), Some("worker-a" | "worker-b")));
    assert_eq!(
        claim_verified(&db, &key(90), 1, "stale").await.unwrap(),
        ClaimOutcome::NotAcquired
    );
    assert_eq!(
        claim_verified(&db, &key(91), 1, "other-run").await.unwrap(),
        ClaimOutcome::NotAcquired
    );

    // Neither unverified rows nor observed executions can acquire a claim.
    load(&db, 91, "observed", 0, false).await;
    load(&db, 92, "verified", 1, true).await;
    assert_eq!(
        claim_verified(&db, &key(91), 0, "worker").await.unwrap(),
        ClaimOutcome::NotAcquired
    );
    assert_eq!(
        claim_verified(&db, &key(92), 1, "worker").await.unwrap(),
        ClaimOutcome::NotAcquired
    );
    assert!(claim_verified(&db, &key(90), 1, "bad\nowner")
        .await
        .is_err());

    // CAS is composable with a caller-owned transaction and rollback.
    load(&db, 93, "verified", 1, false).await;
    let tx = db.begin().await.unwrap();
    let result = claim_verified(&tx, &key(93), 1, "crashed-before-commit")
        .await
        .unwrap();
    assert_eq!(
        result,
        ClaimOutcome::Claimed {
            version: 2,
            fencing_token: 2
        }
    );
    tx.rollback().await.unwrap();
    assert_eq!(
        version_and_owner(&db, 93).await,
        ("verified".to_string(), 1, None),
        "an interrupted transaction must not leak a claim"
    );

    // Exactly one pending intent is committed along with the claimed state.
    load(&db, 94, "verified", 1, false).await;
    let intent94 = durable_intent_key(&key(94));
    let first = stage_verified_recovery(&db, &key(94), 1, "stage-worker")
        .await
        .unwrap();
    assert_eq!(
        first,
        StageOutcome::Staged {
            version: 3,
            fencing_token: 2,
            idempotency_key: intent94.clone()
        }
    );
    assert_eq!(
        stage_verified_recovery(&db, &key(94), 1, "late-worker")
            .await
            .unwrap(),
        StageOutcome::NotAcquired
    );
    let row = db
        .query_one(Statement::from_string(
            DbBackend::Postgres,
            "SELECT count(*) as n FROM gha_recovery_outbox WHERE idempotency_key = '".to_string()
                + &intent94
                + "'",
        ))
        .await
        .unwrap()
        .unwrap();
    let n: i64 = row.try_get("", "n").unwrap();
    assert_eq!(n, 1);
    let (state, version, owner) = version_and_owner(&db, 94).await;
    assert_eq!(
        (state.as_str(), version, owner.as_deref()),
        ("dispatch_pending", 3, Some("stage-worker"))
    );

    // Simulate a process losing its response immediately after commit. A new
    // independent database connection reads the canonical intent, never
    // attempting a second claim or dispatch.
    let restarted = Database::connect(url.clone()).await.unwrap();
    assert_eq!(
        reconcile_disabled_intent(&restarted, &key(94))
            .await
            .unwrap(),
        ReconcileOutcome::ConfirmedDisabledIntent {
            version: 3,
            fencing_token: 2,
            idempotency_key: intent94.clone(),
        }
    );
    assert_eq!(
        reconcile_disabled_intent(&restarted, &key(999))
            .await
            .unwrap(),
        ReconcileOutcome::Missing,
    );
    let wrong_sha = RecoveryKey::new("ci/repo", 94, 1, "b".repeat(40)).unwrap();
    assert_eq!(
        reconcile_disabled_intent(&restarted, &wrong_sha)
            .await
            .unwrap(),
        ReconcileOutcome::Missing,
    );
    // A stale or malformed canonical identity is a hard error, not "Missing".
    let mut invalid = key(94);
    invalid.attempt = u64::MAX;
    assert!(reconcile_disabled_intent(&restarted, &invalid)
        .await
        .is_err());

    // Two racing full transactions must still produce just one staged intent.
    load(&db, 95, "verified", 1, false).await;
    let run95 = key(95);
    let (a, b) = tokio::join!(
        stage_verified_recovery(&db, &run95, 1, "stage-a"),
        stage_verified_recovery(&db, &run95, 1, "stage-b")
    );
    let wins = [a.unwrap(), b.unwrap()]
        .into_iter()
        .filter(|r| matches!(r, StageOutcome::Staged { .. }))
        .count();
    assert_eq!(wins, 1);
    let row = db
        .query_one(Statement::from_string(
            DbBackend::Postgres,
            "SELECT count(*) AS n FROM gha_recovery_outbox WHERE recovery_id =
          (SELECT id FROM gha_recovery_runs WHERE run_id = 95)"
                .to_string(),
        ))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(row.try_get::<i64>("", "n").unwrap(), 1);

    // Corrupt a previously committed outbox payload. Reconciliation must
    // refuse to affirm the intent even though its idempotency key is correct.
    load(&db, 97, "verified", 1, false).await;
    assert!(matches!(
        stage_verified_recovery(&db, &key(97), 1, "worker97")
            .await
            .unwrap(),
        StageOutcome::Staged { .. }
    ));
    // Even raw SQL must not insert a dispatch-enabled outbox envelope.
    let forbidden_sql = Statement::from_string(
        DbBackend::Postgres,
        "UPDATE gha_recovery_outbox SET payload =
      '{\"schema\":\"gha.recovery.intent.v1\",\"dispatch_enabled\":true}'::jsonb
      WHERE recovery_id = (SELECT id FROM gha_recovery_runs WHERE run_id=97)"
            .to_string(),
    );
    assert!(db.execute(forbidden_sql).await.is_err());
    assert!(matches!(
        reconcile_disabled_intent(&restarted, &key(97))
            .await
            .unwrap(),
        ReconcileOutcome::ConfirmedDisabledIntent { .. }
    ));
    // The DB permits only disabled envelopes; the reconciler additionally
    // rejects noncanonical schemas and malformed metadata.
    exec(
        &db,
        "UPDATE gha_recovery_outbox SET payload =
      '{\"schema\":\"unknown.intent\",\"dispatch_enabled\":false}'::jsonb
      WHERE recovery_id = (SELECT id FROM gha_recovery_runs WHERE run_id=97)",
    )
    .await;
    assert_eq!(
        reconcile_disabled_intent(&restarted, &key(97))
            .await
            .unwrap(),
        ReconcileOutcome::RequiresOperatorReview
    );

    // Even when a complete canonical record exists, tampering with the fence
    // or transition version cannot be mistaken for a valid committed intent.
    load(&db, 98, "verified", 1, false).await;
    assert!(matches!(
        stage_verified_recovery(&db, &key(98), 1, "worker98")
            .await
            .unwrap(),
        StageOutcome::Staged { .. }
    ));
    exec(
        &db,
        "UPDATE gha_recovery_runs SET version=version+1 WHERE run_id=98",
    )
    .await;
    assert_eq!(
        reconcile_disabled_intent(&restarted, &key(98))
            .await
            .unwrap(),
        ReconcileOutcome::RequiresOperatorReview
    );

    // Force outbox UNIQUE(recovery_id) rejection *after* a successful claim
    // and verify the whole transaction rolls back the claimed transition.
    load(&db, 96, "verified", 1, false).await;
    exec(
        &db,
        "INSERT INTO gha_recovery_outbox(recovery_id,idempotency_key,payload)
      SELECT id,'ci.preexisting.96','{}'::jsonb
      FROM gha_recovery_runs WHERE run_id=96",
    )
    .await;
    assert!(stage_verified_recovery(&db, &key(96), 1, "cannot-commit")
        .await
        .is_err());
    assert_eq!(
        version_and_owner(&db, 96).await,
        ("verified".to_string(), 1, None),
        "outbox insert errors must roll back the claim"
    );
    assert_eq!(
        reconcile_disabled_intent(&restarted, &key(96))
            .await
            .unwrap(),
        ReconcileOutcome::RequiresOperatorReview,
        "a rolled-back claim with a stray outbox must require operator review"
    );
}
