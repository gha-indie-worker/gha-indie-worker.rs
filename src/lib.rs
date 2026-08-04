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

/// Decodes the bounded workflow-YAML subset without adding a registry crate.
///
/// This crate-local alias intentionally matches the narrow `serde_yaml::from_str`
/// call site in the planner while preserving the repository's reviewed lockfile.
pub(crate) fn from_str<T>(input: &str) -> Result<T, String>
where
    T: DeserializeOwned,
{
    workflow_guard::validate_source(input)?;
    let value = workflow_yaml::parse_yaml(input).map_err(|error| error.to_string())?;
    workflow_guard::validate_document(&value)?;
    serde_json::from_value(value).map_err(|error| error.to_string())
}
