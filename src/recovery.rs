//! Authenticated, deny-only production GitHub run observation route.
//! Never dispatches, mirrors source, or issues provider diagnostics.
//! Persistence failures are hard denials; normal build persistence remains separate.

use axum::{
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};

use crate::{
    admission::github_api::{inspect_authenticated, ObserveError, RunIdentity},
    config::first_env,
    db, http,
    recovery_policy::{decide, AttemptKey},
    state::AppState,
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ObservationRequest {
    repo: String,
    run_id: u64,
    attempt: u64,
    head_sha: String,
}

fn error(status: StatusCode, message: &'static str) -> Response {
    (
        status,
        Json(json!({ "error": message, "fallbackAllowed": false })),
    )
        .into_response()
}

pub(crate) async fn observe(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Err(response) = http::require_auth(&headers, &state) {
        return response;
    }
    let request: ObservationRequest = match serde_json::from_slice(&body) {
        Ok(value) => value,
        Err(_) => return error(StatusCode::BAD_REQUEST, "invalid observation request"),
    };
    let Some(key) = AttemptKey::new(
        &request.repo,
        request.run_id,
        request.attempt,
        &request.head_sha,
    ) else {
        return error(StatusCode::BAD_REQUEST, "invalid GitHub run identity");
    };

    let Some(database) = state.db.as_ref() else {
        return error(
            StatusCode::SERVICE_UNAVAILABLE,
            "recovery persistence unavailable",
        );
    };
    // Require a dedicated read-scoped token, never GH_PAT or a clone token.
    let Some(token) = first_env(&["BUILD_SERVER_GH_RECOVERY_READ_TOKEN"]) else {
        return error(
            StatusCode::SERVICE_UNAVAILABLE,
            "recovery observer disabled",
        );
    };
    let identity = RunIdentity {
        repo: key.repo.clone(),
        run_id: key.run_id,
        attempt: key.attempt,
        head_sha: key.head_sha.clone(),
    };
    let receipt = match inspect_authenticated(&token, &identity).await {
        Ok(receipt) => receipt,
        Err(ObserveError::InvalidIdentity) => {
            return error(StatusCode::BAD_REQUEST, "invalid GitHub run identity");
        }
        Err(_) => {
            return error(
                StatusCode::SERVICE_UNAVAILABLE,
                "provider observation incomplete",
            );
        }
    };
    let denial = decide(
        receipt.steps_observed,
        receipt.runner_assigned,
        receipt.classification.fallback_allowed,
    );

    let digest = Sha256::digest(key.canonical_identity().as_bytes());
    let delivery_id = format!("v1-{}", hex::encode(digest));
    let action = format!(
        "denied:{}:jobs={}:steps={}:assigned={}",
        denial.as_str(),
        receipt.jobs_observed,
        receipt.steps_observed,
        receipt.runner_assigned,
    );
    let inserted =
        match db::record_recovery_denial(database, &delivery_id, &key.repo, &key.head_sha, &action)
            .await
        {
            Ok(value) => value,
            Err(_) => {
                return error(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "recovery audit unavailable",
                )
            }
        };
    (
        if inserted {
            StatusCode::ACCEPTED
        } else {
            StatusCode::OK
        },
        Json(json!({
            "action": if inserted { "denied_recorded" } else { "duplicate_denied" },
            "fallbackAllowed": false,
            "classification": receipt.classification.kind.as_str(),
            "reason": denial.as_str(),
            "jobsObserved": receipt.jobs_observed,
            "stepsObserved": receipt.steps_observed,
            "runnerAssigned": receipt.runner_assigned,
            "runId": receipt.identity.run_id,
            "attempt": receipt.identity.attempt
        })),
    )
        .into_response()
}
