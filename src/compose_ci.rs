use std::{env, path::{Path, PathBuf}, process::Stdio};

use tokio::process::Command;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PrComposeSession {
    pub owner: String,
    pub infra_repo: String,
    pub infra_root: PathBuf,
    pub config_path: PathBuf,
    pub session_id: String,
}

impl PrComposeSession {
    pub fn for_pr(workspace_root: &Path, owner: &str, pr_number: u64, head_sha: &str) -> Result<Self, String> {
        validate_owner(owner)?;
        validate_sha(head_sha)?;
        let short_sha = &head_sha[..12.min(head_sha.len())];
        let infra_repo = format!("{owner}-infra");
        let infra_root = workspace_root.join(&infra_repo);
        let config_path = infra_root.join(".ores-compose.yaml");
        Ok(Self {
            owner: owner.to_string(),
            infra_repo,
            infra_root,
            config_path,
            session_id: format!("pr-{pr_number}-{short_sha}"),
        })
    }

    pub async fn up(&self) -> Result<(), String> {
        if !self.config_path.is_file() {
            return Err(format!(
                "org local-dev config not found: {} (expected *-infra/.ores-compose.yaml)",
                self.config_path.display()
            ));
        }
        run_ores_compose(&self.infra_root, &[
            "up",
            "--config",
            self.config_path.to_string_lossy().as_ref(),
            "--session",
            &self.session_id,
            "--detach",
        ]).await
    }

    pub async fn down(&self) -> Result<(), String> {
        if !self.config_path.is_file() {
            return Ok(());
        }
        run_ores_compose(&self.infra_root, &[
            "down",
            "--config",
            self.config_path.to_string_lossy().as_ref(),
            "--session",
            &self.session_id,
        ]).await
    }
}

pub fn workspace_root_from_env() -> PathBuf {
    env::var_os("BUILD_SERVER_ORG_WORKSPACE_ROOT")
        .map(PathBuf::from)
        .or_else(|| {
            env::var_os("HOME").map(|home| {
                PathBuf::from(home)
                    .join(".cache")
                    .join("gha-indie-worker")
                    .join("orgs")
            })
        })
        .unwrap_or_else(|| PathBuf::from("/tmp/gha-indie-worker-orgs"))
}

async fn run_ores_compose(cwd: &Path, args: &[&str]) -> Result<(), String> {
    let binary = env::var("BUILD_SERVER_ORES_COMPOSE_BIN")
        .unwrap_or_else(|_| "ores-compose".to_string());
    let output = Command::new(&binary)
        .args(args)
        .current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .await
        .map_err(|error| format!("failed to execute {binary}: {error}"))?;
    if output.status.success() {
        return Ok(());
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    let stdout = String::from_utf8_lossy(&output.stdout);
    Err(format!(
        "ores-compose exited with {}: stdout={} stderr={}",
        output.status,
        truncate(&stdout),
        truncate(&stderr)
    ))
}

fn truncate(input: &str) -> String {
    const LIMIT: usize = 2048;
    input.chars().take(LIMIT).collect()
}

fn validate_owner(owner: &str) -> Result<(), String> {
    if owner.is_empty()
        || owner.len() > 100
        || !owner
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    {
        return Err("invalid GitHub owner for infra repo discovery".to_string());
    }
    Ok(())
}

fn validate_sha(sha: &str) -> Result<(), String> {
    if sha.len() < 12 || sha.len() > 64 || !sha.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("invalid pull-request head SHA".to_string());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_infra_repo_and_collision_safe_pr_session() {
        let session = PrComposeSession::for_pr(
            Path::new("/work/gha-indie-worker"),
            "gha-indie-worker",
            42,
            "0123456789abcdef0123456789abcdef01234567",
        )
        .unwrap();
        assert_eq!(session.infra_repo, "gha-indie-worker-infra");
        assert_eq!(session.session_id, "pr-42-0123456789ab");
        assert!(session.config_path.ends_with("gha-indie-worker-infra/.ores-compose.yaml"));
    }

    #[test]
    fn rejects_owner_path_injection() {
        assert!(PrComposeSession::for_pr(
            Path::new("/tmp"),
            "../evil",
            1,
            "0123456789ab",
        )
        .is_err());
    }
}
