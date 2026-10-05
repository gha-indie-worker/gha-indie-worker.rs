use std::{
    collections::HashMap,
    env,
    error::Error,
    net::SocketAddr,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    body::{Body, Bytes},
    extract::{DefaultBodyLimit, MatchedPath, Path, State},
    http::{HeaderMap, Request, StatusCode},
    response::IntoResponse,
    routing::{get, post},
};
use base64::{
    Engine as _,
    engine::general_purpose::{URL_SAFE, URL_SAFE_NO_PAD},
};
use flags2env::BundledFlags2Env;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::{PgPool, postgres::PgPoolOptions};
use tower_http::trace::TraceLayer;

const CLI_CONTRACT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/.cli-flags.toml");
const PRIVATE_INGRESS_HEADERS: [&str; 3] = [
    "x-ores-ingress-routing-key",
    "x-ores-ingress-timestamp",
    "x-ores-ingress-signature",
];
const MAX_PREVIOUS_INGRESS_HMAC_OVERLAP_SECONDS: i64 = 24 * 60 * 60;
type HmacSha256 = Hmac<Sha256>;

#[derive(Clone)]
struct IngressHmacKey {
    key: Vec<u8>,
    valid_until: Option<i64>,
}

impl IngressHmacKey {
    fn active_at(&self, now: i64) -> bool {
        self.valid_until.is_none_or(|valid_until| now <= valid_until)
    }
}

#[derive(Clone)]
struct AppState {
    ingress_hmac_keys: Arc<Vec<IngressHmacKey>>,
    ingress_replay_window_seconds: u64,
    pool: PgPool,
}

#[derive(Debug)]
struct Config {
    listen_addr: SocketAddr,
    max_body_bytes: usize,
    ingress_replay_window_seconds: u64,
    database_url: String,
    database_max_connections: u32,
    database_acquire_timeout_seconds: u64,
    ingress_hmac_keys: Vec<IngressHmacKey>,
}

struct PrivateHopProof<'a> {
    provider: &'a str,
    routing_key: &'a str,
    timestamp: i64,
    encoded_signature: &'a str,
    body: &'a [u8],
}

#[derive(Debug, Deserialize)]
struct PubSubEnvelope {
    message: PubSubMessage,
    #[serde(default)]
    subscription: Option<String>,
}

#[derive(Debug, Deserialize)]
struct PubSubMessage {
    data: String,
    #[serde(rename = "messageId")]
    message_id: String,
    #[serde(rename = "publishTime", default)]
    publish_time: Option<String>,
    #[serde(default)]
    attributes: HashMap<String, String>,
}

#[derive(Debug, Deserialize, Serialize)]
struct GmailNotice {
    #[serde(rename = "emailAddress")]
    email_address: String,
    #[serde(rename = "historyId")]
    history_id: String,
}

fn main() -> Result<(), Box<dyn Error>> {
    // flags-2-env admission must happen before the Tokio runtime creates worker
    // threads. Secrets remain environment-only and are never CLI flags.
    apply_flags()?;
    let config = Config::from_env()?;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async_main(config))
}

fn apply_flags() -> Result<(), Box<dyn Error>> {
    let parser = BundledFlags2Env::new();
    parser
        .audit_config(Some(CLI_CONTRACT))
        .map_err(|_| "reviewed flags contract audit failed")?;

    let argv = env::args().collect::<Vec<_>>();
    let parsed = parser
        .parse_structured(&argv, Some(CLI_CONTRACT))
        .map_err(|_| "flags parsing failed")?;

    if !parsed.unknown_options.is_empty() || !parsed.errors.is_empty() || !parsed.extras.is_empty()
    {
        return Err(format!(
            "invalid CLI arguments: unknown={}, errors={}, positionals={}",
            parsed.unknown_options.len(),
            parsed.errors.len(),
            parsed.extras.len()
        )
        .into());
    }

    for (key, value) in parsed.provided_flags {
        // SAFETY: this runs before the Tokio runtime or any worker threads exist.
        unsafe { env::set_var(key, value) };
    }
    Ok(())
}

