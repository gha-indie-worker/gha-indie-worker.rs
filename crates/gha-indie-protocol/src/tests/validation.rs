use serde_json::json;

use super::*;

#[test]
fn runner_labels_fail_closed() {
    let catalog = catalog();
    let bindings = bindings(&catalog);

    let mut missing_indie = plan();
    missing_indie.jobs[0].runs_on = ["self-hosted", "linux", "x64"]
        .into_iter()
        .map(str::to_string)
        .collect();
    assert_eq!(
        bind_plan(&missing_indie, &catalog, &bindings)
            .unwrap_err()
            .code,
        "missing_indie_runner_label"
    );

    let mut ambiguous_platform = plan();
    ambiguous_platform.jobs[0]
        .runs_on
        .push("windows".to_string());
    assert_eq!(
        bind_plan(&ambiguous_platform, &catalog, &bindings)
            .unwrap_err()
            .code,
        "ambiguous_runner_platform"
    );

    let mut ambiguous_architecture = plan();
    ambiguous_architecture.jobs[0]
        .runs_on
        .push("arm64".to_string());
    assert_eq!(
        bind_plan(&ambiguous_architecture, &catalog, &bindings)
            .unwrap_err()
            .code,
        "ambiguous_runner_architecture"
    );

    let mut unsupported = plan();
    unsupported.jobs[0]
        .runs_on
        .push("ubuntu-latest".to_string());
    assert_eq!(
        bind_plan(&unsupported, &catalog, &bindings)
            .unwrap_err()
            .code,
        "unsupported_runner_label"
    );
}

#[test]
fn profile_runner_targets_fail_closed() {
    let valid = catalog();
    let valid_bindings = bindings(&valid);

    let mut missing_native = valid.clone();
    missing_native.profiles[0].runner.platform = "macos".to_string();
    missing_native.profiles[0].runner.architecture = "arm64".to_string();
    missing_native.profiles[0].runner.capabilities.clear();
    assert_eq!(
        bind_plan(&plan(), &missing_native, &valid_bindings)
            .unwrap_err()
            .code,
        "native_capability_required"
    );

    let mut unsupported_platform = valid.clone();
    unsupported_platform.profiles[0].runner.platform = "freebsd".to_string();
    assert_eq!(
        bind_plan(&plan(), &unsupported_platform, &valid_bindings)
            .unwrap_err()
            .code,
        "unsupported_runner_platform"
    );

    let mut duplicate_capability = valid;
    duplicate_capability.profiles[0].runner.capabilities =
        vec!["native".to_string(), "native".to_string()];
    assert_eq!(
        bind_plan(&plan(), &duplicate_capability, &valid_bindings)
            .unwrap_err()
            .code,
        "duplicate_runner_capability"
    );
}

#[test]
fn rejects_inconsistent_incomplete_or_backward_dependencies() {
    let catalog = catalog();
    let bindings = bindings(&catalog);

    let mut missing_base = plan();
    missing_base.jobs[2].needs.clear();
    assert_eq!(
        bind_plan(&missing_base, &catalog, &bindings)
            .unwrap_err()
            .code,
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
    step.run = Some("echo caller-controlled".to_string());
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
