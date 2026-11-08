use gha_capacity_broker::*;

fn policy() -> OrgPolicy {
    OrgPolicy {
        included_minutes: Some(1_000.0),
        warn_percent: 75.0,
        self_hosted_percent: 90.0,
        hard_stop_percent: 100.0,
        prefer_self_hosted: false,
        self_hosted_ready: false,
        build_server_enabled: false,
        hosted_runs_on: vec!["ubuntu-latest".to_string()],
        self_hosted_runs_on: vec!["self-hosted".to_string(), "gha-indie-worker".to_string()],
        selected_repository_ids: vec![42],
    }
}

#[test]
fn gross_actions_minutes_drive_routing_evidence() {
    let usage = BillingUsageResponse {
        usage_items: vec![BillingUsageItem {
            product: "Actions".to_string(),
            sku: "linux".to_string(),
            unit_type: "minutes".to_string(),
            price_per_unit: 0.0,
            gross_quantity: 950.0,
            gross_amount: 0.0,
            discount_quantity: 900.0,
            discount_amount: 0.0,
            net_quantity: 50.0,
            net_amount: 0.0,
            model: None,
        }],
    };
    assert_eq!(usage.actions_gross_minutes(), 950.0);
    assert_eq!(usage.actions_billable_minutes(), 50.0);

    let mut configured = policy();
    configured.self_hosted_ready = true;
    let decision = decide_capacity(&configured, Some(usage.actions_minutes()));
    assert_eq!(decision.mode, ExecutionMode::SelfHosted);
}

#[test]
fn missing_billing_fails_closed_without_certified_capacity() {
    let configured = policy();
    let decision = decide_capacity(&configured, None);
    assert_eq!(decision.mode, ExecutionMode::Hold);
    assert!(decision.runs_on.is_empty());
}

#[test]
fn missing_billing_can_use_explicitly_certified_self_hosted_capacity() {
    let mut configured = policy();
    configured.self_hosted_ready = true;
    let decision = decide_capacity(&configured, None);
    assert_eq!(decision.mode, ExecutionMode::SelfHosted);
    assert_eq!(decision.runs_on, configured.self_hosted_runs_on);
}

#[test]
fn hard_stop_uses_reviewed_build_server_only_when_enabled() {
    let mut configured = policy();
    configured.build_server_enabled = true;
    let decision = decide_capacity(&configured, Some(1_000.0));
    assert_eq!(decision.mode, ExecutionMode::BuildServer);
    assert!(decision.runs_on.is_empty());
}

#[test]
fn organization_variable_mutation_requires_selected_repository_scope() {
    let mut configured = policy();
    configured.selected_repository_ids.clear();
    let decision = decide_capacity(&configured, Some(100.0));
    assert!(decision_variables(&configured, &decision).is_err());
}

#[test]
fn hold_mode_writes_only_nonexistent_hold_runner_label() {
    let configured = policy();
    let decision = decide_capacity(&configured, None);
    let variables = decision_variables(&configured, &decision).expect("bounded mutation");
    let runs_on = variables
        .get("CI_LINUX_RUNS_ON_JSON")
        .expect("runs-on mutation");
    assert_eq!(
        runs_on.value,
        serde_json::to_string(&vec![CI_HOLD_RUNNER_LABEL]).expect("json")
    );
    assert_eq!(runs_on.visibility, "selected");
    assert_eq!(runs_on.selected_repository_ids, vec![42]);
}

#[test]
fn policy_rejects_overlapping_or_duplicate_authority() {
    let mut configured = policy();
    configured.hosted_runs_on = vec!["gha-indie-worker".to_string()];
    assert!(configured.validate().is_err());

    let mut duplicate_repos = policy();
    duplicate_repos.selected_repository_ids = vec![42, 42];
    assert!(duplicate_repos.validate().is_err());
}
