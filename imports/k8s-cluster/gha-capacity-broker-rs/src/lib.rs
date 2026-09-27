use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

pub const DEFAULT_HOSTED_RUNS_ON: &str = "ubuntu-latest";
pub const CI_HOLD_RUNNER_LABEL: &str = "ci-capacity-hold-no-runner";

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct OrgPolicy {
    pub included_minutes: Option<f64>,
    #[serde(default = "default_warn_percent")]
    pub warn_percent: f64,
    #[serde(default = "default_self_hosted_percent")]
    pub self_hosted_percent: f64,
    #[serde(default = "default_hard_stop_percent")]
    pub hard_stop_percent: f64,
    #[serde(default)]
    pub prefer_self_hosted: bool,
    #[serde(default)]
    pub self_hosted_ready: bool,
    #[serde(default)]
    pub build_server_enabled: bool,
    #[serde(default = "default_hosted_runs_on")]
    pub hosted_runs_on: Vec<String>,
    #[serde(default)]
    pub self_hosted_runs_on: Vec<String>,
    #[serde(default)]
    pub selected_repository_ids: Vec<u64>,
}

fn default_warn_percent() -> f64 { 75.0 }
fn default_self_hosted_percent() -> f64 { 90.0 }
fn default_hard_stop_percent() -> f64 { 100.0 }
fn default_hosted_runs_on() -> Vec<String> { vec![DEFAULT_HOSTED_RUNS_ON.to_string()] }

impl OrgPolicy {
    pub fn validate(&self) -> Result<(), String> {
        if !self.warn_percent.is_finite() || !(0.0..=100.0).contains(&self.warn_percent) {
            return Err("warnPercent must be between 0 and 100".to_string());
        }
        if !self.self_hosted_percent.is_finite() || !(0.0..=100.0).contains(&self.self_hosted_percent) {
            return Err("selfHostedPercent must be between 0 and 100".to_string());
        }
        if !self.hard_stop_percent.is_finite() || self.hard_stop_percent < 100.0 {
            return Err("hardStopPercent must be at least 100".to_string());
        }
        if self.warn_percent > self.self_hosted_percent {
            return Err("warnPercent must not exceed selfHostedPercent".to_string());
        }
        if self.self_hosted_percent > self.hard_stop_percent {
            return Err("selfHostedPercent must not exceed hardStopPercent".to_string());
        }
        if self.included_minutes.is_some_and(|minutes| !minutes.is_finite() || minutes <= 0.0) {
            return Err("includedMinutes must be positive when configured".to_string());
        }
        let hosted = validate_runs_on(&self.hosted_runs_on, "hostedRunsOn")?;
        let self_hosted = validate_runs_on(&self.self_hosted_runs_on, "selfHostedRunsOn")?;
        if !hosted.is_disjoint(&self_hosted) {
            return Err("hostedRunsOn and selfHostedRunsOn must not overlap".to_string());
        }
        validate_repository_ids(&self.selected_repository_ids)?;
        Ok(())
    }
}

fn validate_runs_on(values: &[String], field: &str) -> Result<BTreeSet<String>, String> {
    if values.is_empty() { return Err(format!("{field} must contain at least one label")); }
    if values.len() > 8 { return Err(format!("{field} must contain no more than eight labels")); }
    let mut normalized = BTreeSet::new();
    for value in values {
        let trimmed = value.trim();
        if trimmed.is_empty() || trimmed.len() > 100 { return Err(format!("{field} contains an empty or oversized label")); }
        if trimmed != value { return Err(format!("{field} labels must not contain surrounding whitespace")); }
        if !trimmed.chars().all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.')) {
            return Err(format!("{field} contains an invalid label: {trimmed}"));
        }
        if !normalized.insert(trimmed.to_ascii_lowercase()) {
            return Err(format!("{field} contains a duplicate label: {trimmed}"));
        }
    }
    Ok(normalized)
}

fn validate_repository_ids(values: &[u64]) -> Result<(), String> {
    let mut seen = BTreeSet::new();
    for value in values {
        if *value == 0 { return Err("selectedRepositoryIds must contain only positive IDs".to_string()); }
        if !seen.insert(*value) { return Err(format!("selectedRepositoryIds contains duplicate repository ID {value}")); }
    }
    Ok(())
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct BillingUsageItem {
    pub product: String,
    pub sku: String,
    pub unit_type: String,
    pub price_per_unit: f64,
    pub gross_quantity: f64,
    pub gross_amount: f64,
    pub discount_quantity: f64,
    pub discount_amount: f64,
    pub net_quantity: f64,
    pub net_amount: f64,
    #[serde(default)]
    pub model: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct BillingUsageResponse { pub usage_items: Vec<BillingUsageItem> }

impl BillingUsageResponse {
    fn actions_minute_items(&self) -> impl Iterator<Item = &BillingUsageItem> {
        self.usage_items.iter().filter(|item| item.product.eq_ignore_ascii_case("Actions") && item.unit_type.eq_ignore_ascii_case("minutes"))
    }
    pub fn actions_minutes(&self) -> f64 { self.actions_gross_minutes() }
    pub fn actions_gross_minutes(&self) -> f64 {
        self.actions_minute_items().map(|item| nonnegative_finite(item.gross_quantity)).sum()
    }
    pub fn actions_billable_minutes(&self) -> f64 {
        self.actions_minute_items().map(|item| nonnegative_finite(item.net_quantity)).sum()
    }
}

fn nonnegative_finite(value: f64) -> f64 { if value.is_finite() { value.max(0.0) } else { 0.0 } }

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, ToSchema)]
#[serde(rename_all = "kebab-case")]
pub enum ExecutionMode { Hosted, SelfHosted, BuildServer, Hold }

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CapacityDecision {
    pub mode: ExecutionMode,
    pub runs_on: Vec<String>,
    pub reason: String,
    pub actions_minutes: Option<f64>,
    pub usage_percent: Option<f64>,
    pub warnings: Vec<String>,
}

