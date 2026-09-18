//! Reusable components for the independent GitHub Actions-compatible worker.

extern crate self as serde_yaml;
extern crate serde_yaml as serde_yaml_real;

pub mod workflow;
mod workflow_guard;
mod workflow_yaml;

use serde::de::DeserializeOwned;
pub use workflow_guard::{
    MAX_BASE_JOBS, MAX_EXPANDED_PLAN_BYTES, MAX_FLOW_COLLECTION_DEPTH, MAX_PLANNED_JOBS,
    MAX_PLANNED_STEP_CLONES, MAX_STEPS_PER_JOB, MAX_WORKFLOW_SOURCE_BYTES,
};

/// Preflights workflow YAML through the shared bounded parser, then decodes it
/// with the canonical YAML value model expected by the fixed-profile engine.
///
/// The preflight rejects duplicate keys, aliases, anchors, merge keys, tags,
/// multiple documents, tabs, malformed indentation, excessive source size,
/// and excessive parser depth before the general decoder can normalize them.
/// Executor-specific unsupported fields remain values in the resulting plan so
/// callers receive an explicit non-executable compatibility report instead of
/// a misleading YAML syntax error.
pub fn strict_from_str<T>(input: &str) -> Result<T, String>
where
    T: DeserializeOwned,
{
    workflow_guard::validate_source(input)?;
    workflow_yaml::parse_yaml(input).map_err(|error| error.to_string())?;
    serde_yaml_real::from_str(input).map_err(|error| error.to_string())
}

/// Decodes the planner's bounded workflow subset and applies aggregate semantic
/// limits before matrix or step expansion.
///
/// This crate-local alias intentionally matches the planner's narrow
/// `serde_yaml::from_str` call site without granting those stricter planner
/// semantics to the fixed-profile compatibility-report path.
pub(crate) fn from_str<T>(input: &str) -> Result<T, String>
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

fn is_deferred_matrix_error(error: &str) -> bool {
    error.starts_with("job ") && error.contains(" matrix ")
}
