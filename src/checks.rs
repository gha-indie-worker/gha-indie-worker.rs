//! GitHub reporting for exact-head local CI.
//!
//! Reporting has two deliberately disjoint modes:
//! - `app-required`: only an App-owned Check Run under the authoritative
//!   context may carry evidence. Installation identity is resolved server-side
//!   for the exact repository and PAT fallback is forbidden.
//! - `advisory`: only a PAT Commit Status under a distinct advisory context is
//!   used. It must never masquerade as the authoritative context.
//!
//! The App private key and minted installation tokens never leave this process.
//! The only cached metadata is a bounded-TTL repository -> installation-id map.

use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::json;

use crate::config::{Config, ReportingMode};
use crate::state::{AppState, InstallationCacheEntry};

const GITHUB_API: &str = "https://api.github.com";
const API_VERSION: &str = "2022-11-28";
const USER_AGENT: &str = "gha-indie-worker";
const MAX_OUTPUT_TEXT: usize = 60_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CheckTarget {
    pub owner: String,
    pub repo: String,
    pub head_sha: String,
    /// Candidate installation from a signature-verified delivery. It is never
    /// trusted by itself and must match the repository installation resolved by
    /// the worker through the App API.
    pub installation_id: Option<u64>,
}

#[derive(Serialize)]
struct AppClaims {
    iat: i64,
    exp: i64,
    iss: String,
}

pub(crate) fn target_from_request(
    repo_url: &str,
    commit_sha: Option<&str>,
    installation_id: Option<u64>,
) -> Option<CheckTarget> {
    let head_sha =
        commit_sha.filter(|value| crate::validation::validate_commit_sha(value).is_ok())?;
    let rest = repo_url
        .strip_prefix("https://github.com/")
        .or_else(|| repo_url.strip_prefix("git@github.com:"))?;
    let rest = rest.strip_suffix(".git").unwrap_or(rest);
    let (owner, repo) = rest.split_once('/')?;
    if owner.is_empty() || repo.is_empty() || repo.contains('/') {
        return None;
    }
    Some(CheckTarget {
        owner: owner.to_string(),
        repo: repo.to_string(),
        head_sha: head_sha.to_string(),
        installation_id,
    })
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_secs() as i64)
        .unwrap_or_default()
}

fn unix_now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| u64::try_from(value.as_millis()).unwrap_or(u64::MAX))
        .unwrap_or_default()
}

fn app_jwt(app_id: &str, private_key_pem: &str) -> Result<String, String> {
    let now = unix_now();
    let claims = AppClaims {
        iat: now - 60,
        exp: now + 540,
        iss: app_id.to_string(),
    };
    let key = jsonwebtoken::EncodingKey::from_rsa_pem(private_key_pem.as_bytes())
        .map_err(|error| format!("app private key is not a usable RSA PEM: {error}"))?;
    jsonwebtoken::encode(
        &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::RS256),
        &claims,
        &key,
    )
    .map_err(|error| format!("failed to sign app JWT: {error}"))
}

fn cache_key(target: &CheckTarget) -> String {
    format!(
        "{}/{}",
        target.owner.to_ascii_lowercase(),
        target.repo.to_ascii_lowercase()
    )
}

fn bootstrap_installation_candidate(
    config: &Config,
    target: &CheckTarget,
) -> Result<Option<u64>, String> {
    if let Some(id) = target.installation_id {
        return Ok(Some(id));
    }
    let Some(raw) = config.github_app_installation_id.as_deref() else {
        return Ok(None);
    };
    raw.parse::<u64>()
        .ok()
        .filter(|id| *id > 0)
        .map(Some)
        .ok_or_else(|| "configured GitHub App installation id is invalid".to_string())
}

fn verify_installation_candidate(candidate: Option<u64>, resolved: u64) -> Result<u64, String> {
    if resolved == 0 {
        return Err("GitHub returned an invalid zero installation id".to_string());
    }
    if let Some(candidate) = candidate {
        if candidate != resolved {
            return Err(format!(
                "GitHub App installation mismatch for target repository: candidate {candidate}, resolved {resolved}"
            ));
        }
    }
    Ok(resolved)
}

pub(crate) fn checks_configured(config: &Config) -> bool {
    config.github_app_id.is_some() && config.github_app_private_key.is_some()
}

