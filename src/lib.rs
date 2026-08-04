//! Reusable components for the independent GitHub Actions-compatible worker.

extern crate self as serde_yaml;

mod workflow_yaml;
pub mod workflow;

use serde::de::DeserializeOwned;

/// Decodes the bounded workflow-YAML subset without adding a registry crate.
///
/// This crate-local alias intentionally matches the narrow `serde_yaml::from_str`
/// call site in the planner while preserving the repository's reviewed lockfile.
pub(crate) fn from_str<T>(input: &str) -> Result<T, String>
where
    T: DeserializeOwned,
{
    let value = workflow_yaml::parse_yaml(input).map_err(|error| error.to_string())?;
    serde_json::from_value(value).map_err(|error| error.to_string())
}
