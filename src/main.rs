use std::{
    collections::{HashMap, HashSet},
    net::SocketAddr,
    sync::Arc,
};

use tokio::{
    fs,
    sync::{RwLock, Semaphore},
};

mod compose_ci;
mod config;
mod db;
mod ecr;
mod entity;
mod events;
mod exec;
mod fiducia;
mod gh_secrets;
mod github_pr_ci;
mod http;
mod jobs;
mod lambda_exec;
mod nats_contract;
mod nats_submit;
mod profiles;
mod runtime_config_registration;
mod state;
mod telemetry;
mod types;
mod util;
mod validation;
mod webhooks;
mod workflow;

// Preserve the historical generated-crate path inside existing modules while
// the public worker stops depending on a private split-repo checkout. The
// compatibility constants themselves live in `nats_contract` and are covered
// by strict drift tests.
extern crate self as dd_nats_subject_defs;
pub(crate) use nats_contract::{
    BUILD_SERVER_EVENTS_SUBJECT, BUILD_SERVER_IMAGES_SUBJECT,
    BUILD_SERVER_REQUESTS_QUEUE_GROUP, BUILD_SERVER_REQUESTS_SUBJECT,
    BUILD_SERVER_RESULTS_SUBJECT, DD_REMOTE_BUILD_JOBS_STREAM_NAME,
    RUNTIME_CRITICAL_EVENTS_SUBJECT,
};

// Preserve the historical runtime-config client path at existing call sites,
// but back it with this public crate's worker-owned typed receiver boundary.
extern crate self as dd_runtime_config_client;
pub(crate) use runtime_config_registration::router;

use config::{config_from_env, env_u64, env_usize, env_value, Config};
use exec::append_log;
use http::build_router;
use jobs::enqueue_build;
use nats_submit::submit_from_nats;
use state::{AppState, Counters, DEFAULT_PORT, SERVICE_NAME};
use types::{BuildJobRecord, BuildRequest, BuildStatus, DeployRequest, NatsSubmitError};
use util::now_ms;

#[tokio::main]
async fn main() {
    let _telemetry = telemetry::init("dd-build-server");

    let config = Arc::new(config_from_env());
    let host = env_value("HOST", "0.0.0.0");
    let port = env_u64("PORT", DEFAULT_PORT as u64) as u16;
    let max_concurrent = env_usize("BUILD_SERVER_MAX_CONCURRENT_BUILDS", 1);

    if let Err(error) = fs::create_dir_all(&config.work_root).await {
        panic!("failed to create build server work root: {error}");
    }

    // Optional Postgres persistence (own database dd_build_server on RDS). A
    // connection failure is fatal only when a URL was configured — it signals
    // misconfiguration; with no URL the server runs in-memory as before.
    let db = match config.database_url.as_deref() {
        Some(url) => match db::connect(url).await {
            Ok(connection) => {
                db::fail_interrupted_jobs(&connection).await;
                Some(connection)
            }
            Err(error) => {
                // Never interpolate the error (`{error}` / `{error:?}`):
                // sea-orm/sqlx inline the full connection string, including the
                // password, on a parse failure — which would land the DSN in
                // pod logs. Discard it and emit only a fixed message. Bind to
                // `_` so the value is explicitly dropped unprinted.
                let _ = error;
                panic!(
                    "BUILD_SERVER_DATABASE_URL was set but connect failed (message suppressed to avoid leaking the DSN)"
                );
            }
        },
        None => {
            tracing::info!(
                "no BUILD_SERVER_DATABASE_URL configured; running with in-memory jobs only"
            );
            None
        }
    };

    // Optional NATS (on by default; failure is non-fatal — the server still
    // serves HTTP, it just won't publish/consume events).
    let nats = if config.nats_enabled {
        match events::connect(&config.nats_url).await {
            Ok(client) => Some(client),
            Err(error) => {
                tracing::warn!("NATS disabled: {error}");
                None
            }
        }
    } else {
        None
    };

    let holder = format!("dd-build-server/{}", uuid::Uuid::new_v4());

    let state = AppState {
        config: config.clone(),
        http: reqwest::Client::new(),
        jobs: Arc::new(RwLock::new(HashMap::new())),
        semaphore: Arc::new(Semaphore::new(max_concurrent)),
        counters: Arc::new(Counters::default()),
        db,
        nats,
        holder,
        recent_request_ids: Arc::new(RwLock::new(HashSet::new())),
    };

    // Durable JetStream build-request intake (opt-in).
    if config.nats_intake_enabled && state.nats.is_some() {
        tokio::spawn(events::run_request_intake(state.clone()));
    }
    // Periodic GitHub Actions secret sync (opt-in; 0 interval = manual only).
    if config.gh_sync_enabled && !config.gh_sync_interval.is_zero() {
        tokio::spawn(gh_secrets::run_periodic_sync(state.clone()));
    }

    // The production route table lives with the handlers it composes
    // (`http::build_router`), so the e2e suite drives the exact same router
    // in-process via `tower::ServiceExt::oneshot`.
    let app = build_router(state);

    tokio::spawn(runtime_config_registration::register_with_control_plane());

    let address: SocketAddr = format!("{host}:{port}")
        .parse()
        .expect("failed to parse bind address");
    tracing::info!("{SERVICE_NAME} listening on http://{address}");

    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("failed to bind tcp listener");
    axum::serve(
        listener,
        app.layer(axum::middleware::from_fn(telemetry::trace_request)),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .expect("axum server crashed");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
