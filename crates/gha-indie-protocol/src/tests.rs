use std::collections::{BTreeMap, BTreeSet};

use serde_json::json;

use crate::*;

const COMMIT: &str = "0123456789abcdef0123456789abcdef01234567";
const OTHER_COMMIT: &str = "1123456789abcdef0123456789abcdef01234567";
const RUST_DIGEST: &str = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
const NODE_DIGEST: &str = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
const MAC_DIGEST: &str = "sha256:3333333333333333333333333333333333333333333333333333333333333333";
const WINDOWS_DIGEST: &str =
    "sha256:4444444444444444444444444444444444444444444444444444444444444444";

fn runner(platform: &str, architecture: &str, capabilities: &[&str]) -> RunnerTarget {
    RunnerTarget {
        platform: platform.to_string(),
        architecture: architecture.to_string(),
        capabilities: capabilities
            .iter()
            .map(|capability| (*capability).to_string())
            .collect(),
    }
}

fn empty_step() -> PlannedStep {
    PlannedStep {
        index: 0,
        id: None,
        name: None,
        condition: None,
        uses: None,
        run: None,
        shell: None,
        working_directory: None,
        with: BTreeMap::new(),
        env: BTreeMap::new(),
        continue_on_error: None,
        timeout_minutes: None,
    }
}

fn job(id: &str, base_job_id: &str, needs_instances: &[&str]) -> PlannedJob {
    let needs = needs_instances
        .iter()
        .map(|value| value.split_once('[').map_or(*value, |(base, _)| base))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .map(str::to_string)
        .collect();
    PlannedJob {
        id: id.to_string(),
        base_job_id: base_job_id.to_string(),
        name: base_job_id.to_string(),
        needs,
        needs_instances: needs_instances
            .iter()
            .map(|value| (*value).to_string())
            .collect(),
        runs_on: vec![
            "self-hosted".to_string(),
            "gha-indie-worker".to_string(),
            "linux".to_string(),
            "x64".to_string(),
        ],
        reusable_workflow: None,
        condition: None,
        matrix: BTreeMap::new(),
        matrix_expression: None,
        env: BTreeMap::new(),
        steps: Vec::new(),
        fail_fast: true,
        max_parallel: None,
        timeout_minutes: None,
        continue_on_error: None,
        outputs: BTreeMap::new(),
    }
}

fn plan() -> WorkflowPlan {
    let mut stable = job("build[1]", "build", &[]);
    stable.matrix.insert("rust".to_string(), json!("stable"));
    let mut beta = job("build[2]", "build", &[]);
    beta.matrix.insert("rust".to_string(), json!("beta"));
    WorkflowPlan {
        schema_version: PLAN_SCHEMA.to_string(),
        name: Some("profile-only".to_string()),
        job_order: vec!["build".to_string(), "report".to_string()],
        jobs: vec![
            stable,
            beta,
            job("report", "report", &["build[1]", "build[2]"]),
        ],
    }
}

fn catalog() -> ProfileCatalog {
    ProfileCatalog {
        schema_version: PROFILE_CATALOG_SCHEMA.to_string(),
        profiles: vec![
            ProfileRecord {
                name: "rust-verify".to_string(),
                digest: RUST_DIGEST.to_string(),
                runner: runner("linux", "x64", &["native", "cargo-cache"]),
            },
            ProfileRecord {
                name: "node-verify".to_string(),
                digest: NODE_DIGEST.to_string(),
                runner: runner("linux", "x64", &[]),
            },
        ],
    }
}

fn bindings(catalog: &ProfileCatalog) -> BindingDocument {
    BindingDocument {
        schema_version: BINDINGS_SCHEMA.to_string(),
        repository_url: "https://github.com/gha-indie-worker/example.git".to_string(),
        commit_sha: COMMIT.to_string(),
        profile_catalog_digest: profile_catalog_digest(catalog).unwrap(),
        jobs: BTreeMap::from([
            (
                "build".to_string(),
                JobBinding {
                    profile: "rust-verify".to_string(),
                    profile_digest: RUST_DIGEST.to_string(),
                    context_dir: Some("crates/core".to_string()),
                },
            ),
            (
                "report".to_string(),
                JobBinding {
                    profile: "node-verify".to_string(),
                    profile_digest: NODE_DIGEST.to_string(),
                    context_dir: None,
                },
            ),
        ]),
    }
}

mod binding;
mod validation;