impl Config {
    fn from_env() -> Result<Self, Box<dyn Error>> {
        let listen_addr = read_env("ORES_ZEN_LISTEN_ADDR", "0.0.0.0:8080")?.parse()?;
        let max_body_bytes = read_env("ORES_ZEN_MAX_BODY_BYTES", "1048576")?.parse::<usize>()?;
        let ingress_replay_window_seconds =
            read_env("ORES_ZEN_INGRESS_REPLAY_WINDOW_SECONDS", "90")?.parse::<u64>()?;
        let database_max_connections =
            read_env("ORES_ZEN_DATABASE_MAX_CONNECTIONS", "10")?.parse::<u32>()?;
        let database_acquire_timeout_seconds =
            read_env("ORES_ZEN_DATABASE_ACQUIRE_TIMEOUT_SECONDS", "5")?.parse::<u64>()?;

        if !(16 * 1024..=16 * 1024 * 1024).contains(&max_body_bytes) {
            return Err("ORES_ZEN_MAX_BODY_BYTES must be between 16384 and 16777216".into());
        }
        if !(15..=300).contains(&ingress_replay_window_seconds) {
            return Err("ORES_ZEN_INGRESS_REPLAY_WINDOW_SECONDS must be between 15 and 300".into());
        }
        if !(1..=100).contains(&database_max_connections) {
            return Err("ORES_ZEN_DATABASE_MAX_CONNECTIONS must be between 1 and 100".into());
        }
        if !(1..=60).contains(&database_acquire_timeout_seconds) {
            return Err(
                "ORES_ZEN_DATABASE_ACQUIRE_TIMEOUT_SECONDS must be between 1 and 60".into(),
            );
        }

        let database_url = required_secret("ORES_ZEN_DATABASE_URL")?;
        let current_key = required_secret("ORES_ZEN_INGRESS_HMAC_KEY")?;
        let previous_key = optional_env("ORES_ZEN_INGRESS_HMAC_PREVIOUS_KEY")?;
        let previous_valid_until =
            optional_env("ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX")?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "system clock is before the Unix epoch")?;
        let now = i64::try_from(now.as_secs()).map_err(|_| "system clock exceeds i64 range")?;
        let ingress_hmac_keys =
            build_ingress_hmac_keys(current_key, previous_key, previous_valid_until, now)?;

        Ok(Self {
            listen_addr,
            max_body_bytes,
            ingress_replay_window_seconds,
            database_url,
            database_max_connections,
            database_acquire_timeout_seconds,
            ingress_hmac_keys,
        })
    }
}

fn build_ingress_hmac_keys(
    current_key: String,
    previous_key: Option<String>,
    previous_valid_until: Option<String>,
    now: i64,
) -> Result<Vec<IngressHmacKey>, Box<dyn Error>> {
    if current_key.len() < 32 {
        return Err("ORES_ZEN_INGRESS_HMAC_KEY must contain at least 32 bytes of entropy".into());
    }

    let current = IngressHmacKey {
        key: current_key.into_bytes(),
        valid_until: None,
    };

    let previous = match (previous_key, previous_valid_until) {
        (None, None) => None,
        (Some(key), Some(valid_until)) => {
            if key.len() < 32 {
                return Err(
                    "ORES_ZEN_INGRESS_HMAC_PREVIOUS_KEY must contain at least 32 bytes".into(),
                );
            }
            if key.as_bytes() == current.key.as_slice() {
                return Err(
                    "ORES_ZEN_INGRESS_HMAC_PREVIOUS_KEY must differ from the current key".into(),
                );
            }
            let valid_until = valid_until
                .parse::<i64>()
                .map_err(|_| "ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX must be an integer")?;
            if valid_until <= now {
                return Err(
                    "ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX must be in the future".into(),
                );
            }
            let overlap = valid_until
                .checked_sub(now)
                .ok_or("previous HMAC key expiry is before the current time")?;
            if overlap > MAX_PREVIOUS_INGRESS_HMAC_OVERLAP_SECONDS {
                return Err(format!(
                    "ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX may be at most {} seconds in the future",
                    MAX_PREVIOUS_INGRESS_HMAC_OVERLAP_SECONDS
                )
                .into());
            }
            Some(IngressHmacKey {
                key: key.into_bytes(),
                valid_until: Some(valid_until),
            })
        }
        (Some(_), None) => {
            return Err(
                "ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX is required when ORES_ZEN_INGRESS_HMAC_PREVIOUS_KEY is set"
                    .into(),
            );
        }
        (None, Some(_)) => {
            return Err(
                "ORES_ZEN_INGRESS_HMAC_PREVIOUS_KEY is required when ORES_ZEN_INGRESS_HMAC_PREVIOUS_VALID_UNTIL_UNIX is set"
                    .into(),
            );
        }
    };

    let mut keys = vec![current];
    if let Some(previous) = previous {
        keys.push(previous);
    }
    Ok(keys)
}

