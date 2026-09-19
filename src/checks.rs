//! GitHub Check Run reporting.
//!
//! Only a GitHub App can create check runs — a personal access token cannot —
//! so this mints an app JWT, exchanges it for a short-lived installation
//! token, and reports the verdict against the exact head commit that was
//! verified.
//!
//! The App private key never leaves this process. Job containers are started
//! with a cleared environment and a read-only view of the cloned repository,
//! so nothing here is reachable from the code being verified.
//!
//! Reporting is strictly advisory to the build: every failure below is logged
//! and swallowed, because losing a status update must never turn a green build
//! red (or vice versa).

use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::json;

use crate::config::Config;
use crate::state::AppState;

const GITHUB_API: &str = "https://api.github.com";
const API_VERSION: &str = "2022-11-28";
const USER_AGENT: &str = "gha-indie-worker";

/// GitHub rejects `output.text` beyond 65535 characters, so the log tail is
/// trimmed well inside that with room for the fenced block around it.
const MAX_OUTPUT_TEXT: usize = 60_000;

/// The commit a check run is attached to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CheckTarget {
    pub owner: String,
    pub repo: String,
    pub head_sha: String,
    /// Installation named by the signed delivery, when there was one.
    pub installation_id: Option<u64>,
}

#[derive(Serialize)]
struct AppClaims {
    iat: i64,
    exp: i64,
    iss: String,
}

/// Which commit, if any, this job should report against.
///
/// A check run must name a commit: a branch name cannot be reported on, and
/// reporting against whatever the branch points at *now* would attach the
/// verdict to code that was never verified. Jobs that are not pinned to an
/// object id therefore report nothing.
pub(crate) fn target_from_request(
    repo_url: &str,
    commit_sha: Option<&str>,
    installation_id: Option<u64>,
) -> Option<CheckTarget> {
    // The same grammar admission and checkout use, so a job is reported on
    // exactly when the executor could pin it.
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

/// The installation a target is reported through.
///
/// The delivery's own installation wins. The configured id is only a fallback
/// for jobs that did not arrive by webhook, and must never override what a
/// signed delivery said: that would make one installation the authority for
/// every repository the App is installed on.
pub(crate) fn installation_for(config: &Config, target: &CheckTarget) -> Option<String> {
    target
        .installation_id
        .map(|id| id.to_string())
        .or_else(|| config.github_app_installation_id.clone())
}

pub(crate) fn checks_configured(config: &Config, target: &CheckTarget) -> bool {
    config.github_app_id.is_some()
        && config.github_app_private_key.is_some()
        && installation_for(config, target).is_some()
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_secs() as i64)
        .unwrap_or_default()
}

/// Sign a short-lived app JWT.
///
/// `iat` is backdated by a minute because GitHub rejects tokens whose issue
/// time is in the future relative to its own clock, and modest host clock skew
/// is normal on a laptop.
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

/// Exchange the app JWT for an installation token.
///
/// Minted per operation rather than cached: a check run costs two of these per
/// job, which is negligible against the installation's hourly budget, and it
/// avoids holding a decrypted credential in memory between jobs.
async fn installation_token(state: &AppState, target: &CheckTarget) -> Result<String, String> {
    let config = state.config.as_ref();
    let installation = installation_for(config, target);
    let (Some(app_id), Some(pem), Some(installation)) = (
        config.github_app_id.as_deref(),
        config.github_app_private_key.as_deref(),
        installation.as_deref(),
    ) else {
        return Err("GitHub App is not configured".to_string());
    };
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
        .map_err(|error| format!("installation token request failed: {error}"))?;
    let status = response.status();
    let body: serde_json::Value = response
        .json()
        .await
        .map_err(|error| format!("installation token response was not JSON: {error}"))?;
    if !status.is_success() {
        // The body carries GitHub's message, never the key or the JWT.
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

/// Announce that verification of this commit has started.
///
/// Returns the check run id to complete later, or `None` when checks are not
/// configured or GitHub refused — the build continues regardless.
pub(crate) async fn start_check_run(
    state: &AppState,
    target: &CheckTarget,
    job_id: &str,
) -> Option<u64> {
    if !checks_configured(state.config.as_ref(), target) {
        return None;
    }
    let token = match installation_token(state, target).await {
        Ok(token) => token,
        Err(error) => {
            tracing::warn!("check run not started: {error}");
            return None;
        }
    };
    let name = state.config.check_run_name.clone();
    let body = json!({
        "name": name,
        "head_sha": target.head_sha,
        "status": "in_progress",
        "started_at": iso_now(),
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
            tracing::warn!("check run creation refused: {}", response.status());
            None
        }
        Err(error) => {
            tracing::warn!("check run creation failed: {error}");
            None
        }
    }
}

/// Trim a build log to what GitHub will accept, keeping the end.
///
/// The tail is what explains a failure; the head is image pulls. GitHub's
/// limit is in **bytes**, so counting characters is wrong: a log full of
/// multibyte UTF-8 would stay far over the limit, completion would be
/// rejected, and the check would sit `in_progress` forever. The cut is moved
/// forward to a character boundary so the result is still valid UTF-8.
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

/// Report the verdict against the verified commit.
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
            tracing::warn!("check run not completed: {error}");
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
            tracing::warn!("check run completion refused: {}", response.status());
            false
        }
        Err(error) => {
            tracing::warn!("check run completion failed: {error}");
            false
        }
    }
}

