use super::identifiers::{validate_base_job_id, validate_commit_sha, validate_digest};
use super::repository::validate_repository_url;
use crate::model::{BindingDocument, ProtocolError};
use crate::{BINDINGS_SCHEMA, MAX_BASE_JOBS};

pub(crate) fn validate_bindings_shape(bindings: &BindingDocument) -> Result<(), ProtocolError> {
    if bindings.schema_version != BINDINGS_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_bindings_schema",
            format!(
                "bindings schema must be {BINDINGS_SCHEMA:?}, got {:?}",
                bindings.schema_version
            ),
        ));
    }
    validate_repository_url(&bindings.repository_url)?;
    validate_commit_sha(&bindings.commit_sha)?;
    validate_digest("profileCatalogDigest", &bindings.profile_catalog_digest)?;
    if bindings.jobs.is_empty() || bindings.jobs.len() > MAX_BASE_JOBS {
        return Err(ProtocolError::new(
            "invalid_binding_count",
            format!("bindings must contain 1-{MAX_BASE_JOBS} base jobs"),
        ));
    }
    for base_job_id in bindings.jobs.keys() {
        validate_base_job_id(base_job_id)?;
    }
    Ok(())
}
