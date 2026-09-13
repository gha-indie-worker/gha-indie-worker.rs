use std::collections::BTreeSet;

use serde::Serialize;
use serde_yaml::{Mapping, Value};

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WorkflowPlan {
    pub profiles: Vec<String>,
    pub jobs_seen: Vec<String>,
    pub unsupported: Vec<String>,
}

impl WorkflowPlan {
    pub fn is_supported(&self) -> bool {
        self.unsupported.is_empty() && !self.profiles.is_empty()
    }
}

/// Translate a fail-closed subset of GitHub Actions YAML into existing,
/// operator-reviewed gha-indie-worker profiles. We deliberately do not execute
/// arbitrary `run:` text on the laptop host. Instead the workflow is used as a
/// contract describing which toolchain/test family the PR expects, and the
/// matching fixed profile runs inside the worker's sandbox container while the
/// org ores-compose session is live.
pub fn plan_workflow_yaml(input: &str) -> Result<WorkflowPlan, String> {
    let root: Value = serde_yaml::from_str(input)
        .map_err(|error| format!("invalid GitHub workflow YAML: {error}"))?;
    let root = root
        .as_mapping()
        .ok_or_else(|| "workflow root must be a mapping".to_string())?;
    let jobs = mapping_get(root, "jobs")
        .and_then(Value::as_mapping)
        .ok_or_else(|| "workflow must declare jobs".to_string())?;

    let mut profiles = BTreeSet::new();
    let mut jobs_seen = Vec::new();
    let mut unsupported = Vec::new();

    for (job_name, job_value) in jobs {
        let Some(job_name) = job_name.as_str() else {
            unsupported.push("non-string job name".to_string());
            continue;
        };
        jobs_seen.push(job_name.to_string());
        let Some(job) = job_value.as_mapping() else {
            unsupported.push(format!("job {job_name} is not a mapping"));
            continue;
        };

        for forbidden in ["container", "strategy"] {
            if mapping_get(job, forbidden).is_some() {
                unsupported.push(format!("job {job_name} uses unsupported `{forbidden}`"));
            }
        }

        if let Some(runs_on) = mapping_get(job, "runs-on") {
            if !runs_on_is_linux(runs_on) {
                unsupported.push(format!("job {job_name} requires non-Linux runs-on"));
            }
        }

        let Some(steps) = mapping_get(job, "steps").and_then(Value::as_sequence) else {
            unsupported.push(format!("job {job_name} has no steps"));
            continue;
        };

        for (index, step_value) in steps.iter().enumerate() {
            let Some(step) = step_value.as_mapping() else {
                unsupported.push(format!("job {job_name} step {index} is not a mapping"));
                continue;
            };

            if let Some(uses) = mapping_get(step, "uses").and_then(Value::as_str) {
                classify_action(uses, &mut profiles, &mut unsupported, job_name, index);
            }
            if let Some(run) = mapping_get(step, "run").and_then(Value::as_str) {
                classify_run(run, &mut profiles);
            }
        }
    }

    if profiles.is_empty() && unsupported.is_empty() {
        unsupported.push("workflow did not map to a supported verification profile".to_string());
    }

    Ok(WorkflowPlan {
        profiles: profiles.into_iter().collect(),
        jobs_seen,
        unsupported,
    })
}

fn mapping_get<'a>(mapping: &'a Mapping, key: &str) -> Option<&'a Value> {
    mapping.get(Value::String(key.to_string()))
}

fn runs_on_is_linux(value: &Value) -> bool {
    match value {
        Value::String(label) => {
            let label = label.to_ascii_lowercase();
            label.contains("ubuntu") || label.contains("linux") || label == "self-hosted"
        }
        Value::Sequence(labels) => labels
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_ascii_lowercase)
            .any(|label| label.contains("ubuntu") || label.contains("linux")),
        _ => false,
    }
}

fn classify_action(
    uses: &str,
    profiles: &mut BTreeSet<String>,
    unsupported: &mut Vec<String>,
    job: &str,
    step: usize,
) {
    let lower = uses.to_ascii_lowercase();
    if lower.starts_with("actions/checkout@") {
        return;
    }
    if lower.starts_with("dtolnay/rust-toolchain@")
        || lower.starts_with("actions-rs/toolchain@")
    {
        profiles.insert("rust-verify".to_string());
        return;
    }
    if lower.starts_with("actions/setup-node@") {
        profiles.insert("node-verify".to_string());
        return;
    }
    if lower.starts_with("actions/setup-python@") {
        profiles.insert("python-verify".to_string());
        return;
    }
    if lower.contains("flutter-action@") || lower.contains("setup-flutter@") {
        profiles.insert("flutter-verify".to_string());
        return;
    }
    unsupported.push(format!("job {job} step {step} uses unsupported action {uses}"));
}

fn classify_run(run: &str, profiles: &mut BTreeSet<String>) {
    let lower = run.to_ascii_lowercase();
    if lower.contains("cargo ") || lower.contains("cargo\n") || lower.starts_with("cargo") {
        profiles.insert("rust-verify".to_string());
    }
    if lower.contains("flutter ") || lower.starts_with("flutter") {
        profiles.insert("flutter-verify".to_string());
    }
    if lower.contains("playwright") {
        profiles.insert("playwright".to_string());
    } else if lower.contains("puppeteer") {
        profiles.insert("puppeteer".to_string());
    } else if lower.contains("npm ")
        || lower.contains("pnpm ")
        || lower.contains("yarn ")
        || lower.starts_with("npm")
        || lower.starts_with("pnpm")
        || lower.starts_with("yarn")
    {
        profiles.insert("node-verify".to_string());
    }
    if lower.contains("pytest") || lower.contains("python -m") || lower.starts_with("python ") {
        profiles.insert("python-verify".to_string());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rust_workflow_maps_to_fixed_rust_profile() {
        let yaml = r#"
name: CI
on: [pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo fmt --all -- --check
      - run: cargo test --locked --all-targets
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(plan.is_supported());
        assert_eq!(plan.profiles, vec!["rust-verify"]);
    }

    #[test]
    fn unsupported_third_party_action_fails_closed() {
        let yaml = r#"
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: vendor/magic-action@v1
      - run: cargo test
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(!plan.is_supported());
        assert!(plan.unsupported[0].contains("vendor/magic-action"));
    }

    #[test]
    fn macos_job_is_not_claimed_supported() {
        let yaml = r#"
jobs:
  test:
    runs-on: macos-latest
    steps:
      - run: cargo test
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(!plan.is_supported());
        assert!(plan.unsupported.iter().any(|item| item.contains("non-Linux")));
    }
}
