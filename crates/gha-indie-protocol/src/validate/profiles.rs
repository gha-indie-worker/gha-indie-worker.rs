use std::collections::BTreeSet;

use super::identifiers::validate_digest;
use crate::model::{ProfileCatalog, ProtocolError, RunnerTarget};
use crate::{
    ALLOWED_RUNNER_ARCHITECTURES, ALLOWED_RUNNER_PLATFORMS, MAX_CAPABILITY_NAME_BYTES,
    MAX_PROFILES, MAX_PROFILE_CAPABILITIES, MAX_PROFILE_NAME_BYTES, PROFILE_CATALOG_SCHEMA,
};

pub(crate) fn validate_catalog(catalog: &ProfileCatalog) -> Result<(), ProtocolError> {
    if catalog.schema_version != PROFILE_CATALOG_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_profile_catalog_schema",
            format!(
                "profile catalog schema must be {PROFILE_CATALOG_SCHEMA:?}, got {:?}",
                catalog.schema_version
            ),
        ));
    }
    if catalog.profiles.is_empty() || catalog.profiles.len() > MAX_PROFILES {
        return Err(ProtocolError::new(
            "invalid_profile_count",
            format!("profile catalog must contain 1-{MAX_PROFILES} profiles"),
        ));
    }
    let mut names = BTreeSet::new();
    for profile in &catalog.profiles {
        validate_profile_name(&profile.name)?;
        validate_digest("profile digest", &profile.digest)?;
        validate_runner_target(&profile.runner)?;
        if !names.insert(profile.name.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_profile",
                format!("profile catalog repeats {:?}", profile.name),
            ));
        }
    }
    Ok(())
}

pub(crate) fn validate_runner_target(target: &RunnerTarget) -> Result<(), ProtocolError> {
    if !ALLOWED_RUNNER_PLATFORMS.contains(&target.platform.as_str()) {
        return Err(ProtocolError::new(
            "unsupported_runner_platform",
            format!(
                "runner platform {:?} is unsupported; allowed platforms are {ALLOWED_RUNNER_PLATFORMS:?}",
                target.platform
            ),
        ));
    }
    if !ALLOWED_RUNNER_ARCHITECTURES.contains(&target.architecture.as_str()) {
        return Err(ProtocolError::new(
            "unsupported_runner_architecture",
            format!(
                "runner architecture {:?} is unsupported; allowed architectures are {ALLOWED_RUNNER_ARCHITECTURES:?}",
                target.architecture
            ),
        ));
    }
    if target.capabilities.len() > MAX_PROFILE_CAPABILITIES {
        return Err(ProtocolError::new(
            "too_many_runner_capabilities",
            format!(
                "runner target has {} capabilities; maximum is {MAX_PROFILE_CAPABILITIES}",
                target.capabilities.len()
            ),
        ));
    }

    let mut capabilities = BTreeSet::new();
    for capability in &target.capabilities {
        validate_capability_name(capability)?;
        if !capabilities.insert(capability.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_runner_capability",
                format!("runner target repeats capability {capability:?}"),
            ));
        }
    }
    if target.platform != "linux" && !capabilities.contains("native") {
        return Err(ProtocolError::new(
            "native_capability_required",
            format!(
                "runner platform {:?} must declare the native capability",
                target.platform
            ),
        ));
    }
    Ok(())
}

pub(crate) fn validate_profile_name(value: &str) -> Result<(), ProtocolError> {
    validate_slug(
        value,
        MAX_PROFILE_NAME_BYTES,
        "invalid_profile_name",
        "profile name",
    )
}

fn validate_capability_name(value: &str) -> Result<(), ProtocolError> {
    validate_slug(
        value,
        MAX_CAPABILITY_NAME_BYTES,
        "invalid_runner_capability",
        "runner capability",
    )
}

fn validate_slug(
    value: &str,
    max_bytes: usize,
    error_code: &'static str,
    field_name: &str,
) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > max_bytes {
        return Err(ProtocolError::new(
            error_code,
            format!("{field_name} must be 1-{max_bytes} bytes"),
        ));
    }
    let mut characters = value.chars();
    let first = characters.next().expect("non-empty validated slug");
    if !first.is_ascii_lowercase() && !first.is_ascii_digit() {
        return Err(ProtocolError::new(
            error_code,
            format!("{field_name} must start with a lowercase letter or digit"),
        ));
    }
    if !characters.all(|character| {
        character.is_ascii_lowercase() || character.is_ascii_digit() || character == '-'
    }) {
        return Err(ProtocolError::new(
            error_code,
            format!("{field_name} may contain lowercase letters, digits, and '-' only"),
        ));
    }
    Ok(())
}
