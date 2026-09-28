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

    let url = format!(
        "{}/api/v1/functions/{function_id}/invoke",
        config.scintilla_api_url.trim_end_matches('/')
    );

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

    let response = state
        .http
        .post(url)
        .bearer_auth(token)
        .timeout(config.job_deadline)
        .json(&payload)
        .send()
        .await
        .map_err(|error| format!("Scintilla request failed: {error}"))?;

    let status = response.status();
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("Scintilla response read failed: {error}"))?;
    if bytes.len() > MAX_RESPONSE_BYTES {
        return Err("Scintilla response exceeded 1 MiB".to_string());
    }

    let body: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|_| "Scintilla returned non-JSON".to_string())?;

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
