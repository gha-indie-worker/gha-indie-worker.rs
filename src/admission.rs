//! Fail-closed classification for GitHub workflow runs that never admitted user code.
//!
//! The fallback worker must not treat an empty jobs/steps payload as proof of a
//! billing failure. Only explicit platform diagnostics can make a run eligible
//! for fallback, and any observed user step suppresses billing/capacity fallback.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AdmissionFailureKind {
    BillingOrSpending,
    ActionsDisabled,
    PolicyOrRuleset,
    RunnerLabelOrCapacity,
    CredentialOrCheckout,
    CanceledOrSuperseded,
    ExecutedCodeFailure,
    Unknown,
}

impl AdmissionFailureKind {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::BillingOrSpending => "billing_or_spending",
            Self::ActionsDisabled => "actions_disabled",
            Self::PolicyOrRuleset => "policy_or_ruleset",
            Self::RunnerLabelOrCapacity => "runner_label_or_capacity",
            Self::CredentialOrCheckout => "credential_or_checkout",
            Self::CanceledOrSuperseded => "canceled_or_superseded",
            Self::ExecutedCodeFailure => "executed_code_failure",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct WorkflowAdmissionEvidence<'a> {
    pub(crate) conclusion: Option<&'a str>,
    pub(crate) status: Option<&'a str>,
    pub(crate) jobs_observed: Option<u32>,
    pub(crate) steps_observed: Option<u32>,
    pub(crate) runner_name: Option<&'a str>,
    pub(crate) runner_labels: &'a [&'a str],
    /// GitHub-generated workflow/check-suite diagnostics only. Do not pass raw
    /// user step logs here: repository output must never authorize fallback.
    pub(crate) platform_messages: &'a [&'a str],
}

