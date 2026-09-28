//! Optional build executor backed by the Scintilla management API.
//!
//! Jobs are validated by the build server before reaching this module. The
//! executor sends only the structured build-server.v1 request to an operator-
//! provisioned Scintilla function. It never forwards shell/argv from clients.
//!
//! Required configuration:
//! - BUILD_SERVER_SCINTILLA_ENABLED=true
//! - BUILD_SERVER_SCINTILLA_API_URL
//! - BUILD_SERVER_SCINTILLA_FUNCTION_ID
//! - BUILD_SERVER_SCINTILLA_AUTH_TOKEN

use serde_json::json;
use std::path::Path;
use uuid::Uuid;

use crate::{append_log, AppState, BuildJobRecord};

const MAX_RESPONSE_BYTES: usize = 1024 * 1024;

pub async fn execute(
    state: &AppState,
    job: &BuildJobRecord,
    log_path: &Path,
) -> Result<(), String> {
    let config = &state.config;
    if !config.scintilla_executor_enabled {
        return Err(
            "Scintilla executor is disabled by BUILD_SERVER_SCINTILLA_ENABLED=false".to_string(),
        );
    }
    let function_id = config
        .scintilla_function_id
        .as_deref()
        .ok_or_else(|| "BUILD_SERVER_SCINTILLA_FUNCTION_ID is not configured".to_string())?;
    let function_id = Uuid::parse_str(function_id)
        .map_err(|_| "BUILD_SERVER_SCINTILLA_FUNCTION_ID must be a UUID".to_string())?;
    let token = config
        .scintilla_auth_token
        .as_deref()
        .ok_or_else(|| "BUILD_SERVER_SCINTILLA_AUTH_TOKEN is not configured".to_string())?;

    let base = validate_scintilla_api_url(&config.scintilla_api_url)?;
    let url = base
        .join(&format!("api/v1/functions/{function_id}/invoke"))
        .map_err(|_| "failed to construct Scintilla invocation URL".to_string())?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(config.job_deadline)
        .build()
        .map_err(|_| "failed to build Scintilla HTTP client".to_string())?;

    append_log(
        log_path,
        &format!(
            "dispatching job {} to Scintilla function {function_id}\n",
            job.id
        ),
        config.max_log_bytes,
    )
    .await;

    let payload = json!({
        "schemaVersion": "build-server.v1",
        "jobId": job.id,
        "request": job.request,
        "fencingToken": job.fencing_token,
        "execution": {
            "provider": "gha-indie-worker",
            "ephemeral": true,
            "reuseWorkspace": false
        }
    });

    let response = client
        .post(url)
        .bearer_auth(token)
        .json(&payload)
        .send()
        .await
        .map_err(|error| format!("Scintilla request failed: {error}"))?;

    let status = response.status();
    let body = read_bounded_json(response, MAX_RESPONSE_BYTES).await?;

    if let Some(output) = body.get("output").and_then(serde_json::Value::as_str) {
        if !output.is_empty() {
            append_log(
                log_path,
                &format!("Scintilla output:\n{output}\n"),
                config.max_log_bytes,
            )
            .await;
        }
    }

    let ok = body.get("ok").and_then(serde_json::Value::as_bool) == Some(true);
    if status.is_success() && ok {
        return Ok(());
    }

    let detail = body
        .get("error")
        .or_else(|| body.get("message"))
        .map(ToString::to_string)
        .unwrap_or_else(|| format!("HTTP {}", status.as_u16()));
    Err(format!("Scintilla build failed: {detail}"))
}


fn validate_scintilla_api_url(value: &str) -> Result<reqwest::Url, String> {
    let mut url = reqwest::Url::parse(value)
        .map_err(|_| "BUILD_SERVER_SCINTILLA_API_URL must be a valid URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(
            "BUILD_SERVER_SCINTILLA_API_URL must be a credential-free HTTP(S) origin".to_string(),
        );
    }
    let host = url.host_str().unwrap_or_default();
    let private_http = matches!(host, "127.0.0.1" | "::1")
        || host.ends_with(".svc")
        || host.ends_with(".svc.cluster.local");
    if url.scheme() == "http" && !private_http {
        return Err(
            "BUILD_SERVER_SCINTILLA_API_URL requires HTTPS outside literal loopback or cluster-local DNS"
                .to_string(),
        );
    }
    url.set_path("/");
    Ok(url)
}

async fn read_bounded_json(
    mut response: reqwest::Response,
    max_bytes: usize,
) -> Result<serde_json::Value, String> {
    if response
        .content_length()
        .is_some_and(|length| length > max_bytes as u64)
    {
        return Err(format!("Scintilla response exceeded {max_bytes} bytes"));
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "Scintilla response read failed".to_string())?
    {
        if bytes.len().saturating_add(chunk.len()) > max_bytes {
            return Err(format!("Scintilla response exceeded {max_bytes} bytes"));
        }
        bytes.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&bytes).map_err(|_| "Scintilla returned non-JSON".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_scintilla_api_origins() {
        assert!(validate_scintilla_api_url("http://127.0.0.1:8080").is_ok());
        assert!(
            validate_scintilla_api_url(
                "http://scintilla-api-server.scintilla.svc.cluster.local:8080"
            )
            .is_ok()
        );
        assert!(validate_scintilla_api_url("https://scintilla.example.com").is_ok());
        assert!(validate_scintilla_api_url("http://scintilla.example.com").is_err());
        assert!(validate_scintilla_api_url("https://user:pass@scintilla.example.com").is_err());
        assert!(validate_scintilla_api_url("https://scintilla.example.com?token=x").is_err());
    }

    #[test]
    fn response_limit_is_one_mib() {
        assert_eq!(MAX_RESPONSE_BYTES, 1024 * 1024);
    }
}
