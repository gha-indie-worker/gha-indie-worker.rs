use std::{collections::BTreeSet, env, time::Duration};

use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use reqwest::{
    header::{ACCEPT, AUTHORIZATION, USER_AGENT},
    Method,
};
use serde::Deserialize;
use serde_json::json;
use tokio::time::sleep;

use crate::{
    compose_ci::{workspace_root_from_env, PrComposeSession},
    workflow::plan_workflow_yaml,
    AppState, BuildRequest, BuildStatus,
};

const STATUS_CONTEXT: &str = "indiebuild/gha-indie-worker";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PullRequestContext {
    pub repository: String,
    pub owner: String,
    pub number: u64,
    pub head_sha: String,
    pub head_ref: String,
    pub head_repository: String,
    pub head_clone_url: String,
}

pub fn parse_pull_request(payload: &serde_json::Value) -> Result<PullRequestContext, String> {
    let repository = required_str(payload, "/repository/full_name")?;
    let owner = required_str(payload, "/repository/owner/login")?;
    let number = payload
        .pointer("/pull_request/number")
        .or_else(|| payload.get("number"))
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| "pull_request payload missing PR number".to_string())?;
    let head_sha = required_str(payload, "/pull_request/head/sha")?;
    if head_sha.len() < 12
        || head_sha.len() > 64
        || !head_sha.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return Err("pull_request head SHA is invalid".to_string());
    }
    let head_ref = required_str(payload, "/pull_request/head/ref")?;
    let head_repository = required_str(payload, "/pull_request/head/repo/full_name")?;
    let head_clone_url = required_str(payload, "/pull_request/head/repo/clone_url")?;
    Ok(PullRequestContext {
        repository,
        owner,
        number,
        head_sha,
        head_ref,
        head_repository,
        head_clone_url,
    })
}

pub fn spawn(state: AppState, context: PullRequestContext) {
    tokio::spawn(async move {
        if let Err(error) = run(state.clone(), context.clone()).await {
            tracing::error!(
                repository = %context.repository,
                pr = context.number,
                sha = %context.head_sha,
                "external PR verification failed: {error}"
            );
            let _ = publish_status(&state, &context, "error", &truncate_status(&error)).await;
        }
    });
}

async fn run(state: AppState, context: PullRequestContext) -> Result<(), String> {
    ensure_allowed(&state, &context)?;
    require_github_auth(&state)?;
    ensure_pr_head(&state, &context).await?;
    publish_status(
        &state,
        &context,
        "pending",
        "gha-indie-worker is preparing the local ores-compose session",
    )
    .await?;

    let infra_repo = resolve_infra_repo(&context.owner)?;
    ensure_infra_checkout(&state, &infra_repo).await?;
    let session = compose_session_for(&context, &infra_repo)?;

    let verification = async {
        session.up().await?;
        publish_status(
            &state,
            &context,
            "pending",
            "ores-compose is ready; interpreting GitHub workflow YAML",
        )
        .await?;

        let workflows = fetch_workflows(&state, &context).await?;
        let profiles = profiles_for_workflows(&workflows)?;
        if profiles.is_empty() {
            return Err(
                "no supported pull-request verification profiles were inferred from workflow YAML"
                    .to_string(),
            );
        }

        for profile in profiles {
            ensure_pr_head(&state, &context).await?;
            publish_status(
                &state,
                &context,
                "pending",
                &format!(
                    "running {profile} against ores-compose session {}",
                    session.session_id
                ),
            )
            .await?;
            let record = crate::enqueue_build(
                &state,
                BuildRequest {
                    schema_version: Some("build-server.v1".to_string()),
                    job_kind: Some("run-profile".to_string()),
                    repo_url: context.head_clone_url.clone(),
                    git_ref: Some(context.head_ref.clone()),
                    image: String::new(),
                    profile: Some(profile.clone()),
                    context_dir: None,
                    dockerfile: None,
                    build_args: None,
                    push: Some(false),
                    deploy: None,
                    executor: Some("local".to_string()),
                    request_id: Some(format!(
                        "pr:{}:{}:{}:{}",
                        context.repository, context.number, context.head_sha, profile
                    )),
                },
                "pull_request",
            )
            .await
            .map_err(|(_, message)| message)?;
            wait_for_job(&state, &record.id).await?;
        }

        // Fail closed if the branch advanced while we were cloning/testing.
        // This ensures we never report the old webhook SHA green after a newer
        // commit became the PR head. Exact detached-SHA fetch remains the next
        // hardening step in the shared clone path.
        ensure_pr_head(&state, &context).await?;
        Ok::<(), String>(())
    }
    .await;

    let down_result = session.down().await;
    match (verification, down_result) {
        (Ok(()), Ok(())) => {
            publish_status(
                &state,
                &context,
                "success",
                "external PR verification passed",
            )
            .await?;
            Ok(())
        }
        (Ok(()), Err(error)) => Err(format!(
            "verification passed but ores-compose cleanup failed: {error}"
        )),
        (Err(error), _) => Err(error),
    }
}