fn optional_env(key: &str) -> Result<Option<String>, Box<dyn Error>> {
    match env::var(key) {
        Ok(value) if !value.trim().is_empty() => Ok(Some(value)),
        Ok(_) | Err(env::VarError::NotPresent) => Ok(None),
        Err(err) => Err(format!("{key} is not valid Unicode: {err}").into()),
    }
}

fn read_env(key: &str, default: &str) -> Result<String, Box<dyn Error>> {
    Ok(match env::var(key) {
        Ok(value) if !value.trim().is_empty() => value,
        Ok(_) => return Err(format!("{key} cannot be empty").into()),
        Err(env::VarError::NotPresent) => default.to_string(),
        Err(err) => return Err(format!("{key} is not valid Unicode: {err}").into()),
    })
}

fn required_secret(key: &str) -> Result<String, Box<dyn Error>> {
    match env::var(key) {
        Ok(value) if !value.is_empty() => Ok(value),
        Ok(_) | Err(env::VarError::NotPresent) => {
            Err(format!("required secret {key} is missing").into())
        }
        Err(env::VarError::NotUnicode(_)) => {
            Err(format!("required secret {key} is not valid Unicode").into())
        }
    }
}

async fn async_main(config: Config) -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "ores_zen_api_server=info,tower_http=info".into()),
        )
        .init();

    let pool = PgPoolOptions::new()
        .max_connections(config.database_max_connections)
        .acquire_timeout(Duration::from_secs(config.database_acquire_timeout_seconds))
        .connect(&config.database_url)
        .await?;

    // Refuse to accept provider callbacks until the durable ingress spool exists.
    sqlx::query_scalar::<_, i32>("SELECT 1 FROM ingress_spool LIMIT 1")
        .fetch_optional(&pool)
        .await?;

    let state = AppState {
        ingress_hmac_keys: Arc::new(config.ingress_hmac_keys),
        ingress_replay_window_seconds: config.ingress_replay_window_seconds,
        pool,
    };

    let trace = TraceLayer::new_for_http().make_span_with(|request: &Request<Body>| {
        let matched_path = request
            .extensions()
            .get::<MatchedPath>()
            .map(MatchedPath::as_str)
            .unwrap_or("unmatched");
        tracing::info_span!(
            "http_request",
            method = %request.method(),
            route = matched_path,
        )
    });

    let app = Router::new()
        .route("/healthz", get(|| async { StatusCode::NO_CONTENT }))
        .route("/readyz", get(readyz))
        .route("/webhooks/gmail/pubsub", post(gmail_pubsub))
        .route("/webhooks/zendesk/{integration_key}", post(zendesk_webhook))
        .route("/webhooks/slack/events", post(slack_events))
        .with_state(state)
        .layer(DefaultBodyLimit::max(config.max_body_bytes))
        .layer(trace);

    let listener = tokio::net::TcpListener::bind(config.listen_addr).await?;
    tracing::info!(addr = %config.listen_addr, "ores-zen api server listening");
    axum::serve(listener, app).await?;
    Ok(())
}

