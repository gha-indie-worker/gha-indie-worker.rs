use std::collections::BTreeSet;

use serde::Serialize;

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WorkflowPlan {
    pub pull_request_trigger: bool,
    pub profiles: Vec<String>,
    pub jobs_seen: Vec<String>,
    pub unsupported: Vec<String>,
}

impl WorkflowPlan {
    pub fn is_supported(&self) -> bool {
        self.pull_request_trigger && self.unsupported.is_empty() && !self.profiles.is_empty()
    }
}

/// Translate a deliberately small, fail-closed subset of GitHub Actions YAML
/// into existing operator-reviewed gha-indie-worker profiles.
///
/// This parser is intentionally dependency-free so `cargo --locked` remains
/// valid on the laptop worker. It understands the structural pieces needed for
/// CI classification (`on`, `jobs`, `runs-on`, `steps`, `uses`, and `run`) and
/// fails closed on workflow features that materially change PR execution
/// semantics. It does *not* execute `run:` text on the laptop host; the text
/// only selects a fixed sandbox profile.
pub fn plan_workflow_yaml(input: &str) -> Result<WorkflowPlan, String> {
    if input.contains('\t') {
        return Err(
            "workflow YAML uses tab indentation; unsupported by the local fail-closed parser"
                .to_string(),
        );
    }

    let pull_request_trigger = detects_pull_request_trigger(input)?;
    let mut in_jobs = false;
    let mut current_job: Option<String> = None;
    let mut in_steps = false;
    let mut profiles = BTreeSet::new();
    let mut jobs_seen = Vec::new();
    let mut unsupported = Vec::new();

    for (line_number, raw_line) in input.lines().enumerate() {
        let without_comment = strip_comment(raw_line);
        if without_comment.trim().is_empty() {
            continue;
        }
        let indent = without_comment.len() - without_comment.trim_start_matches(' ').len();
        let trimmed = without_comment.trim();

        if indent == 0 {
            if trimmed == "jobs:" {
                in_jobs = true;
                current_job = None;
                in_steps = false;
                continue;
            }
            if in_jobs {
                // A new top-level key after jobs ends the jobs mapping.
                in_jobs = false;
                current_job = None;
                in_steps = false;
            }
            continue;
        }

        if !in_jobs {
            continue;
        }

        if indent == 2 && is_mapping_key(trimmed) {
            let job = trimmed
                .trim_end_matches(':')
                .trim()
                .trim_matches(['\'', '"']);
            if job.is_empty() {
                unsupported.push(format!("line {} has an empty job name", line_number + 1));
                current_job = None;
            } else {
                current_job = Some(job.to_string());
                jobs_seen.push(job.to_string());
            }
            in_steps = false;
            continue;
        }

        let Some(job) = current_job.as_deref() else {
            continue;
        };

        if indent == 4 {
            in_steps = trimmed == "steps:";
            if let Some(value) = yaml_scalar_value(trimmed, "runs-on:") {
                if !runs_on_is_linux(value) {
                    unsupported.push(format!("job {job} requires non-Linux runs-on {value:?}"));
                }
            }
            if trimmed.starts_with("container:") {
                unsupported.push(format!("job {job} uses unsupported `container`"));
            }
            if trimmed.starts_with("strategy:") {
                unsupported.push(format!("job {job} uses unsupported `strategy`"));
            }
            if trimmed.starts_with("services:") {
                // Service containers should be modeled in the org's
                // *-infra/.ores-compose.yaml instead of being started a second
                // time by the workflow interpreter.
                unsupported.push(format!(
                    "job {job} declares workflow services; move them to *-infra/.ores-compose.yaml"
                ));
            }
            continue;
        }

        if !in_steps || indent < 6 {
            continue;
        }

        if let Some(uses) = step_value(trimmed, "uses:") {
            classify_action(uses, &mut profiles, &mut unsupported, job, line_number + 1);
            continue;
        }

        if let Some(run) = step_value(trimmed, "run:") {
            if run != "|" && run != ">" && !run.is_empty() {
                classify_run(run, &mut profiles);
            }
            continue;
        }

        // Continuation lines belonging to a block-style `run: |` are safe to
        // inspect for toolchain intent. YAML keys such as env/name/with are
        // ignored unless their text itself is an executable-looking command.
        classify_run(trimmed, &mut profiles);
    }

    if jobs_seen.is_empty() && input.lines().any(|line| line.trim() == "jobs:") {
        return Err("workflow jobs mapping is empty or unsupported".to_string());
    }
    if pull_request_trigger && jobs_seen.is_empty() {
        return Err("pull-request workflow must declare jobs".to_string());
    }
    if pull_request_trigger && profiles.is_empty() && unsupported.is_empty() {
        unsupported.push(
            "pull-request workflow did not map to a supported verification profile".to_string(),
        );
    }

    Ok(WorkflowPlan {
        pull_request_trigger,
        profiles: profiles.into_iter().collect(),
        jobs_seen,
        unsupported,
    })
}