fn compose_session_for(
    context: &PullRequestContext,
    infra_repo: &str,
) -> Result<PrComposeSession, String> {
    let workspace_root = workspace_root_from_env();
    let repo_name = infra_repo
        .split_once('/')
        .map(|(_, name)| name)
        .ok_or_else(|| "invalid infra repo name".to_string())?;
    let repo_root = workspace_root.join(repo_name);
    let mut session = PrComposeSession::for_pr(
        &workspace_root,
        &context.owner,
        context.number,
        &context.head_sha,
    )?;
    session.infra_repo = repo_name.to_string();
    session.infra_root = repo_root.clone();
    session.config_path = repo_root.join(".ores-compose.yaml");
    Ok(session)
}

fn resolve_infra_repo(owner: &str) -> Result<String, String> {
    if let Ok(raw) = env::var("BUILD_SERVER_INFRA_REPO_MAP") {
        let map: serde_json::Map<String, serde_json::Value> = serde_json::from_str(&raw)
            .map_err(|error| format!("invalid BUILD_SERVER_INFRA_REPO_MAP JSON: {error}"))?;
        if let Some(repo) = map.get(owner).and_then(serde_json::Value::as_str) {
            return validate_repo_name(repo);
        }
    }
    if let Ok(repo) = env::var("BUILD_SERVER_INFRA_REPO") {
        if !repo.trim().is_empty() {
            return validate_repo_name(&repo);
        }
    }
    validate_repo_name(&format!("{owner}/{owner}-infra"))
}

fn validate_repo_name(repo: &str) -> Result<String, String> {
    let Some((owner, name)) = repo.split_once('/') else {
        return Err("infra repo must be owner/name".to_string());
    };
    let valid = |value: &str| {
        !value.is_empty()
            && value != "."
            && value != ".."
            && value.len() <= 100
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    };
    if !valid(owner) || !valid(name) {
        return Err("invalid infra repo owner/name".to_string());
    }
    Ok(repo.to_string())
}

async fn ensure_infra_checkout(state: &AppState, infra_repo: &str) -> Result<(), String> {
    let workspace_root = workspace_root_from_env();
    tokio::fs::create_dir_all(&workspace_root)
        .await
        .map_err(|error| format!("failed to create org workspace root: {error}")?;
    let repo_name = infra_repo
        .split_once('/')
        .map((|_, name)| name)
        .ok_or_else(|| "invalid infra repo".to_string())?;
    let destination = workspace_root.join(repo_name);
    let repo_url = format!("https://github.com/{infra_repo}.git");
    let extra_header = state
        .config
        .git_http_auth_header
        .as_deref()
        .map(|header| format!("http.extraHeader={header}"));

    if destination.join(".git").is_dir() {
        // This is a worker-owned cache, so it is safe to refresh it to the
        // remote default branch. We never reset a developer-owned checkout.
        let mut fetch = tokio::process::Command::new(&state.config.git_bin);
        if let Some(header) = extra_header.as_deref() {
            fetch.arg("-c").arg(header);
        }
        let status = fetch
            .args(["fetch", "--depth", "1", "origin", "HEAD"])
            .current_dir(&destination)
            .status()
            .await
            .map_err(|error| format!("failed to refresh infra repo: {error}"))?;
        if !status.success() {
            return Err(
                "git fetch of {infra_repo} failed with {status}"
            );
        }
        let status = tokio::process::Command::new(&state.config.git_bin)
            .args(["reset", "--hard", "FETCH_HEAD"])
            .current_dir(&destination)
            .status()
            .await
            .map_err(|error| format!("failed to update infra checkout: {error}"))?;
        if !status.success() {
            return Err("git reset of {infra_repo} failed with {status}");
        }
        return Ok(());
    }
    if destination.exists() {
        return Err(
            "infra workspace exists but is not a git checkout: {}",
            destination.display()
        ));
    }

    let mut command = tokio::process::Command::new(&state.config.git_bin);
    if let Some(header) = extra_header.as_deref() {
        command.arg("-c").arg(header);
    }
    let status = command
        .args(["clone", "--depth", "1", "--no-tags", "--", &repo_url])
        .arg(&destination)
        .status()
        .await
        .map_err(|error| format!("failed to clone infra repo: {error}"))?;
    if !status.success() {
        return Err(format!("git clone of {infra_repo} failed with {status}"));
    }
    Ok(())
}