async fn readyz(State(state): State<AppState>) -> StatusCode {
    match sqlx::query_scalar::<_, i32>("SELECT 1")
        .fetch_one(&state.pool)
        .await
    {
        Ok(1) => StatusCode::NO_CONTENT,
        _ => StatusCode::SERVICE_UNAVAILABLE,
    }
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name)?.to_str().ok()
}

fn single_header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let mut values = headers.get_all(name).iter();
    let value = values.next()?.to_str().ok()?;
    if values.next().is_some() {
        return None;
    }
    Some(value)
}

fn canonical_private_headers_only(headers: &HeaderMap) -> bool {
    headers.keys().all(|name| {
        let name = name.as_str();
        !name.starts_with("x-ores-") || PRIVATE_INGRESS_HEADERS.contains(&name)
    })
}

fn verify_private_hop(
    proof: PrivateHopProof<'_>,
    keys: &[IngressHmacKey],
    replay_window_seconds: u64,
    now: i64,
) -> bool {
    if now.abs_diff(proof.timestamp) > replay_window_seconds {
        return false;
    }

    let signature = match URL_SAFE_NO_PAD.decode(proof.encoded_signature.as_bytes()) {
        Ok(value) => value,
        Err(_) => return false,
    };
    let canonical = format!(
        "v2\n{}\n{}\n{}\n{}",
        proof.provider,
        proof.routing_key,
        proof.timestamp,
        digest_hex(proof.body)
    );

    keys.iter().filter(|key| key.active_at(now)).any(|key| {
        let Ok(mut mac) = HmacSha256::new_from_slice(&key.key) else {
            return false;
        };
        mac.update(canonical.as_bytes());
        mac.verify_slice(&signature).is_ok()
    })
}

fn admitted(
    provider: &str,
    expected_routing_key: &str,
    headers: &HeaderMap,
    body: &[u8],
    state: &AppState,
) -> bool {
    // The public verifier MUST authenticate the provider first, strip every
    // caller-supplied x-ores-* header, derive the routing identity itself, and
    // then mint this route-bound private assertion. The API independently
    // requires a canonical, non-duplicated internal header set as defense in
    // depth against header smuggling inside the private trust boundary.
    if !canonical_private_headers_only(headers) || !valid_bounded(expected_routing_key, 320) {
        return false;
    }
    let signed_routing_key = match single_header_str(headers, "x-ores-ingress-routing-key")
        .filter(|value| valid_bounded(value, 320))
    {
        Some(value) if value == expected_routing_key => value,
        _ => return false,
    };
    let timestamp = match single_header_str(headers, "x-ores-ingress-timestamp")
        .and_then(|value| value.parse::<i64>().ok())
    {
        Some(value) => value,
        None => return false,
    };
    let now = match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(value) => value.as_secs() as i64,
        Err(_) => return false,
    };
    let encoded_signature = match single_header_str(headers, "x-ores-ingress-signature")
        .and_then(|value| value.strip_prefix("v2="))
    {
        Some(value) => value,
        None => return false,
    };

    verify_private_hop(
        PrivateHopProof {
            provider,
            routing_key: signed_routing_key,
            timestamp,
            encoded_signature,
            body,
        },
        state.ingress_hmac_keys.as_slice(),
        state.ingress_replay_window_seconds,
        now,
    )
}

