use std::collections::BTreeMap;

use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::model::{DispatchRequest, ProfileCatalog, ProtocolError, WorkflowPlan};
use crate::validate::{validate_catalog, validate_plan};

pub fn profile_catalog_digest(catalog: &ProfileCatalog) -> Result<String, ProtocolError> {
    validate_catalog(catalog)?;
    let mut profiles = catalog.profiles.clone();
    for profile in &mut profiles {
        profile.runner.capabilities.sort();
    }
    profiles.sort_by(|left, right| left.name.cmp(&right.name));
    digest_serializable(
        &ProfileCatalog {
            schema_version: catalog.schema_version.clone(),
            profiles,
        },
        "profile_catalog_serialization",
    )
}

pub fn workflow_plan_digest(plan: &WorkflowPlan) -> Result<String, ProtocolError> {
    validate_plan(plan)?;
    digest_serializable(plan, "plan_serialization")
}

pub(crate) fn stable_request_id(request: &DispatchRequest) -> Result<String, ProtocolError> {
    let mut identity = request.clone();
    identity.request_id.clear();
    identity.request_digest.clear();
    let digest = digest_serializable(&identity, "request_identity_serialization")?;
    let component = digest
        .strip_prefix("sha256:")
        .unwrap_or(&digest)
        .chars()
        .take(48)
        .collect::<String>();
    Ok(format!("gha:{component}"))
}

pub(crate) fn dispatch_request_digest(request: &DispatchRequest) -> Result<String, ProtocolError> {
    let mut unsigned = request.clone();
    unsigned.request_digest.clear();
    digest_serializable(&unsigned, "request_serialization")
}

fn digest_serializable<T: Serialize>(
    value: &T,
    error_code: &'static str,
) -> Result<String, ProtocolError> {
    let value = serde_json::to_value(value).map_err(|error| {
        ProtocolError::new(error_code, format!("failed to canonicalize JSON: {error}"))
    })?;
    let bytes = serde_json::to_vec(&canonicalize_json(value)).map_err(|error| {
        ProtocolError::new(
            error_code,
            format!("failed to serialize canonical JSON: {error}"),
        )
    })?;
    Ok(format!("sha256:{}", hex::encode(Sha256::digest(bytes))))
}

fn canonicalize_json(value: Value) -> Value {
    match value {
        Value::Array(values) => Value::Array(values.into_iter().map(canonicalize_json).collect()),
        Value::Object(values) => {
            let sorted = values
                .into_iter()
                .map(|(key, value)| (key, canonicalize_json(value)))
                .collect::<BTreeMap<_, _>>();
            Value::Object(sorted.into_iter().collect())
        }
        scalar => scalar,
    }
}
