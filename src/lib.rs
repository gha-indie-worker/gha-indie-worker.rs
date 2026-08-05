//! Reusable components for the independent GitHub Actions-compatible worker.

extern crate self as serde_yaml;

pub mod workflow;
mod workflow_guard;
mod workflow_yaml;

use serde::de::DeserializeOwned;
pub use workflow_guard::{
    MAX_BASE_JOBS, MAX_EXPANDED_PLAN_BYTES, MAX_FLOW_COLLECTION_DEPTH, MAX_PLANNED_JOBS,
    MAX_PLANNED_STEP_CLONES, MAX_STEPS_PER_JOB, MAX_WORKFLOW_SOURCE_BYTES,
};

/// Decodes the bounded workflow-YAML subset through the shared strict
/// admission layer.
///
/// This is the only YAML decoder that executable and planner entry points may
/// use. It rejects ambiguous YAML before deserialization, applies aggregate
/// workflow limits, and preserves stable typed-planner error classes for
/// malformed matrix semantics.
pub fn strict_from_str<T>(input: &str) -> Result<T, String>
where
    T: DeserializeOwned,
{
    workflow_guard::validate_source(input)?;
    let value = workflow_yaml::parse_yaml(input).map_err(|error| error.to_string())?;
    if let Err(error) = workflow_guard::validate_document(&value) {
        // The guard estimates only well-formed static matrices. Defer malformed
        // matrix semantics to the typed planner so its stable `invalid_matrix`
        // and `matrix_too_large` error classes remain part of the public API.
        // Aggregate workflow limits and unsupported execution fields still fail
        // here, before any concrete jobs or repeated step bodies are allocated.
        if !is_deferred_matrix_error(&error) {
            return Err(error);
        }
    }
    serde_json::from_value(value).map_err(|error| error.to_string())
}

/// Crate-local compatibility alias for the planner's narrow
/// `serde_yaml::from_str` call site.
pub(crate) fn from_str<T>(input: &str) -> Result<T, String>
where
    T: DeserializeOwned,
{
    strict_from_str(input)
}

fn is_deferred_matrix_error(error: &str) -> bool {
    error.starts_with("job ") && error.contains(" matrix ")
}