fn digest_hex(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn valid_bounded(value: &str, max_len: usize) -> bool {
    !value.trim().is_empty() && value.len() <= max_len && !value.chars().any(char::is_control)
}

fn normalize_mailbox(value: &str) -> Option<String> {
    let value = value.trim().to_ascii_lowercase();
    if valid_bounded(&value, 320) && value.contains('@') {
        Some(value)
    } else {
        None
    }
}

fn valid_integration_key(value: &str) -> bool {
    (8..=128).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

async fn persist_ingress(
    state: &AppState,
    provider: &str,
    routing_key: &str,
    provider_event_id: &str,
    payload_digest_sha256: &str,
    payload: &Value,
) -> Result<(), StatusCode> {
    // One statement returns the stored digest whether this is a new insert or a
    // retry. Reuse of the same provider event id with different bytes is treated
    // as a collision/tampering condition rather than silently accepted.
    let stored_digest = sqlx::query_scalar::<_, String>(
        r#"
        WITH inserted AS (
          INSERT INTO ingress_spool (
            provider,
            routing_key,
            provider_event_id,
            payload_digest_sha256,
            content_type,
            payload
          )
          VALUES ($1, $2, $3, $4, 'application/json', $5)
          ON CONFLICT (provider, routing_key, provider_event_id) DO NOTHING
          RETURNING payload_digest_sha256
        )
        SELECT payload_digest_sha256 FROM inserted
        UNION ALL
        SELECT payload_digest_sha256
          FROM ingress_spool
         WHERE provider = $1
           AND routing_key = $2
           AND provider_event_id = $3
        LIMIT 1
        "#,
    )
    .bind(provider)
    .bind(routing_key)
    .bind(provider_event_id)
    .bind(payload_digest_sha256)
    .bind(payload)
    .fetch_one(&state.pool)
    .await
    .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;

    if stored_digest != payload_digest_sha256 {
        tracing::error!(
            provider,
            "provider event id reused with a different payload digest"
        );
        return Err(StatusCode::CONFLICT);
    }
    Ok(())
}

fn decode_gmail_notice(data: &str) -> Option<GmailNotice> {
    let decoded = URL_SAFE_NO_PAD
        .decode(data.as_bytes())
        .or_else(|_| URL_SAFE.decode(data.as_bytes()))
        .ok()?;
    let notice: GmailNotice = serde_json::from_slice(&decoded).ok()?;
    if normalize_mailbox(&notice.email_address).is_none()
        || notice.history_id.is_empty()
        || notice.history_id.len() > 32
        || !notice.history_id.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }
    Some(notice)
}

async fn gmail_pubsub(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    let envelope: PubSubEnvelope = match serde_json::from_slice(&body) {
        Ok(value) => value,
        Err(_) => return StatusCode::BAD_REQUEST,
    };
    if !valid_bounded(&envelope.message.message_id, 256) {
        return StatusCode::BAD_REQUEST;
    }
    let mut notice = match decode_gmail_notice(&envelope.message.data) {
        Some(value) => value,
        None => return StatusCode::BAD_REQUEST,
    };
    notice.email_address = match normalize_mailbox(&notice.email_address) {
        Some(value) => value,
        None => return StatusCode::BAD_REQUEST,
    };

    let routing_key = notice.email_address.clone();
    if !admitted("gmail", &routing_key, &headers, &body, &state) {
        return StatusCode::UNAUTHORIZED;
    }

    let payload = json!({
        "notice": notice,
        "subscription": envelope.subscription,
        "publish_time": envelope.message.publish_time,
        "attributes": envelope.message.attributes,
    });
    let digest = digest_hex(&body);

    match persist_ingress(
        &state,
        "gmail",
        &routing_key,
        &envelope.message.message_id,
        &digest,
        &payload,
    )
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(code) => code,
    }
}