impl Default for WorkflowAdmissionEvidence<'_> {
    fn default() -> Self {
        Self {
            conclusion: None,
            status: None,
            jobs_observed: None,
            steps_observed: None,
            runner_name: None,
            runner_labels: &[],
            platform_messages: &[],
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct AdmissionClassification {
    pub(crate) kind: AdmissionFailureKind,
    pub(crate) fallback_allowed: bool,
    pub(crate) reason: &'static str,
}

impl AdmissionClassification {
    const fn new(kind: AdmissionFailureKind, fallback_allowed: bool, reason: &'static str) -> Self {
        Self {
            kind,
            fallback_allowed,
            reason,
        }
    }
}

fn contains_any(haystack: &str, needles: &[&str]) -> bool {
    needles.iter().any(|needle| haystack.contains(needle))
}

fn normalized_platform_messages(evidence: &WorkflowAdmissionEvidence<'_>) -> String {
    evidence
        .platform_messages
        .iter()
        .map(|message| message.to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join("\n")
}

pub(crate) fn classify_admission_failure(
    evidence: &WorkflowAdmissionEvidence<'_>,
) -> AdmissionClassification {
    let conclusion = evidence.conclusion.unwrap_or_default().to_ascii_lowercase();
    let status = evidence.status.unwrap_or_default().to_ascii_lowercase();
    let messages = normalized_platform_messages(evidence);
    let steps_started = evidence.steps_observed.is_some_and(|steps| steps > 0);

    if matches!(
        conclusion.as_str(),
        "cancelled" | "canceled" | "skipped" | "stale"
    ) || contains_any(
        &messages,
        &[
            "superseded",
            "cancelled because a higher priority",
            "canceled because a higher priority",
            "cancelled due to concurrency",
            "canceled due to concurrency",
        ],
    ) {
        return AdmissionClassification::new(
            AdmissionFailureKind::CanceledOrSuperseded,
            false,
            "the workflow was canceled, skipped, stale, or superseded",
        );
    }

    if contains_any(
        &messages,
        &[
            "authentication failed",
            "bad credentials",
            "permission denied",
            "repository not found",
            "could not read username",
            "resource not accessible by integration",
            "checkout failed",
            "failed to checkout",
            "submodule checkout",
        ],
    ) {
        return AdmissionClassification::new(
            AdmissionFailureKind::CredentialOrCheckout,
            false,
            "an explicit credential or checkout diagnostic was observed",
        );
    }

    if contains_any(
        &messages,
        &[
            "github actions is disabled",
            "github actions are disabled",
            "actions are disabled",
            "workflow is disabled",
        ],
    ) {
        return AdmissionClassification::new(
            AdmissionFailureKind::ActionsDisabled,
            false,
            "GitHub explicitly reported that Actions or the workflow is disabled",
        );
    }

    if contains_any(
        &messages,
        &[
            "blocked by a ruleset",
            "required workflow",
            "action is not allowed",
            "actions are not allowed",
            "not allowed to use",
            "organization policy",
            "enterprise policy",
            "repository policy",
        ],
    ) {
        return AdmissionClassification::new(
            AdmissionFailureKind::PolicyOrRuleset,
            false,
            "an explicit policy or ruleset denial was observed",
        );
    }

    // Billing/capacity fallback is allowed only before any user step starts.
    // A repository-controlled log line cannot turn an executed failure into a
    // platform admission failure because only platform_messages are inspected.
    if !steps_started
        && contains_any(
            &messages,
            &[
                "spending limit",
                "billing and plans",
                "payment failed",
                "payment method",
                "account suspended due to billing",
                "exceeded the included minutes",
                "actions minutes quota",
            ],
        )
    {
        return AdmissionClassification::new(
            AdmissionFailureKind::BillingOrSpending,
            true,
            "GitHub explicitly reported a billing, spending, or Actions-minutes admission failure",
        );
    }

    if !steps_started
        && contains_any(
            &messages,
            &[
                "no runner matching the specified labels",
                "no online runner",
                "no online and idle runners",
                "could not find a runner",
                "runner group has no",
                "runner capacity",
            ],
        )
    {
        return AdmissionClassification::new(
            AdmissionFailureKind::RunnerLabelOrCapacity,
            true,
            "GitHub explicitly reported runner label or capacity exhaustion before execution",
        );
    }

    if steps_started {
        return AdmissionClassification::new(
            AdmissionFailureKind::ExecutedCodeFailure,
            false,
            "one or more workflow steps started, so fallback would duplicate executed work",
        );
    }

    // Read these fields intentionally: their absence or zero values are useful
    // audit evidence but never sufficient to authorize fallback.
    let _ambiguous_zero_step_shape = (
        evidence.jobs_observed,
        evidence.runner_name,
        evidence.runner_labels,
        status,
    );

    AdmissionClassification::new(
        AdmissionFailureKind::Unknown,
        false,
        "zero-step or missing-runner evidence alone is ambiguous; fail closed",
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn evidence<'a>(messages: &'a [&'a str]) -> WorkflowAdmissionEvidence<'a> {
        WorkflowAdmissionEvidence {
            platform_messages: messages,
            ..WorkflowAdmissionEvidence::default()
        }
    }

    #[test]
    fn explicit_billing_failure_is_fallback_eligible() {
        let result = classify_admission_failure(&evidence(&[
            "The job was not started because recent account payments have failed or your spending limit needs to be increased.",
        ]));
        assert_eq!(result.kind, AdmissionFailureKind::BillingOrSpending);
        assert!(result.fallback_allowed);
        assert_eq!(result.kind.as_str(), "billing_or_spending");
    }

    #[test]
    fn zero_jobs_and_zero_steps_alone_remain_unknown() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("failure"),
            status: Some("completed"),
            jobs_observed: Some(0),
            steps_observed: Some(0),
            runner_name: None,
            runner_labels: &[],
            platform_messages: &[],
        });
        assert_eq!(result.kind, AdmissionFailureKind::Unknown);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn observed_steps_suppress_a_billing_fallback() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            steps_observed: Some(1),
            platform_messages: &["The account exceeded the included minutes"],
            ..WorkflowAdmissionEvidence::default()
        });
        assert_eq!(result.kind, AdmissionFailureKind::ExecutedCodeFailure);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn explicit_runner_capacity_failure_is_fallback_eligible() {
        let result = classify_admission_failure(&evidence(&[
            "No runner matching the specified labels was found: self-hosted, linux",
        ]));
        assert_eq!(result.kind, AdmissionFailureKind::RunnerLabelOrCapacity);
        assert!(result.fallback_allowed);
    }

    #[test]
    fn disabled_policy_credentials_and_cancellation_fail_closed() {
        let cases = [
            (
                "GitHub Actions is disabled for this repository.",
                AdmissionFailureKind::ActionsDisabled,
            ),
            (
                "The action is not allowed by organization policy.",
                AdmissionFailureKind::PolicyOrRuleset,
            ),
            (
                "Resource not accessible by integration during checkout.",
                AdmissionFailureKind::CredentialOrCheckout,
            ),
            (
                "The run was superseded by a newer concurrency-group run.",
                AdmissionFailureKind::CanceledOrSuperseded,
            ),
        ];
        for (message, expected) in cases {
            let result = classify_admission_failure(&evidence(&[message]));
            assert_eq!(result.kind, expected, "{message}");
            assert!(!result.fallback_allowed, "{message}");
        }
    }

    #[test]
    fn canceled_conclusion_does_not_need_message_text() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("cancelled"),
            ..WorkflowAdmissionEvidence::default()
        });
        assert_eq!(result.kind, AdmissionFailureKind::CanceledOrSuperseded);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn generic_failure_after_execution_is_not_replayed() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("failure"),
            jobs_observed: Some(1),
            steps_observed: Some(4),
            runner_name: Some("self-hosted-01"),
            runner_labels: &["self-hosted", "linux"],
            platform_messages: &["Process completed with exit code 1"],
            ..WorkflowAdmissionEvidence::default()
        });
        assert_eq!(result.kind, AdmissionFailureKind::ExecutedCodeFailure);
        assert!(!result.fallback_allowed);
    }
}
