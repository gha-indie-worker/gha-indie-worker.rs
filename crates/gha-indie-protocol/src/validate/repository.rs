use std::path::{Component, Path};

use crate::model::ProtocolError;
use crate::{MAX_CONTEXT_DIR_BYTES, MAX_REPOSITORY_URL_BYTES};

pub fn validate_repository_url(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_REPOSITORY_URL_BYTES
        || value.trim() != value
        || value
            .chars()
            .any(|character| character.is_control() || character.is_whitespace())
    {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            format!("repositoryUrl must be printable and 1-{MAX_REPOSITORY_URL_BYTES} bytes"),
        ));
    }
    let Some(rest) = value.strip_prefix("https://") else {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must use https://",
        ));
    };
    let Some((authority, path)) = rest.split_once('/') else {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must include a DNS host and repository path",
        ));
    };
    let valid_authority = !authority.is_empty()
        && !authority.contains('@')
        && !authority.contains(':')
        && authority.split('.').all(|label| {
            !label.is_empty()
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
                && label
                    .as_bytes()
                    .first()
                    .is_some_and(|byte| byte.is_ascii_alphanumeric())
                && label
                    .as_bytes()
                    .last()
                    .is_some_and(|byte| byte.is_ascii_alphanumeric())
        });
    if !valid_authority {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl host must be lowercase DNS without credentials or a port",
        ));
    }
    let segments = path.split('/').collect::<Vec<_>>();
    if segments.len() < 2
        || segments.iter().any(|segment| {
            segment.is_empty()
                || matches!(*segment, "." | "..")
                || !segment
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        })
    {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl path must be clean ASCII without traversal, encoding, query, or fragment",
        ));
    }
    Ok(())
}

pub fn validate_context_dir(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_CONTEXT_DIR_BYTES
        || value.chars().any(char::is_control)
        || value.contains('\\')
    {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            format!("contextDir must be printable and 1-{MAX_CONTEXT_DIR_BYTES} bytes"),
        ));
    }
    let path = Path::new(value);
    if path.is_absolute() {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            "contextDir must be relative to the repository root",
        ));
    }
    let mut normal_components = 0_usize;
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::Normal(component) => {
                let part = component.to_str().ok_or_else(|| {
                    ProtocolError::new("invalid_context_dir", "contextDir must be UTF-8")
                })?;
                if !part
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
                {
                    return Err(ProtocolError::new(
                        "invalid_context_dir",
                        "contextDir contains characters unsafe for the worker boundary",
                    ));
                }
                normal_components += 1;
            }
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(ProtocolError::new(
                    "invalid_context_dir",
                    "contextDir must stay inside the repository root",
                ));
            }
        }
    }
    if normal_components == 0 && value != "." {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            "contextDir must resolve to the repository root or a child path",
        ));
    }
    Ok(())
}
