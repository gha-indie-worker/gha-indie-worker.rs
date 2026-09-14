//! Worker-owned runtime-config receiver and registration boundary.
//!
//! The public worker cannot depend on the private split-repo client crate, so
//! this module implements the small HTTP contract the worker actually needs.
//! The wire names mirror the shared runtime-config JSON Schema while secrets
//! remain transport-only and are never serialized into registration payloads.

use std::{
    collections::HashMap,
    env,
    sync::{Arc, OnceLock},
    time::Duration,
};

use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use reqwest::{redirect::Policy, Client};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use subtle::ConstantTimeEq;
use tokio::sync::RwLock;

pub const APPLY_ROUTE_PATH: &str = "/internal/update-runtime-config";
pub const SNAPSHOT_ROUTE_PATH: &str = "/internal/runtime-config";
pub const RESET_ROUTE_PATH: &str = "/internal/runtime-config/reset";

const INITIAL_BACKOFF: Duration = Duration::from_secs(15);
const MAX_BACKOFF: Duration = Duration::from_secs(300);

#[derive(Clone, Debug, PartialEq, Eq)]
struct RegistrationConfig {
    service_name: String,
    scope: String,
    environment: RuntimeConfigEnv,
    register_url: String,
    apply_url: String,
    server_auth: Option<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
enum RuntimeConfigEnv {
    Stage,
    Prod,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
enum RuntimeConfigApplyReason {
    Cron,
    Admin,
    Register,
    Manual,
    Initial,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeConfigEntry {
    env: RuntimeConfigEnv,
    scope: String,
    key: String,
    value: Option<Value>,
    version: i64,
    updated_at: String,
    #[serde(default)]
    labels: Vec<String>,
    #[serde(default)]
    meta: HashMap<String, Value>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeConfigSnapshot {
    env: RuntimeConfigEnv,
    scope: String,
    generated_at: String,
    snapshot_version: i64,
    entries: Vec<RuntimeConfigEntry>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeConfigApplyRequest {
    push_id: String,
    reason: RuntimeConfigApplyReason,
    snapshot: RuntimeConfigSnapshot,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct RegistrationRequest {
    env: RuntimeConfigEnv,
    name: String,
    scope: String,
    apply_url: String,
    labels: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeConfigApplyResponse {
    ok: bool,
    service: String,
    applied_at: String,
    applied_version: i64,
    previous_version: Option<i64>,
    stale: Option<bool>,
    ignored_version: Option<i64>,
    errors: Option<Vec<String>>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeConfigSnapshotResponse {
    service: Option<String>,
    scope: Option<String>,
    env: Option<String>,
    snapshot_version: i64,
    applied_at: Option<String>,
    entries: HashMap<String, Value>,
    last_push_id: Option<String>,
    last_reason: Option<String>,
}

#[derive(Debug, Serialize)]
struct RuntimeConfigResetResponse {
    ok: bool,
}

#[derive(Default)]
struct RuntimeConfigState {
    snapshot_version: i64,
    applied_at: Option<String>,
    entries: HashMap<String, Value>,
    last_push_id: Option<String>,
    last_reason: Option<String>,
}

#[derive(Clone)]
struct RuntimeConfigStore {
    inner: Arc<RwLock<RuntimeConfigState>>,
    server_secret: Option<String>,
    allow_unauthenticated: bool,
}

impl RuntimeConfigStore {
    fn from_env() -> Self {
        Self {
            inner: Arc::new(RwLock::new(RuntimeConfigState::default())),
            server_secret: nonempty(env::var("RUNTIME_CONFIG_SERVER_SECRET").ok()),
            allow_unauthenticated: env_bool("RUNTIME_CONFIG_ALLOW_UNAUTHENTICATED"),
        }
    }

    #[cfg(test)]
    fn for_test(server_secret: Option<&str>, allow_unauthenticated: bool) -> Self {
        Self {
            inner: Arc::new(RwLock::new(RuntimeConfigState::default())),
            server_secret: server_secret.map(ToOwned::to_owned),
            allow_unauthenticated,
        }
    }
}

fn global_store() -> &'static RuntimeConfigStore {
    static STORE: OnceLock<RuntimeConfigStore> = OnceLock::new();
    STORE.get_or_init(RuntimeConfigStore::from_env)
}

impl RegistrationConfig {
    fn from_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Option<Self> {
        let service_name = nonempty(lookup("RUNTIME_CONFIG_SERVICE_NAME"))?;
        let register_url = nonempty(lookup("RUNTIME_CONFIG_REGISTER_URL"))?;
        let apply_url = nonempty(lookup("RUNTIME_CONFIG_APPLY_URL"))?;
        let scope = nonempty(lookup("RUNTIME_CONFIG_SCOPE")).unwrap_or_else(|| service_name.clone());
        let environment = normalize_environment(nonempty(lookup("RUNTIME_CONFIG_ENV")).as_deref());
        let server_auth = nonempty(lookup("RUNTIME_CONFIG_SERVER_SECRET"));
        Some(Self {
            service_name,
            scope,
            environment,
            register_url,
            apply_url,
            server_auth,
        })
    }

    fn from_env() -> Option<Self> {
        Self::from_lookup(|key| env::var(key).ok())
    }

    fn request(&self) -> RegistrationRequest {
        RegistrationRequest {
            env: self.environment,
            name: self.service_name.clone(),
            scope: self.scope.clone(),
            apply_url: self.apply_url.clone(),
            labels: Vec::new(),
        }
    }
}

fn nonempty(value: Option<String>) -> Option<String> {
    value.and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn env_bool(name: &str) -> bool {
    matches!(
        env::var(name).ok().as_deref(),
        Some("1" | "true" | "TRUE" | "yes" | "YES")
    )
}

fn normalize_environment(value: Option<&str>) -> RuntimeConfigEnv {
    match value.map(str::trim).map(str::to_ascii_lowercase).as_deref() {
        Some("prod" | "production") => RuntimeConfigEnv::Prod,
        _ => RuntimeConfigEnv::Stage,
    }
}

fn reason_label(reason: RuntimeConfigApplyReason) -> &'static str {
    match reason {
        RuntimeConfigApplyReason::Cron => "cron",
        RuntimeConfigApplyReason::Admin => "admin",
        RuntimeConfigApplyReason::Register => "register",
        RuntimeConfigApplyReason::Manual => "manual",
        RuntimeConfigApplyReason::Initial => "initial",
    }
}

fn next_backoff(current: Duration) -> Duration {
    current.saturating_mul(2).min(MAX_BACKOFF)
}

fn iso_now() -> String {
    chrono::Utc::now().to_rfc3339()
}

fn constant_time_eq(left: &str, right: &str) -> bool {
    left.len() == right.len() && bool::from(left.as_bytes().ct_eq(right.as_bytes()))
}

fn unauthorized(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({ "ok": false, "error": message }))).into_response()
}

fn require_server_auth(store: &RuntimeConfigStore, headers: &HeaderMap) -> Result<(), Response> {
    let Some(expected) = store.server_secret.as_deref() else {
        return if store.allow_unauthenticated {
            Ok(())
        } else {
            Err(unauthorized(
                StatusCode::SERVICE_UNAVAILABLE,
                "runtime config auth is not configured",
            ))
        };
    };
    let provided = headers
        .get("x-server-auth")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    if provided.is_empty() || !constant_time_eq(provided, expected) {
        return Err(unauthorized(StatusCode::UNAUTHORIZED, "unauthorized"));
    }
    Ok(())
}

async fn get_snapshot(State(store): State<RuntimeConfigStore>, headers: HeaderMap) -> Response {
    if store.server_secret.is_some() {
        if let Err(response) = require_server_auth(&store, &headers) {
            return response;
        }
    }
    let state = store.inner.read().await;
    Json(RuntimeConfigSnapshotResponse {
        service: nonempty(env::var("RUNTIME_CONFIG_SERVICE_NAME").ok()),
        scope: nonempty(env::var("RUNTIME_CONFIG_SCOPE").ok()),
        env: nonempty(env::var("RUNTIME_CONFIG_ENV").ok()),
        snapshot_version: state.snapshot_version,
        applied_at: state.applied_at.clone(),
        entries: state.entries.clone(),
        last_push_id: state.last_push_id.clone(),
        last_reason: state.last_reason.clone(),
    })
    .into_response()
}

async fn apply_snapshot(
    State(store): State<RuntimeConfigStore>,
    headers: HeaderMap,
    Json(body): Json<RuntimeConfigApplyRequest>,
) -> Response {
    if let Err(response) = require_server_auth(&store, &headers) {
        return response;
    }

    let incoming_version = body.snapshot.snapshot_version;
    let mut state = store.inner.write().await;
    let previous_version = state.snapshot_version;
    if incoming_version < previous_version {
        return Json(RuntimeConfigApplyResponse {
            ok: true,
            service: nonempty(env::var("RUNTIME_CONFIG_SERVICE_NAME").ok())
                .unwrap_or_else(|| "unknown".to_owned()),
            applied_at: state.applied_at.clone().unwrap_or_else(iso_now),
            applied_version: previous_version,
            previous_version: Some(previous_version),
            stale: Some(true),
            ignored_version: Some(incoming_version),
            errors: None,
        })
        .into_response();
    }

    state.snapshot_version = incoming_version;
    state.applied_at = Some(iso_now());
    state.entries = body
        .snapshot
        .entries
        .into_iter()
        .map(|entry| (entry.key, entry.value.unwrap_or(Value::Null)))
        .collect();
    state.last_push_id = Some(body.push_id);
    state.last_reason = Some(reason_label(body.reason).to_owned());

    Json(RuntimeConfigApplyResponse {
        ok: true,
        service: nonempty(env::var("RUNTIME_CONFIG_SERVICE_NAME").ok())
            .unwrap_or_else(|| "unknown".to_owned()),
        applied_at: state.applied_at.clone().unwrap_or_else(iso_now),
        applied_version: incoming_version,
        previous_version: Some(previous_version),
        stale: None,
        ignored_version: None,
        errors: None,
    })
    .into_response()
}

async fn reset_snapshot(State(store): State<RuntimeConfigStore>, headers: HeaderMap) -> Response {
    if let Err(response) = require_server_auth(&store, &headers) {
        return response;
    }
    *store.inner.write().await = RuntimeConfigState::default();
    Json(RuntimeConfigResetResponse { ok: true }).into_response()
}

fn router_with_store(store: RuntimeConfigStore) -> Router {
    Router::new()
        .route(SNAPSHOT_ROUTE_PATH, get(get_snapshot))
        .route(APPLY_ROUTE_PATH, post(apply_snapshot))
        .route(RESET_ROUTE_PATH, post(reset_snapshot))
        .with_state(store)
}

pub fn router() -> Router {
    router_with_store(global_store().clone())
}

async fn register_once(client: &Client, config: &RegistrationConfig) -> Result<bool, reqwest::Error> {
    let mut request = client.post(&config.register_url).json(&config.request());
    if let Some(server_auth) = config.server_auth.as_deref() {
        request = request.header("x-server-auth", server_auth);
    }
    Ok(request.send().await?.status().is_success())
}

/// Register this worker with the runtime-config control plane when configured.
/// Missing configuration disables registration. Failed attempts use bounded
/// exponential backoff and never print the server credential.
pub async fn register_with_control_plane() {
    let Some(config) = RegistrationConfig::from_env() else {
        tracing::info!(
            "runtime-config registration disabled: required endpoint settings are absent"
        );
        return;
    };

    let client = Client::builder()
        .timeout(Duration::from_secs(10))
        .redirect(Policy::none())
        .build()
        .expect("runtime-config registration HTTP client must build");

    let mut backoff = INITIAL_BACKOFF;
    loop {
        match register_once(&client, &config).await {
            Ok(true) => {
                tracing::info!(
                    service = %config.service_name,
                    scope = %config.scope,
                    environment = ?config.environment,
                    "registered with runtime-config control plane"
                );
                return;
            }
            Ok(false) => tracing::warn!(
                service = %config.service_name,
                "runtime-config registration returned a non-success status"
            ),
            Err(_) => tracing::warn!(
                service = %config.service_name,
                "runtime-config registration request failed"
            ),
        }
        tokio::time::sleep(backoff).await;
        backoff = next_backoff(backoff);
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use axum::{body::Body, http::Request};
    use tower::ServiceExt;

    use super::*;

    fn config(values: &[(&str, &str)]) -> Option<RegistrationConfig> {
        let values: BTreeMap<_, _> = values.iter().copied().collect();
        RegistrationConfig::from_lookup(|key| values.get(key).map(|value| (*value).to_owned()))
    }

    fn apply_body(version: i64) -> Value {
        json!({
            "pushId": "push-1",
            "reason": "manual",
            "snapshot": {
                "env": "stage",
                "scope": "worker",
                "generatedAt": "2026-09-14T00:00:00Z",
                "snapshotVersion": version,
                "entries": [{
                    "env": "stage",
                    "scope": "worker",
                    "key": "MAX_JOBS",
                    "value": 3,
                    "version": 1,
                    "updatedAt": "2026-09-14T00:00:00Z"
                }]
            }
        })
    }

    async fn response_json(response: Response) -> Value {
        let bytes = axum::body::to_bytes(response.into_body(), 1024 * 1024)
            .await
            .expect("response body");
        serde_json::from_slice(&bytes).expect("json response")
    }

    #[test]
    fn missing_required_endpoint_disables_registration() {
        assert!(config(&[("RUNTIME_CONFIG_SERVICE_NAME", "worker")]).is_none());
    }

    #[test]
    fn scope_defaults_to_service_and_environment_defaults_to_stage() {
        let config = config(&[
            ("RUNTIME_CONFIG_SERVICE_NAME", "worker"),
            (
                "RUNTIME_CONFIG_REGISTER_URL",
                "https://control.example/register",
            ),
            ("RUNTIME_CONFIG_APPLY_URL", "https://worker.example/apply"),
        ])
        .expect("complete config");
        assert_eq!(config.scope, "worker");
        assert_eq!(config.environment, RuntimeConfigEnv::Stage);
    }

    #[test]
    fn production_alias_normalizes_to_prod() {
        assert_eq!(normalize_environment(Some("production")), RuntimeConfigEnv::Prod);
        assert_eq!(normalize_environment(Some("PROD")), RuntimeConfigEnv::Prod);
        assert_eq!(normalize_environment(Some("staging")), RuntimeConfigEnv::Stage);
    }

    #[test]
    fn request_contains_no_server_auth_credential() {
        let config = config(&[
            ("RUNTIME_CONFIG_SERVICE_NAME", "worker"),
            (
                "RUNTIME_CONFIG_REGISTER_URL",
                "https://control.example/register",
            ),
            ("RUNTIME_CONFIG_APPLY_URL", "https://worker.example/apply"),
            ("RUNTIME_CONFIG_SERVER_SECRET", "never-serialize-this"),
        ])
        .expect("complete config");
        let json = serde_json::to_string(&config.request()).expect("request serializes");
        assert!(!json.contains("never-serialize-this"));
        assert!(!json.contains("server_auth"));
    }

    #[test]
    fn retry_backoff_is_bounded() {
        assert_eq!(
            next_backoff(Duration::from_secs(15)),
            Duration::from_secs(30)
        );
        assert_eq!(
            next_backoff(Duration::from_secs(240)),
            Duration::from_secs(300)
        );
        assert_eq!(
            next_backoff(Duration::from_secs(300)),
            Duration::from_secs(300)
        );
    }

    #[tokio::test]
    async fn mutation_requires_server_auth() {
        let app = router_with_store(RuntimeConfigStore::for_test(Some("secret"), false));
        let request = Request::builder()
            .method("POST")
            .uri(APPLY_ROUTE_PATH)
            .header("content-type", "application/json")
            .body(Body::from(apply_body(1).to_string()))
            .expect("request");
        let response = app.oneshot(request).await.expect("response");
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn stale_snapshot_is_acknowledged_without_rollback() {
        let store = RuntimeConfigStore::for_test(Some("secret"), false);
        let app = router_with_store(store.clone());
        for version in [5_i64, 4_i64] {
            let request = Request::builder()
                .method("POST")
                .uri(APPLY_ROUTE_PATH)
                .header("content-type", "application/json")
                .header("x-server-auth", "secret")
                .body(Body::from(apply_body(version).to_string()))
                .expect("request");
            let response = app.clone().oneshot(request).await.expect("response");
            assert_eq!(response.status(), StatusCode::OK);
            if version == 4 {
                let json = response_json(response).await;
                assert_eq!(json["stale"], true);
                assert_eq!(json["appliedVersion"], 5);
                assert_eq!(json["ignoredVersion"], 4);
            }
        }
        assert_eq!(store.inner.read().await.snapshot_version, 5);
    }

    #[tokio::test]
    async fn reset_clears_snapshot_after_auth() {
        let store = RuntimeConfigStore::for_test(Some("secret"), false);
        {
            let mut state = store.inner.write().await;
            state.snapshot_version = 9;
            state.entries.insert("MAX_JOBS".to_owned(), json!(3));
        }
        let app = router_with_store(store.clone());
        let request = Request::builder()
            .method("POST")
            .uri(RESET_ROUTE_PATH)
            .header("x-server-auth", "secret")
            .body(Body::empty())
            .expect("request");
        let response = app.oneshot(request).await.expect("response");
        assert_eq!(response.status(), StatusCode::OK);
        let state = store.inner.read().await;
        assert_eq!(state.snapshot_version, 0);
        assert!(state.entries.is_empty());
    }
}