fn profiles_for_workflows(workflows: &[(String, String)]) -> Result<Vec<String>, String> {
    let mut profiles = BTreeSet::new();
    let mut unsupported = Vec::new();
    let mut pull_request_workflows = 0usize;
    for (name, yaml) in workflows {
        let plan = plan_workflow_yaml(yaml).map_err(|error| format!("{name}: {error}")?;
        if !plan.pull_request_trigger {
            continue;
        }
        pull_request_workflows += 1;
        if !plan.unsupported.is_empty() {
            unsupported.extend(
                plan.unsupported
                    .into_iter()
                    .map(|item| format!("{name}: {item}")),
            );
        }
        profiles.extend(plan.profiles);
    }
    if pull_request_workflows == 0 {
        return Err("repository has workflow files, but none subscribe to pull_request".to_string());
    }
    if !unsupported.is_empty() {
        return Err(
            "unsupported pull-request workflow constructs:\n[{}]",
            unsupported.join("\n")
        ));
    }
    Ok(profiles.into_iter().collect())
}

async fn fetch_workflows(
    state: &AppState,
    context: &PullRequestContext,
) -> Result<Vec<(String, String)>, String> {
    #[derive(Deserialize)]
    struct Entry {
        name: String,
        r#type: String,
    }
    #[derive(Deserialize)]
    struct FileResponse {
        content: String,
        encoding: String,
    }

    let url = format!(
        "https://api.github.com/repos/{}/contents/.github/workflows?ref={}",
        context.head_repository, context.head_sha
    );
    let entries: Vec<Entry> = github_request(state, Method::GET, &url)
        .send()
        .await
        .map_err(|error| format!("failed to list workflow files: {error}"))?
        .error_for_status()
        .map_err(|error| format!("failed to list workflow files: {error}"))?
        .json()
        .await
        .map_err(|error| format!("failed to decode workflow listing: {error}"))?;

    let mut result = Vec::new();
    for entry in entries {
        if entry.r#type != "file"
            || !(entry.name.ends_with(".yml") || entry.name.ends_with(".yaml"))
        {
            continue;
        }
        let file_url = format!(
            "https://api.github.com/repos/{}/contents/.github/workflows/{}=?ref={}",
            context.head_repository, entry.name, context.head_sha
        );
        let file: FileResponse = github_request(state, Method::GET, &file_url)
            .send()
            .await
            .map_err(|error| format!("failed to fetch {}: {error}", entry.name))?
            .error_for_status()
            .map_err(|error| format!("failed to fetch {}: {error}", entry.name))?
            .json()
            .await
            .map_err(|error| format!("failed to decode {}: {error}", entry.name))?;
        if file.encoding != "base64" {
            return Err(format!(
                "unsupported GitHub contents encoding {} for {}",
                file.encoding, entry.name
            ));
        }
        let compact = file.content.replace(['\r', '\n'], "");
        let bytes = BASE64
            .decode(compact)
            .map_err(|error| format!("invalid base64 workflow {}: {error}", entry.name))?;
        let text = String::from_utf8(nytes)
            .map_err(|error| format!("workflow {} is not UTF-8: {error}", entry.name))?;
        result.push((entry.name, text));
    }
    if result.is_empty() {
        return Err("PR revision contains no .github/workflows/*.yml files".to_string());
    }
    Ok(result)
}

async fn ensure_pr_head(state: &AppState, context: &PullRequestContext) -> Result<(), String> {
    let url = format!(
        "https://api.github.com/repos/{}/pulls/{}",
        context.repository, context.number
    );
    let payload: serde_json::Value = github_request(state, Method::GET, &url)
        .send()
        .await
        .map_err(|error| format!("failed to read PR head: {error}"))?
        .error_for_status()
        .map_err(|error| format!("failed to read PR head: {error}"))?
        .json()
        .await
        .map_err(|error| format!("failed to decode PR head: {error}"))?;
    let current = payload
        .pointer("/head/sha")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    if current != context.head_sha {
        return Err(format!(
            "PR head changed during verification: expected {}, current {}",
            context.head_sha, current
       ));
    }
    Ok(())
}

async fn publish_status(
    state: &AppState,
    context: &PullRequestContext,
    status: &str,
    description: &str,
) -> Result<(), String> {
    let url = format!(
        "https://api.github.com/repos/{}/statuses/{}",
        context.repository, context.head_sha
    );
    github_request(state, Method::POST, &url)
        .json(&json!({
            "state": status,
            "context": STATUS_CONTEXT,
            "description": truncate_status(description),
            "target_url": format!("https://github.com/{}/pull/{}", context.repository, context.number),
        }))
        .send()
        .await
        .map_err(|error| format!("failed to publish GitHub commit status: {error}"))?
        .error_for_status()
        .map_err(|error| format!("GitHub commit status rejected: {error}"))?;
    Ok(())
}