/// How a verdict reaches GitHub.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Reporter {
    /// GitHub App + Checks API: richer output, and the only way to get a real
    /// check run.
    CheckRun,
    /// Commit Status API with a token. A status shows on the pull request and
    /// satisfies branch protection exactly like a check does, and it needs no
    /// App, so it is the path that works before one exists.
    CommitStatus,
    None,
}

pub(crate) fn reporter_for(config: &Config, target: &CheckTarget) -> Reporter {
    if checks_configured(config, target) {
        Reporter::CheckRun
    } else if config.github_token.is_some() {
        Reporter::CommitStatus
    } else {
        Reporter::None
    }
}

/// Post a commit status. `state` is GitHub's vocabulary: pending, success,
/// failure, error.
async fn post_commit_status(
    state: &AppState,
    target: &CheckTarget,
    status: &str,
    description: &str,
) -> bool {
    let Some(token) = state.config.github_token.as_deref() else {
        return false;
    };
    // GitHub truncates descriptions past 140 characters.
    let description: String = description.chars().take(140).collect();
    let body = json!({
        "state": status,
        "context": state.config.check_run_name,
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
            tracing::warn!("commit status refused: {}", response.status());
            false
        }
        Err(error) => {
            tracing::warn!("commit status failed: {error}");
            false
        }
    }
}

/// Announce that verification started, by whichever mechanism is configured.
///
/// Returns the check run id when one was created, so the completion can update
/// that same run.
pub(crate) async fn report_started(
    state: &AppState,
    target: &CheckTarget,
    job_id: &str,
) -> Option<u64> {
    match reporter_for(state.config.as_ref(), target) {
        Reporter::CheckRun => start_check_run(state, target, job_id).await,
        Reporter::CommitStatus => {
            post_commit_status(state, target, "pending", "Running on the local worker").await;
            None
        }
        Reporter::None => None,
    }
}

/// How a verdict was, or was not, delivered.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Delivery {
    CheckRun,
    CommitStatus,
    /// Nothing reached GitHub. A required check would stay pending, so this is
    /// recorded loudly rather than treated as a quiet no-op.
    Undelivered,
    NotConfigured,
}

/// Which mechanisms to try, in order, for a terminal verdict.
///
/// Pure so the fallback policy is testable: the bug this replaces was a
/// "fallback" to commit statuses that silently did nothing when the
/// deployment had App credentials but no token, leaving no verdict at all.
pub(crate) fn verdict_plan(
    reporter: Reporter,
    check_run_id: Option<u64>,
    has_status_token: bool,
) -> Vec<Delivery> {
    let mut plan = Vec::new();
    if reporter == Reporter::CheckRun && check_run_id.is_some() {
        plan.push(Delivery::CheckRun);
    }
    // A status is a real fallback only when there is a token to post it with.
    if reporter != Reporter::None && has_status_token {
        plan.push(Delivery::CommitStatus);
    }
    plan
}