/// Resolve the App installation for this exact repository. A signed delivery's
/// installation id and the legacy configured id are merely candidates; both
/// must agree with GitHub's repository-scoped App API result.
pub(crate) async fn resolve_installation_id(
    state: &AppState,
    target: &CheckTarget,
) -> Result<u64, String> {
    let config = state.config.as_ref();
    let (Some(app_id), Some(pem)) = (
        config.github_app_id.as_deref(),
        config.github_app_private_key.as_deref(),
    ) else {
        return Err("GitHub App is not configured".to_string());
    };
    let candidate = bootstrap_installation_candidate(config, target)?;
    let key = cache_key(target);
    let now_ms = unix_now_ms();
    if let Some(cached) = state.installation_cache.read().await.get(&key).copied() {
        if cached.expires_at_ms > now_ms {
            return verify_installation_candidate(candidate, cached.installation_id);
        }
    }

    let jwt = app_jwt(app_id, pem)?;
    let response = state
        .http
        .get(format!(
            "{GITHUB_API}/repos/{}/{}/installation",
            target.owner, target.repo
        ))
        .header("authorization", format!("Bearer {jwt}"))
        .header("accept", "application/vnd.github+json")
        .header("x-github-api-version", API_VERSION)
        .header("user-agent", USER_AGENT)
        .send()
        .await
        .map_err(|_| "repository installation lookup failed".to_string())?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!(
            "repository installation lookup was refused with status {status}"
        ));
    }
    let body: serde_json::Value = response
        .json()
        .await
        .map_err(|_| "repository installation response was not valid JSON".to_string())?;
    let resolved = body
        .get("id")
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| "repository installation response had no numeric id".to_string())?;
    let resolved = verify_installation_candidate(candidate, resolved)?;
    let ttl_ms = u64::try_from(config.installation_cache_ttl.as_millis()).unwrap_or(u64::MAX);
    state.installation_cache.write().await.insert(
        key,
        InstallationCacheEntry {
            installation_id: resolved,
            expires_at_ms: now_ms.saturating_add(ttl_ms),
        },
    );
    Ok(resolved)
}

async fn installation_token(state: &AppState, target: &CheckTarget) -> Result<String, String> {
    let config = state.config.as_ref();
    let (Some(app_id), Some(pem)) = (
        config.github_app_id.as_deref(),
        config.github_app_private_key.as_deref(),
    ) else {
        return Err("GitHub App is not configured".to_string());
    };
    let installation = resolve_installation_id(state, target).await?;
    let jwt = app_jwt(app_id, pem)?;
    let response = state
        .http
        .post(format!(
            "{GITHUB_API}/app/installations/{installation}/access_tokens"
        ))
        .header("authorization", format!("Bearer {jwt}"))
        .header("accept", "application/vnd.github+json")
        .header("x-github-api-version", API_VERSION)
        .header("user-agent", USER_AGENT)
        .send()
        .await
        .map_err(|_| "installation token request failed".to_string())?;
    let status = response.status();
    let body: serde_json::Value = response
        .json()
        .await
        .map_err(|_| "installation token response was not JSON".to_string())?;
    if !status.is_success() {
        let message = body
            .get("message")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("unknown error");
        return Err(format!("installation token rejected ({status}): {message}"));
    }
    body.get("token")
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| "installation token response had no token".to_string())
}

fn iso_now() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}

pub(crate) async fn start_check_run(
    state: &AppState,
    target: &CheckTarget,
    job_id: &str,
) -> Option<u64> {
    if !checks_configured(state.config.as_ref()) {
        return None;
    }
    let token = match installation_token(state, target).await {
        Ok(token) => token,
        Err(error) => {
            tracing::error!("authoritative check run not started: {error}");
            return None;
        }
    };
    let name = state.config.check_run_name.clone();
    let body = json!({
        "name": name,
        "head_sha": target.head_sha,
        "status": "in_progress",
        "started_at": iso_now(),
        "external_id": format!("indiebuild:{job_id}@{}", target.head_sha),
        "output": {
            "title": "Running on the local worker",
            "summary": format!("Job `{job_id}` is verifying `{}`.", target.head_sha),
        }
    });
    let response = state
        .http
        .post(format!(
            "{GITHUB_API}/repos/{}/{}/check-runs",
            target.owner, target.repo
        ))
        .header("authorization", format!("Bearer {token}"))
        .header("accept", "application/vnd.github+json")
        .header("x-github-api-version", API_VERSION)
        .header("user-agent", USER_AGENT)
        .json(&body)
        .send()
        .await;
    match response {
        Ok(response) if response.status().is_success() => response
            .json::<serde_json::Value>()
            .await
            .ok()
            .and_then(|value| value.get("id").and_then(serde_json::Value::as_u64)),
        Ok(response) => {
            tracing::error!("authoritative check run creation refused: {}", response.status());
            None
        }
        Err(_) => {
            tracing::error!("authoritative check run creation failed");
            None
        }
    }
}

