use crate::model::ProtocolError;
use crate::MAX_IDENTIFIER_BYTES;

pub fn validate_commit_sha(value: &str) -> Result<(), ProtocolError> {
    if value.len() != 40
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ProtocolError::new(
            "invalid_commit_sha",
            "commitSha must be exactly 40 lowercase hexadecimal characters",
        ));
    }
    Ok(())
}

pub fn validate_digest(name: &str, value: &str) -> Result<(), ProtocolError> {
    let Some(hex) = value.strip_prefix("sha256:") else {
        return Err(ProtocolError::new(
            "invalid_digest",
            format!("{name} must use sha256:<64 lowercase hex>"),
        ));
    };
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ProtocolError::new(
            "invalid_digest",
            format!("{name} must use sha256:<64 lowercase hex>"),
        ));
    }
    Ok(())
}

pub(super) fn validate_base_job_id(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_IDENTIFIER_BYTES
        || !value
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-'))
    {
        return Err(ProtocolError::new(
            "invalid_base_job_id",
            format!("invalid base job identifier {value:?}"),
        ));
    }
    Ok(())
}

pub(super) fn validate_instance_id(name: &str, value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_IDENTIFIER_BYTES
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric()
                || matches!(character, '_' | '-' | '[' | ']' | '.' | ',')
        })
    {
        return Err(ProtocolError::new(
            "invalid_job_instance_id",
            format!("{name} {value:?} contains unsupported characters"),
        ));
    }
    Ok(())
}
