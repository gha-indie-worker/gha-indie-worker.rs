use std::env;
use std::net::SocketAddr;

use axum::extract::DefaultBodyLimit;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use dd_build_server::workflow::{plan_workflow, MAX_MATRIX_JOBS};
use serde_json::json;

const DEFAULT_HOST: &str = "0.0.0.0";
const DEFAULT_PORT: u16 = 8090;
const MAX_WORKFLOW_BYTES: usize = 1024 * 1024;

#[tokio::main]
async fn main() {
    let host = env::var("GHA_WORKFLOW_HOST").unwrap_or_else(|_| DEFAULT_HOST.to_owned());
    let port = env::var("GHA_WORKFLOW_PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .unwrap_or(DEFAULT_PORT);
    let address: SocketAddr = format!("{host}:{port}")
        .parse()
        .unwrap_or_else(|error| panic!("invalid workflow planner bind address: {error}"));

    let app = Router::new()
        .route("/", get(descriptor))
        .route("/healthz", get(healthz))
        .route("/v1/workflows/plan", post(plan))
        .layer(DefaultBodyLimit::max(MAX_WORKFLOW_BYTES));

    let listener = tokio::net::TcpListener::bind(address)
        .await
        .unwrap_or_else(|error| panic!("failed to bind workflow planner: {error}"));
    tracing::info!("gha-workflow-server listening on http://{address}");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .unwrap_or_else(|error| panic!("workflow planner server failed: {error}"));
}

async fn descriptor() -> impl IntoResponse {
    Json(json!({
        "service": "gha-workflow-server",
        "schemaVersion": "gha-indie-worker.plan.v1",
        "endpoints": {
            "plan": "POST /v1/workflows/plan",
            "health": "GET /healthz"
        },
        "input": "GitHub Actions workflow YAML as the request body",
        "supported": [
            "jobs",
            "needs",
            "runs-on",
            "steps.run",
            "steps.uses",
            "job and step env",
            "static strategy.matrix",
            "matrix include/exclude",
            "reusable workflow jobs"
        ],
        "expressions": "preserved but not evaluated",
        "maxMatrixJobs": MAX_MATRIX_JOBS,
        "maxWorkflowBytes": MAX_WORKFLOW_BYTES
    }))
}

async fn healthz() -> impl IntoResponse {
    Json(json!({
        "ok": true,
        "service": "gha-workflow-server",
        "schemaVersion": "gha-indie-worker.plan.v1"
    }))
}

async fn plan(yaml: String) -> Response {
    match plan_workflow(&yaml) {
        Ok(plan) => (StatusCode::OK, Json(plan)).into_response(),
        Err(error) => (StatusCode::UNPROCESSABLE_ENTITY, Json(error)).into_response(),
    }
}

async fn shutdown_signal() {
    let control_c = async {
        tokio::signal::ctrl_c()
            .await
            .unwrap_or_else(|error| panic!("failed to install Ctrl+C handler: {error}"));
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .unwrap_or_else(|error| panic!("failed to install SIGTERM handler: {error}"))
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        () = control_c => {},
        () = terminate => {},
    }
}