pub(crate) fn output_text(log: &str) -> String {
    if log.len() <= MAX_OUTPUT_TEXT {
        return log.to_string();
    }
    let mut start = log.len() - MAX_OUTPUT_TEXT;
    while !log.is_char_boundary(start) {
        start += 1;
    }
    format!("…log truncated…\n{}", &log[start..])
}

pub(crate) async fn finish_check_run(
    state: &AppState,
    target: &CheckTarget,
    check_run_id: u64,
    succeeded: bool,
    summary: &str,
    log_tail: &str,
) -> bool {
    let token = match installation_token(state, target).await {
        Ok(token) => token,
        Err(error) => {
            tracing::error!("authoritative check run not completed: {error}");
            return false;
        }
    };
    let body = json!({
        "status": "completed",
        "conclusion": if succeeded { "success" } else { "failure" },
        "completed_at": iso_now(),
        "output": {
            "title": if succeeded { "Passed" } else { "Failed" },
            "summary": summary,
            "text": format!("```\n{}\n```", output_text(log_tail)),
        }
    });
    let response = state
        .http
        .patch(format!(
            "{GITHUB_API}/repos/{}/{}/check-runs/{check_run_id}",
            target.owner, target.repo
        ))
        .header("authorization", format!("Bearer {token}"))
        .header("accept", "application/vnd.github+json")
        .header("x-github-api-version", API_VERSION)
        .header("user-agent", USER_AGENT)
        .json(&body)
        .send()
        .await;
    match response {
        Ok(response) if response.status().is_success() => true,
        Ok(response) => {
            tracing::error!("authoritative check run completion refused: {}", response.status());
            false
        }
        Err(_) => {
            tracing::error!("authoritative check run completion failed");
            false
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Reporter {
    CheckRun,
    CommitStatus,
    None,
}

pub(crate) fn reporter_for(config: &Config, _target: &CheckTarget) -> Reporter {
    match config.reporting_mode {
        ReportingMode::AppRequired if checks_configured(config) => Reporter::CheckRun,
        ReportingMode::AppRequired | ReportingMode::Invalid => Reporter::None,
        ReportingMode::Advisory if config.github_token.is_some() => Reporter::CommitStatus,
        ReportingMode::Advisory => Reporter::None,
    }
}

async fn post_commit_status(
    state: &AppState,
    target: &CheckTarget,
    status: &str,
    description: &str,
) -> bool {
    let Some(token) = state.config.github_token.as_deref() else {
        return false;
    };
    let description: String = description.chars().take(140).collect();
    let body = json!({
        "state": status,
        "context": state.config.advisory_status_context,
        "description": description,
    });
    let response = state
        .http
        .post(format!(
            "{GITHUB_API}/repos/{}/{}/statuses/{}",
            target.owner, target.repo, target.head_sha
        ))
        .header("authorization", format!("Bearer {token}"))
        .header("accept", "application/vnd.github+json")
        .header("x-github-api-version", API_VERSION)
        .header("user-agent", USER_AGENT)
        .json(&body)
        .send()
        .await;
    match response {
        Ok(response) if response.status().is_success() => true,
        Ok(response) => {
            tracing::warn!("advisory commit status refused: {}", response.status());
            false
        }
        Err(_) => {
            tracing::warn!("advisory commit status failed");
            false
        }
    }
}

pub(crate) async fn report_started(
    state: &AppState,
    target: &CheckTarget,
    job_id: &str,
) -> Option<u64> {
    match reporter_for(state.config.as_ref(), target) {
        Reporter::CheckRun => start_check_run(state, target, job_id).await,
        Reporter::CommitStatus => {
            let _ = post_commit_status(
                state,
                target,
                "pending",
                "Running on the local worker (advisory)",
            )
            .await;
            None
        }
        Reporter::None => None,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Delivery {
    CheckRun,
    CommitStatus,
    Undelivered,
    NotConfigured,
}

pub(crate) fn verdict_plan(
    reporter: Reporter,
    check_run_id: Option<u64>,
    has_status_token: bool,
) -> Vec<Delivery> {
    match reporter {
        // Authoritative mode is closed: a PAT status can never repair or
        // substitute an App-owned context.
        Reporter::CheckRun => check_run_id
            .map(|_| vec![Delivery::CheckRun])
            .unwrap_or_default(),
        Reporter::CommitStatus if has_status_token => vec![Delivery::CommitStatus],
        Reporter::CommitStatus | Reporter::None => Vec::new(),
    }
}

pub(crate) async fn report_finished(
    state: &AppState,
    target: &CheckTarget,
    check_run_id: Option<u64>,
    succeeded: bool,
    summary: &str,
    log_tail: &str,
) -> Delivery {
    let reporter = reporter_for(state.config.as_ref(), target);
    if reporter == Reporter::None {
        return Delivery::NotConfigured;
    }
    let plan = verdict_plan(reporter, check_run_id, state.config.github_token.is_some());
    for step in plan {
        let delivered = match (step, check_run_id) {
            (Delivery::CheckRun, Some(id)) => {
                finish_check_run(state, target, id, succeeded, summary, log_tail).await
            }
            (Delivery::CommitStatus, _) => {
                let status = if succeeded { "success" } else { "failure" };
                post_commit_status(state, target, status, summary).await
            }
            _ => false,
        };
        if delivered {
            return step;
        }
    }
    tracing::error!(
        "verdict for {}/{}@{} was not delivered to GitHub by the configured reporting authority",
        target.owner,
        target.repo,
        target.head_sha
    );
    Delivery::Undelivered
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target(installation_id: Option<u64>) -> CheckTarget {
        target_from_request(
            "https://github.com/o/r",
            Some("52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0"),
            installation_id,
        )
        .expect("target")
    }

    #[test]
    fn targets_require_a_pinned_commit() {
        let target = target(None);
        assert_eq!(target.owner, "o");
        assert_eq!(target.repo, "r");
        assert_eq!(
            target_from_request("https://github.com/o/r", Some("main"), None),
            None
        );
        assert_eq!(target_from_request("https://github.com/o/r", None, None), None);
    }

    #[test]
    fn target_parses_supported_github_urls_only() {
        let sha = "52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0";
        assert!(target_from_request("https://github.com/o/r.git", Some(sha), None).is_some());
        assert!(target_from_request("git@github.com:o/r.git", Some(sha), None).is_some());
        assert!(target_from_request("https://gitlab.com/o/r", Some(sha), None).is_none());
        assert!(target_from_request("https://github.com/o/r/extra", Some(sha), None).is_none());
    }

    #[test]
    fn signed_or_bootstrap_installation_is_only_a_candidate() {
        assert_eq!(verify_installation_candidate(Some(22), 22), Ok(22));
        assert!(verify_installation_candidate(Some(22), 23).is_err());
        assert_eq!(verify_installation_candidate(None, 23), Ok(23));
        assert!(verify_installation_candidate(None, 0).is_err());
    }

    #[test]
    fn repo_cache_keys_are_org_specific() {
        let a = CheckTarget { owner: "org-a".into(), repo: "r".into(), head_sha: "a".repeat(40), installation_id: None };
        let b = CheckTarget { owner: "org-b".into(), repo: "r".into(), head_sha: "a".repeat(40), installation_id: None };
        assert_ne!(cache_key(&a), cache_key(&b));
    }

    #[test]
    fn reporting_modes_never_share_an_authoritative_context() {
        let mut config = crate::config::config_from_env();
        config.github_token = Some("token".to_string());
        config.github_app_id = Some("1".to_string());
        config.github_app_private_key = Some("pem".to_string());

        config.reporting_mode = ReportingMode::Advisory;
        assert_eq!(reporter_for(&config, &target(None)), Reporter::CommitStatus);

        config.reporting_mode = ReportingMode::AppRequired;
        assert_eq!(reporter_for(&config, &target(None)), Reporter::CheckRun);
        assert_eq!(
            verdict_plan(Reporter::CheckRun, Some(7), true),
            vec![Delivery::CheckRun],
            "PAT fallback must be impossible in app-required mode"
        );
        assert_eq!(
            verdict_plan(Reporter::CheckRun, None, true),
            Vec::<Delivery>::new(),
            "missing App check stays undelivered rather than becoming a PAT status"
        );
    }

    #[test]
    fn invalid_or_partial_authoritative_config_has_no_reporter() {
        let mut config = crate::config::config_from_env();
        config.reporting_mode = ReportingMode::AppRequired;
        config.github_app_id = None;
        config.github_app_private_key = None;
        config.github_token = Some("token".to_string());
        assert_eq!(reporter_for(&config, &target(None)), Reporter::None);
    }

    #[test]
    fn output_text_is_bounded_in_bytes_not_characters() {
        let input = "🦀".repeat(MAX_OUTPUT_TEXT);
        let output = output_text(&input);
        assert!(output.len() <= MAX_OUTPUT_TEXT + "…log truncated…\n".len());
        assert!(output.is_char_boundary(output.len()));
    }
}