fn detects_pull_request_trigger(input: &str) -> Result<bool, String> {
    let mut in_on_block = false;
    for raw_line in input.lines() {
        let line = strip_comment(raw_line);
        if line.trim().is_empty() {
            continue;
        }
        let indent = line.len() - line.trim_start_matches(' ').len();
        let trimmed = line.trim();

        if indent == 0 {
            in_on_block = false;
            if let Some(value) = yaml_scalar_value(trimmed, "on:") {
                if value.is_empty() {
                    in_on_block = true;
                    continue;
                }
                let normalized = value
                    .trim_matches(['[', ']', '\'', '"'])
                    .split(',')
                    .map(str::trim)
                    .map(|item| item.trim_matches(['\'', '"']))
                    .collect::<Vec<_>>();
                return Ok(normalized.iter().any(|item| *item == "pull_request"));
            }
            // YAML 1.1 parsers historically treat `on` specially, but GitHub
            // workflow syntax requires the literal top-level key. Quoted keys
            // are accepted here as well.
            if let Some(rest) = trimmed
                .strip_prefix("'on':")
                .or_else(|| trimmed.strip_prefix("\"on\":"))
            {
                let value = rest.trim();
                if value.is_empty() {
                    in_on_block = true;
                    continue;
                }
                let normalized = value
                    .trim_matches(['[', ']', '\'', '"'])
                    .split(',')
                    .map(str::trim)
                    .map(|item| item.trim_matches(['\'', '"']))
                    .collect::<Vec<_>>();
                return Ok(normalized.iter().any(|item| *item == "pull_request"));
            }
        } else if in_on_block && indent >= 2 {
            let event = trimmed
                .split_once(':')
                .map(|(key, _)| key)
                .unwrap_or(trimmed)
                .trim_matches(['\'', '"']);
            if event == "pull_request" {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

fn strip_comment(line: &str) -> &str {
    // GitHub action refs and shell snippets can contain `#`; only treat a hash
    // as a YAML comment delimiter when preceded by whitespace.
    let bytes = line.as_bytes();
    for index in 0..bytes.len() {
        if bytes[index] == b'#' && (index == 0 || bytes[index - 1].is_ascii_whitespace()) {
            return &line[..index];
        }
    }
    line
}

fn is_mapping_key(trimmed: &str) -> bool {
    trimmed.ends_with(':') && !trimmed.starts_with('-') && !trimmed.contains("::")
}

fn yaml_scalar_value<'a>(trimmed: &'a str, key: &str) -> Option<&'a str> {
    let value = trimmed.strip_prefix(key)?.trim();
    Some(value.trim_matches(['\'', '"']))
}

fn step_value<'a>(trimmed: &'a str, key: &str) -> Option<&'a str> {
    let trimmed = trimmed.strip_prefix("- ").unwrap_or(trimmed);
    yaml_scalar_value(trimmed, key)
}

fn runs_on_is_linux(label: &str) -> bool {
    let lower = label.to_ascii_lowercase();
    lower.contains("ubuntu") || lower.contains("linux") || lower.contains("self-hosted")
}

fn classify_action(
    uses: &str,
    profiles: &mut BTreeSet<String>,
    unsupported: &mut Vec<String>,
    job: &str,
    line: usize,
) {
    let uses = uses.trim_matches(['\'', '"']);
    let lower = uses.to_ascii_lowercase();
    if lower.starts_with("actions/checkout@") {
        return;
    }
    if lower.starts_with("dtolnay/rust-toolchain@") || lower.starts_with("actions-rs/toolchain@") {
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
    if lower.starts_with("actions/cache@") || lower.starts_with("actions/upload-artifact@") {
        // Cache/upload semantics do not determine whether the verification
        // command itself passes, so the first external-CI version treats these
        // as optional compatibility hints rather than executing the action.
        return;
    }
    unsupported.push(format!(
        "job {job} line {line} uses unsupported action {uses}"
    ));
}

fn classify_run(run: &str, profiles: &mut BTreeSet<String>) {
    let lower = run.to_ascii_lowercase();
    if lower.contains("cargo ") || lower.starts_with("cargo") {
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
    fn block_pull_request_trigger_is_detected() {
        let yaml = r#"
on:
  push:
  pull_request:
    branches: [main]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: cargo test
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(plan.pull_request_trigger);
        assert!(plan.is_supported());
    }

    #[test]
    fn release_workflow_does_not_gate_pr_even_if_actions_are_unsupported() {
        let yaml = r#"
on: push
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: vendor/deploy-action@v9
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(!plan.pull_request_trigger);
        assert!(!plan.is_supported());
    }

    #[test]
    fn block_run_is_classified() {
        let yaml = r#"
on: pull_request
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: verify
        run: |
          cargo fmt --all -- --check
          cargo test --locked
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert_eq!(plan.profiles, vec!["rust-verify"]);
    }

    #[test]
    fn unsupported_third_party_action_fails_closed() {
        let yaml = r#"
on: pull_request
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
on: pull_request
jobs:
  test:
    runs-on: macos-latest
    steps:
      - run: cargo test
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(!plan.is_supported());
        assert!(plan
            .unsupported
            .iter()
            .any(|item| item.contains("non-Linux")));
    }

    #[test]
    fn workflow_service_containers_fail_closed_in_favor_of_ores_compose() {
        let yaml = r#"
on: pull_request
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:18
    steps:
      - run: cargo test
"#;
        let plan = plan_workflow_yaml(yaml).unwrap();
        assert!(!plan.is_supported());
        assert!(plan
            .unsupported
            .iter()
            .any(|item| item.contains("*-infra/.ores-compose.yaml")));
    }
}