pub fn decide_capacity(policy: &OrgPolicy, actions_minutes: Option<f64>) -> CapacityDecision {
    let usage_percent = match (actions_minutes, policy.included_minutes) {
        (Some(used), Some(included)) if included > 0.0 => Some((used / included) * 100.0),
        _ => None,
    };
    let mut warnings = Vec::new();
    if let Some(percent) = usage_percent {
        if percent >= policy.warn_percent { warnings.push(format!("Actions usage is at {percent:.1}% of the configured included-minute budget")); }
        if percent >= policy.self_hosted_percent { warnings.push("hosted capacity should no longer be the primary Linux lane".to_string()); }
        if percent >= policy.hard_stop_percent { warnings.push("hosted runner allocation may be blocked by budget policy".to_string()); }
    }
    if policy.prefer_self_hosted && policy.self_hosted_ready {
        return CapacityDecision { mode: ExecutionMode::SelfHosted, runs_on: policy.self_hosted_runs_on.clone(), reason: "policy prefers the validated self-hosted Linux lane".to_string(), actions_minutes, usage_percent, warnings };
    }
    match usage_percent {
        Some(percent) if percent >= policy.hard_stop_percent => {
            if policy.self_hosted_ready {
                CapacityDecision { mode: ExecutionMode::SelfHosted, runs_on: policy.self_hosted_runs_on.clone(), reason: "configured hosted-minute hard stop reached; using validated ARC capacity".to_string(), actions_minutes, usage_percent, warnings }
            } else if policy.build_server_enabled {
                CapacityDecision { mode: ExecutionMode::BuildServer, runs_on: Vec::new(), reason: "hosted-minute hard stop reached and ARC is not certified; only reviewed build-server profiles may proceed".to_string(), actions_minutes, usage_percent, warnings }
            } else {
                CapacityDecision { mode: ExecutionMode::Hold, runs_on: Vec::new(), reason: "hosted-minute hard stop reached and no certified fallback is available".to_string(), actions_minutes, usage_percent, warnings }
            }
        }
        Some(percent) if percent >= policy.self_hosted_percent && policy.self_hosted_ready => CapacityDecision { mode: ExecutionMode::SelfHosted, runs_on: policy.self_hosted_runs_on.clone(), reason: "configured self-hosted threshold reached".to_string(), actions_minutes, usage_percent, warnings },
        Some(_) => CapacityDecision { mode: ExecutionMode::Hosted, runs_on: policy.hosted_runs_on.clone(), reason: "hosted-minute usage remains below the configured routing threshold".to_string(), actions_minutes, usage_percent, warnings },
        None if policy.self_hosted_ready => CapacityDecision { mode: ExecutionMode::SelfHosted, runs_on: policy.self_hosted_runs_on.clone(), reason: "billing usage is unavailable; failing closed onto validated self-hosted capacity".to_string(), actions_minutes, usage_percent, warnings },
        None => CapacityDecision { mode: ExecutionMode::Hold, runs_on: Vec::new(), reason: "billing usage is unavailable and self-hosted readiness is not certified".to_string(), actions_minutes, usage_percent, warnings },
    }
}

#[derive(Clone, Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct VariableMutation {
    pub name: String,
    pub value: String,
    pub visibility: String,
    pub selected_repository_ids: Vec<u64>,
}

pub fn decision_variables(policy: &OrgPolicy, decision: &CapacityDecision) -> Result<BTreeMap<String, VariableMutation>, String> {
    policy.validate()?;
    if policy.selected_repository_ids.is_empty() {
        return Err("selectedRepositoryIds must be non-empty before organization variables can mutate".to_string());
    }
    let effective_runs_on = match decision.mode {
        ExecutionMode::Hosted | ExecutionMode::SelfHosted => {
            if decision.runs_on.is_empty() { return Err("an executable capacity decision must include a runner label".to_string()); }
            decision.runs_on.clone()
        }
        ExecutionMode::BuildServer | ExecutionMode::Hold => vec![CI_HOLD_RUNNER_LABEL.to_string()],
    };
    let runs_on = serde_json::to_string(&effective_runs_on).map_err(|error| format!("failed to serialize runs-on labels: {error}"))?;
    let mode = match decision.mode { ExecutionMode::Hosted => "hosted", ExecutionMode::SelfHosted => "self-hosted", ExecutionMode::BuildServer => "build-server", ExecutionMode::Hold => "hold" };
    let mut values = BTreeMap::new();
    values.insert("CI_EXECUTION_MODE".to_string(), VariableMutation { name: "CI_EXECUTION_MODE".to_string(), value: mode.to_string(), visibility: "selected".to_string(), selected_repository_ids: policy.selected_repository_ids.clone() });
    values.insert("CI_LINUX_RUNS_ON_JSON".to_string(), VariableMutation { name: "CI_LINUX_RUNS_ON_JSON".to_string(), value: runs_on, visibility: "selected".to_string(), selected_repository_ids: policy.selected_repository_ids.clone() });
    Ok(values)
}