/// Report the verdict, by whichever mechanism is configured.
///
/// Mechanisms are tried in order until one is delivered. A check run that was
/// created but cannot be completed therefore still gets a terminal status
/// when a token exists, instead of hanging `in_progress`.
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
        "verdict for {}/{}@{} was not delivered to GitHub by any configured mechanism",
        target.owner,
        target.repo,
        target.head_sha
    );
    Delivery::Undelivered
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn targets_require_a_pinned_commit() {
        let sha = "52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0";
        let target =
            target_from_request("https://github.com/o/r", Some(sha), None).expect("target");
        assert_eq!(target.owner, "o");
        assert_eq!(target.repo, "r");
        assert_eq!(target.head_sha, sha);

        // A branch name cannot be reported on, and reporting against wherever
        // it points now would attach the verdict to unverified code.
        assert_eq!(
            target_from_request("https://github.com/o/r", Some("main"), None),
            None
        );
        assert_eq!(
            target_from_request("https://github.com/o/r", None, None),
            None
        );
    }

    #[test]
    fn target_parses_the_url_forms_the_worker_builds() {
        let sha = "52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0";
        // Webhook rules build this form, with the .git suffix.
        let from_webhook = target_from_request(
            "https://github.com/gha-indie-worker/api.rs.git",
            Some(sha),
            None,
        )
        .expect("https target");
        assert_eq!(from_webhook.owner, "gha-indie-worker");
        assert_eq!(from_webhook.repo, "api.rs");

        let ssh =
            target_from_request("git@github.com:o/r.git", Some(sha), None).expect("ssh target");
        assert_eq!(ssh.owner, "o");
        assert_eq!(ssh.repo, "r");

        // Anything that is not GitHub has no check runs to report to.
        assert_eq!(
            target_from_request("https://gitlab.com/o/r", Some(sha), None),
            None
        );
        assert_eq!(
            target_from_request("https://github.com/o", Some(sha), None),
            None
        );
        assert_eq!(
            target_from_request("https://github.com/o/r/extra", Some(sha), None),
            None
        );
    }

    #[test]
    fn the_reporter_prefers_an_app_and_falls_back_to_a_token() {
        let target = target_from_request(
            "https://github.com/o/r",
            Some("52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0"),
            None,
        )
        .expect("target");
        let mut config = crate::config::config_from_env();
        config.github_app_id = None;
        config.github_app_private_key = None;
        config.github_app_installation_id = None;
        config.github_token = None;
        assert_eq!(reporter_for(&config, &target), Reporter::None);

        // A token alone still turns pull requests green, via commit statuses.
        config.github_token = Some("token".to_string());
        assert_eq!(reporter_for(&config, &target), Reporter::CommitStatus);

        config.github_app_id = Some("1".to_string());
        config.github_app_private_key = Some("pem".to_string());
        config.github_app_installation_id = Some("2".to_string());
        assert_eq!(reporter_for(&config, &target), Reporter::CheckRun);

        // A partly configured App must not count as configured.
        config.github_app_installation_id = None;
        assert_eq!(reporter_for(&config, &target), Reporter::CommitStatus);
    }

    #[test]
    fn the_signed_delivery_decides_which_installation_reports() {
        let sha = "52f0e858d5d6cc952d0bb24d1eb5b4631bb92de0";
        let mut config = crate::config::config_from_env();
        config.github_app_id = Some("1".to_string());
        config.github_app_private_key = Some("pem".to_string());
        config.github_app_installation_id = Some("111".to_string());

        // An App on two orgs has two installations. The delivery's id must
        // win, or every repository would report through whichever one was
        // configured globally.
        let from_webhook =
            target_from_request("https://github.com/other-org/r", Some(sha), Some(222)).unwrap();
        assert_eq!(
            installation_for(&config, &from_webhook).as_deref(),
            Some("222")
        );

        // A job that did not come from a webhook has only the fallback.
        let manual = target_from_request("https://github.com/o/r", Some(sha), None).unwrap();
        assert_eq!(installation_for(&config, &manual).as_deref(), Some("111"));

        // With no global id, webhook jobs still report and manual ones do not.
        config.github_app_installation_id = None;
        assert!(checks_configured(&config, &from_webhook));
        assert!(!checks_configured(&config, &manual));
    }

    #[test]
    fn a_verdict_never_falls_back_to_a_mechanism_that_cannot_run() {
        // App credentials, the check run was never created, and no token: the
        // old code "fell back" to a status call that returned immediately.
        assert_eq!(
            verdict_plan(Reporter::CheckRun, None, false),
            Vec::<Delivery>::new()
        );
        // With a token the fallback is real.
        assert_eq!(
            verdict_plan(Reporter::CheckRun, None, true),
            vec![Delivery::CommitStatus]
        );
        // A created check run is completed first, with the status behind it in
        // case completion is refused.
        assert_eq!(
            verdict_plan(Reporter::CheckRun, Some(7), true),
            vec![Delivery::CheckRun, Delivery::CommitStatus]
        );
        assert_eq!(
            verdict_plan(Reporter::CheckRun, Some(7), false),
            vec![Delivery::CheckRun]
        );
        assert_eq!(
            verdict_plan(Reporter::CommitStatus, None, true),
            vec![Delivery::CommitStatus]
        );
        assert_eq!(
            verdict_plan(Reporter::None, None, true),
            Vec::<Delivery>::new()
        );
    }

    #[test]
    fn output_text_is_bounded_in_bytes_not_characters() {
        // Four bytes each: a character count would let this through at four
        // times GitHub's limit.
        let log = "🦀".repeat(MAX_OUTPUT_TEXT);
        let trimmed = output_text(&log);
        assert!(trimmed.len() < 65_000, "{} bytes", trimmed.len());
        assert!(trimmed.ends_with('🦀'));
        // Cutting mid-character would not be valid UTF-8; this would panic.
        let mixed = format!("{}é{}", "x".repeat(3), "y".repeat(MAX_OUTPUT_TEXT - 1));
        assert!(output_text(&mixed).is_char_boundary(0));
    }

    #[test]
    fn output_text_keeps_the_end_of_a_long_log() {
        let log = format!("{}TAIL-MARKER", "x".repeat(MAX_OUTPUT_TEXT * 2));
        let trimmed = output_text(&log);
        assert!(trimmed.ends_with("TAIL-MARKER"));
        assert!(trimmed.starts_with("…log truncated…"));
        // Must stay inside GitHub's 65535 limit once fenced.
        assert!(trimmed.len() < 65_000);

        let short = "all of it";
        assert_eq!(output_text(short), short);
    }
}