async fn zendesk_webhook(
    State(state): State<AppState>,
    Path(integration_key): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    if !valid_integration_key(&integration_key) {
        return StatusCode::NOT_FOUND;
    }
    if !admitted("zendesk", &integration_key, &headers, &body, &state) {
        return StatusCode::UNAUTHORIZED;
    }

    let payload: Value = match serde_json::from_slice(&body) {
        Ok(value) => value,
        Err(_) => return StatusCode::BAD_REQUEST,
    };
    let provider_event_id = header_str(&headers, "x-zendesk-webhook-id")
        .filter(|value| valid_bounded(value, 256))
        .map(str::to_owned)
        .unwrap_or_else(|| digest_hex(&body));
    let digest = digest_hex(&body);

    match persist_ingress(
        &state,
        "zendesk",
        &integration_key,
        &provider_event_id,
        &digest,
        &payload,
    )
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(code) => code,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SlackRoutingIdentity {
    route_kind: &'static str,
    route_key: String,
}

fn slack_routing_identities(payload: &Value) -> Vec<SlackRoutingIdentity> {
    [
        ("team_id", "slack_team"),
        ("enterprise_id", "slack_enterprise"),
    ]
    .into_iter()
    .filter_map(|(field, route_kind)| {
        payload
            .get(field)
            .and_then(Value::as_str)
            .filter(|value| valid_bounded(value, 128))
            .map(|value| SlackRoutingIdentity {
                route_kind,
                route_key: value.to_owned(),
            })
    })
    .collect()
}

async fn resolve_slack_route(
    state: &AppState,
    identities: &[SlackRoutingIdentity],
    signed_routing_key: &str,
) -> Result<Option<SlackRoutingIdentity>, StatusCode> {
    let signed_matches = identities
        .iter()
        .filter(|identity| identity.route_key == signed_routing_key)
        .count();
    if signed_matches != 1 {
        return Ok(None);
    }

    let mut resolved_owner: Option<(String, String)> = None;
    let mut signed_identity: Option<SlackRoutingIdentity> = None;

    for identity in identities {
        let route = sqlx::query_as::<_, (String, String)>(
            r#"
            SELECT tenant_id::text, integration_id::text
              FROM integration_routes
             WHERE provider = 'slack'
               AND route_kind = $1
               AND route_key = $2
               AND enabled
             LIMIT 1
            "#,
        )
        .bind(identity.route_kind)
        .bind(&identity.route_key)
        .fetch_optional(&state.pool)
        .await
        .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;

        let Some(owner) = route else {
            continue;
        };

        if let Some(existing) = &resolved_owner {
            if existing != &owner {
                tracing::warn!(
                    route_kind = identity.route_kind,
                    "Slack payload identities resolve to different tenant/integration authorities"
                );
                return Ok(None);
            }
        } else {
            resolved_owner = Some(owner);
        }

        if identity.route_key == signed_routing_key {
            signed_identity = Some(identity.clone());
        }
    }

    Ok(signed_identity)
}

async fn slack_events(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> impl IntoResponse {
    let payload: Value = match serde_json::from_slice(&body) {
        Ok(value) => value,
        Err(_) => {
            return (StatusCode::BAD_REQUEST, Json(json!({"ok": false}))).into_response();
        }
    };
    let identities = slack_routing_identities(&payload);
    if identities.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({"ok": false}))).into_response();
    }
    let signed_routing_key = match single_header_str(&headers, "x-ores-ingress-routing-key")
        .filter(|value| valid_bounded(value, 128))
    {
        Some(value) => value,
        None => return (StatusCode::UNAUTHORIZED, Json(json!({"ok": false}))).into_response(),
    };
    let routing_identity = match resolve_slack_route(&state, &identities, signed_routing_key).await
    {
        Ok(Some(value)) => value,
        Ok(None) => {
            return (StatusCode::UNAUTHORIZED, Json(json!({"ok": false}))).into_response();
        }
        Err(code) => return (code, Json(json!({"ok": false}))).into_response(),
    };
    let routing_key = routing_identity.route_key;
    if !admitted("slack", &routing_key, &headers, &body, &state) {
        return (StatusCode::UNAUTHORIZED, Json(json!({"ok": false}))).into_response();
    }

    if payload.get("type").and_then(Value::as_str) == Some("url_verification") {
        let challenge = payload
            .get("challenge")
            .and_then(Value::as_str)
            .filter(|value| valid_bounded(value, 512));
        return match challenge {
            Some(value) => (StatusCode::OK, Json(json!({"challenge": value}))).into_response(),
            None => (StatusCode::BAD_REQUEST, Json(json!({"ok": false}))).into_response(),
        };
    }

    let provider_event_id = match payload
        .get("event_id")
        .and_then(Value::as_str)
        .filter(|value| valid_bounded(value, 256))
    {
        Some(value) => value.to_owned(),
        None => return (StatusCode::BAD_REQUEST, Json(json!({"ok": false}))).into_response(),
    };
    let digest = digest_hex(&body);

    match persist_ingress(
        &state,
        "slack",
        &routing_key,
        &provider_event_id,
        &digest,
        &payload,
    )
    .await
    {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(code) => (code, Json(json!({"ok": false}))).into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    fn sign(provider: &str, routing_key: &str, timestamp: i64, body: &[u8], key: &[u8]) -> String {
        let canonical = format!(
            "v2\n{provider}\n{routing_key}\n{timestamp}\n{}",
            digest_hex(body)
        );
        let mut mac = HmacSha256::new_from_slice(key).expect("valid test key");
        mac.update(canonical.as_bytes());
        URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    }

    #[test]
    fn private_hop_signature_binds_provider_route_body_and_timestamp() {
        let key = b"0123456789abcdef0123456789abcdef".to_vec();
        let body = br#"{"event":"x"}"#;
        let timestamp = 1_800_000_000_i64;
        let routing_key = "tenant_123-zd";
        let signature = sign("zendesk", routing_key, timestamp, body, &key);
        let keys = vec![IngressHmacKey {
            key,
            valid_until: None,
        }];

        assert!(verify_private_hop(
            PrivateHopProof {
                provider: "zendesk",
                routing_key,
                timestamp,
                encoded_signature: &signature,
                body,
            },
            &keys,
            90,
            timestamp + 30,
        ));
        assert!(!verify_private_hop(
            PrivateHopProof {
                provider: "slack",
                routing_key,
                timestamp,
                encoded_signature: &signature,
                body,
            },
            &keys,
            90,
            timestamp + 30,
        ));
        assert!(!verify_private_hop(
            PrivateHopProof {
                provider: "zendesk",
                routing_key: "tenant_456-zd",
                timestamp,
                encoded_signature: &signature,
                body,
            },
            &keys,
            90,
            timestamp + 30,
        ));
        assert!(!verify_private_hop(
            PrivateHopProof {
                provider: "zendesk",
                routing_key,
                timestamp,
                encoded_signature: &signature,
                body: br#"{"event":"changed"}"#,
            },
            &keys,
            90,
            timestamp + 30,
        ));
        assert!(!verify_private_hop(
            PrivateHopProof {
                provider: "zendesk",
                routing_key,
                timestamp,
                encoded_signature: &signature,
                body,
            },
            &keys,
            90,
            timestamp + 91,
        ));
    }

    #[test]
    fn previous_private_hop_key_is_accepted_only_until_its_expiry() {
        let current = b"current-current-current-current-01".to_vec();
        let previous = b"previous-previous-previous-prev-01".to_vec();
        let body = br#"{"event":"rotation"}"#;
        let timestamp = 1_800_000_000_i64;
        let routing_key = "tenant_123-zd";
        let signature = sign("zendesk", routing_key, timestamp, body, &previous);
        let keys = vec![
            IngressHmacKey {
                key: current,
                valid_until: None,
            },
            IngressHmacKey {
                key: previous,
                valid_until: Some(timestamp + 60),
            },
        ];
        let proof = PrivateHopProof {
            provider: "zendesk",
            routing_key,
            timestamp,
            encoded_signature: &signature,
            body,
        };

        assert!(verify_private_hop(proof, &keys, 300, timestamp + 60));

        let proof = PrivateHopProof {
            provider: "zendesk",
            routing_key,
            timestamp,
            encoded_signature: &signature,
            body,
        };
        assert!(!verify_private_hop(proof, &keys, 300, timestamp + 61));
    }

    #[test]
    fn private_hop_rotation_config_requires_a_complete_distinct_pair() {
        let current = "c".repeat(32);
        let previous = "p".repeat(32);

        let now = 1_800_000_000_i64;
        let keys = build_ingress_hmac_keys(
            current.clone(),
            Some(previous.clone()),
            Some("1800000060".to_owned()),
            now,
        )
        .expect("complete bounded rotation is valid");
        assert_eq!(keys.len(), 2);
        assert_eq!(keys[1].valid_until, Some(1_800_000_060));

        assert!(
            build_ingress_hmac_keys(current.clone(), Some(previous.clone()), None, now).is_err()
        );
        assert!(
            build_ingress_hmac_keys(current.clone(), None, Some("1800000060".to_owned()), now)
                .is_err()
        );
        assert!(
            build_ingress_hmac_keys(
                current.clone(),
                Some(current.clone()),
                Some("1800000060".to_owned()),
                now,
            )
            .is_err()
        );
        assert!(
            build_ingress_hmac_keys(
                current.clone(),
                Some(previous.clone()),
                Some("not-a-time".to_owned()),
                now,
            )
            .is_err()
        );
        assert!(
            build_ingress_hmac_keys(
                current.clone(),
                Some(previous.clone()),
                Some(now.to_string()),
                now,
            )
            .is_err()
        );
        assert!(
            build_ingress_hmac_keys(
                current,
                Some(previous),
                Some((now + MAX_PREVIOUS_INGRESS_HMAC_OVERLAP_SECONDS + 1).to_string()),
                now,
            )
            .is_err()
        );
    }

    #[test]
    fn canonical_private_headers_reject_duplicates_and_unknown_extensions() {
        let mut headers = HeaderMap::new();
        headers.insert(
            "x-ores-ingress-routing-key",
            HeaderValue::from_static("tenant_123-zd"),
        );
        headers.insert(
            "x-ores-ingress-timestamp",
            HeaderValue::from_static("1800000000"),
        );
        headers.insert(
            "x-ores-ingress-signature",
            HeaderValue::from_static("v2=abc"),
        );
        assert!(canonical_private_headers_only(&headers));
        assert_eq!(
            single_header_str(&headers, "x-ores-ingress-signature"),
            Some("v2=abc")
        );

        headers.append(
            "x-ores-ingress-signature",
            HeaderValue::from_static("v2=def"),
        );
        assert_eq!(
            single_header_str(&headers, "x-ores-ingress-signature"),
            None
        );

        headers.remove("x-ores-ingress-signature");
        headers.insert("x-ores-debug", HeaderValue::from_static("1"));
        assert!(!canonical_private_headers_only(&headers));
    }

    #[test]
    fn slack_routing_uses_workspace_or_enterprise_not_app_id() {
        let team = slack_routing_identities(&json!({"team_id": "T123", "api_app_id": "A123"}));
        assert_eq!(
            team,
            vec![SlackRoutingIdentity {
                route_kind: "slack_team",
                route_key: "T123".to_string(),
            }]
        );

        let enterprise =
            slack_routing_identities(&json!({"enterprise_id": "E123", "api_app_id": "A123"}));
        assert_eq!(
            enterprise,
            vec![SlackRoutingIdentity {
                route_kind: "slack_enterprise",
                route_key: "E123".to_string(),
            }]
        );

        assert!(slack_routing_identities(&json!({"api_app_id": "A123"})).is_empty());
    }

    #[test]
    fn slack_routing_preserves_both_authority_candidates_for_confusion_checks() {
        assert_eq!(
            slack_routing_identities(&json!({
                "team_id": "T123",
                "enterprise_id": "E123",
                "api_app_id": "A123"
            })),
            vec![
                SlackRoutingIdentity {
                    route_kind: "slack_team",
                    route_key: "T123".to_string(),
                },
                SlackRoutingIdentity {
                    route_kind: "slack_enterprise",
                    route_key: "E123".to_string(),
                },
            ]
        );
    }

    #[test]
    fn gmail_notice_uses_base64url_and_normalizes_mailbox() {
        let data = URL_SAFE_NO_PAD
            .encode(br#"{"emailAddress":"Support@Example.COM","historyId":"123456789"}"#);
        let notice = decode_gmail_notice(&data).expect("valid gmail notice");
        assert_eq!(
            normalize_mailbox(&notice.email_address).as_deref(),
            Some("support@example.com")
        );
        assert_eq!(notice.history_id, "123456789");
    }

    #[test]
    fn integration_keys_are_bounded_and_path_safe() {
        assert!(valid_integration_key("tenant_123-zd"));
        assert!(!valid_integration_key("short"));
        assert!(!valid_integration_key("../../secret"));
        assert!(!valid_integration_key("contains space"));
    }
}
