use std::collections::BTreeSet;

use super::identifiers::validate_digest;
use crate::model::{ProfileCatalog, ProtocolError};
use crate::{MAX_PROFILES, MAX_PROFILE_NAME_BYTES, PROFILE_CATALOG_SCHEMA};

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
        if profile.platform != "linux" {
            return Err(ProtocolError::new(
                "unsupported_profile_platform",
                format!("profile {:?} must target Linux", profile.name),
            ));
        }
        if !names.insert(profile.name.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_profile",
                format!("profile catalog repeats {:?}", profile.name),
            ));
        }
    }
    Ok(())
}

pub(crate) fn validate_profile_name(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > MAX_PROFILE_NAME_BYTES {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            format!("profile names must be 1-{MAX_PROFILE_NAME_BYTES} bytes"),
        ));
    }
    let mut characters = value.chars();
    let first = characters.next().expect("non-empty profile name");
    if !first.is_ascii_lowercase() && !first.is_ascii_digit() {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            "profile name must start with a lowercase letter or digit",
        ));
    }
    if !characters.all(|character| {
        character.is_ascii_lowercase() || character.is_ascii_digit() || character == '-'
    }) {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            "profile name may contain lowercase letters, digits, and '-' only",
        ));
    }
    Ok(())
}