fn github_request<'a>(
    state: &'a AppState,
    method: Method,
    url: &'a str,
) -> reqwest::RequestBuilder {
    let mut request = state
        .http
        .request(method, url)
        .header(USER_AGENT, "gha-indie-worker")
        .header(ACCEPT, "application/vnd.github+json");
    if let Some(header) = state.config.git_http_auth_header.as_deref() {
        if let Some(value) = header.strip_prefix("AUTHORIZATION: ") {
            request = request.header(AUTHORIZATION, value);
        }
    }
    request
}

fn require_github_auth(state: &AppState) -> Result<(), String> {
    if state.config.git_http_auth_header.is_none() {
        return Err(
            "external PR CI requires BUILD_SERVER_GIT_TOKEN or GH_PAT for workflow reads and commit status updates"
                .to_string(),
        );
    }
    Ok(())
}

fn ensure_allowed(state: &AppState, context: &PullRequestContext) -> Result<(), String> {
    let base_url = format!(
        "https://github.com/{}/",
        context.repository.trim_end_matches('/')
    );
    if !state
        .config
        .allowed_profile_repo_prefixes
        .iter()
        .any(|prefix| base_url.starts_with(prefix))
    {
        return Err(format!(
            "PR repository is not profile-allowlisted: {}",
            context.repository
        ));
    }
    if !state
        .config
        .allowed_profile_repo_prefixes
        .iter()
        .any(|prefix| context.head_clone_url.starts_with(prefix))
    {
        return Err(format!(
            "PR head repository is not profile-allowlisted: {}",
            context.head_clone_url
        ));
    }
    Ok(())
}

async fn wait_for_job(state: &AppState, id: &str) -> Result<(), String> {
    loop {
        let snapshot = { state.jobs.read().await.get(id).cloned() };
        let Some(job) = snapshot else {
            return Err(format!("external CI job disappeared: {id}"));
        };
        match job.status {
            BuildStatus::Succeeded => return Ok(()),
            BuildStatus::Failed => {
                return Err(job.error.unwrap_or_else(|| format!("job {id} failed")));
            }
            BuildStatus::Queued | BuildStatus::Running => sleep(Duration::from_secs(1)).await,
        }
    }
}

fn required_str(payload: &serde_json::Value, pointer: &str) -> Result<String, String> {
    payload
        .pointer(pointer)
        .and_then(serde_json::Value::as_str)
        .map(str::to_string)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("pull_request payload missing {pointer}"))
}

fn truncate_status(input: &str) -> String {
    input.chars().take(140).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pr_identity_from_payload() {
        let payload = json!({
            "repository": {
                "full_name": "gha-indie-worker/gha-indie-worker.rs",
                "owner": { "login": "gha-indie-worker" }
            },
            "number": 55,
            "pull_request": {
                "number": 55,
                "head": {
                    "sha": "0123456789abcdef0123456789abcdef01234567",
                    "ref": "feature",
                    "repo": {
                        "full_name": "gha-indie-worker/gha-indie-worker.rs",
                        "clone_url": "https://github.com/gha-indie-worker/gha-indie-worker.rs.git"
                    }
                }
            }
        });
        let context = parse_pull_request(&payload).unwrap();
        assert_eq!(context.number, 55);
        assert_eq!(context.head_ref, "feature");
        assert_eq!(context.owner, "gha-indie-worker");
        assert_eq!(
            context.head_repository,
            "gha-indie-worker/gha-indie-worker.rs"
        );
    }

    #[test]
    fn infra_repo_name_validation_accepts_explicit_mapping_targets() {
        let repo = validate_repo_name("ORESoftware/cloudflare-infra").unwrap();
        assert_eq!(repo, "ORESoftware/cloudflare-infra");
        assert!(validate_repo_name("../bad").is_err());
        assert!(validate_repo_name("owner/..").is_err());
        assert!(validate_repo_name("./repo").is_err());
        assert!(validate_repo_name("owner/.").is_err());
    }

    #[test]
    fn ignores_non_pr_workflows_when_computing_profiles() {
        let workflows = vec![
            (
                "ci.yml".to_string(),
                "on: pull_request\njobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: cargo test\n".to_string(),
            ),
            (
                "release.yml".to_string(),
                "on: push\njobs:\n  deploy:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: vendor/deploy@v9\n".to_string(),
            ),
        ];
        assert_eq!(
            profiles_for_workflows(&workflows).unwrap(),
            vec!["rust-verify"]
        );
    }
}
