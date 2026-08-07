use serde_json::json;

use super::*;

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
    assert_eq!(batch.requests[0].runner.platform, "linux");
    assert_eq!(batch.requests[0].runner.architecture, "x64");
    assert_eq!(
        batch.requests[0].runner.capabilities,
        vec!["cargo-cache", "native"]
    );
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
fn binds_native_macos_profile_to_apple_silicon_job() {
    let mut mac_job = job("build", "build", &[]);
    mac_job.runs_on = ["self-hosted", "gha-indie-worker", "macos", "arm64"]
        .into_iter()
        .map(str::to_string)
        .collect();
    let workflow = WorkflowPlan {
        schema_version: PLAN_SCHEMA.to_string(),
        name: Some("macos-native".to_string()),
        job_order: vec!["build".to_string()],
        jobs: vec![mac_job],
    };
    let catalog = ProfileCatalog {
        schema_version: PROFILE_CATALOG_SCHEMA.to_string(),
        profiles: vec![ProfileRecord {
            name: "macos-xcode".to_string(),
            digest: MAC_DIGEST.to_string(),
            runner: runner("macos", "arm64", &["xcode", "native", "ios-simulator"]),
        }],
    };
    let bindings = BindingDocument {
        schema_version: BINDINGS_SCHEMA.to_string(),
        repository_url: "https://github.com/gha-indie-worker/example.git".to_string(),
        commit_sha: COMMIT.to_string(),
        profile_catalog_digest: profile_catalog_digest(&catalog).unwrap(),
        jobs: BTreeMap::from([(
            "build".to_string(),
            JobBinding {
                profile: "macos-xcode".to_string(),
                profile_digest: MAC_DIGEST.to_string(),
                context_dir: None,
            },
        )]),
    };

    let batch = bind_plan(&workflow, &catalog, &bindings).unwrap();
    assert_eq!(batch.requests[0].runner.platform, "macos");
    assert_eq!(batch.requests[0].runner.architecture, "arm64");
    assert_eq!(
        batch.requests[0].runner.capabilities,
        vec!["ios-simulator", "native", "xcode"]
    );
}

#[test]
fn rejects_profile_and_job_runner_target_mismatch() {
    let mut workflow = plan();
    workflow.jobs[0].runs_on = ["self-hosted", "gha-indie-worker", "windows", "x64"]
        .into_iter()
        .map(str::to_string)
        .collect();
    let catalog = catalog();
    let bindings = bindings(&catalog);
    assert_eq!(
        bind_plan(&workflow, &catalog, &bindings).unwrap_err().code,
        "profile_runner_target_mismatch"
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
    assert_ne!(
        base.requests[0].request_id,
        changed_commit.requests[0].request_id
    );

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
fn digests_are_stable_under_semantically_unordered_inputs() {
    let first = catalog();
    let mut second = first.clone();
    second.profiles.reverse();
    for profile in &mut second.profiles {
        profile.runner.capabilities.reverse();
    }
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
