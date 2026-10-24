//! Fail-closed classification for GitHub workflow runs that never admitted user code.
//!
//! Empty jobs/steps payloads are ambiguous. Fallback eligibility requires a
//! typed diagnostic sourced from an authorized GitHub API surface plus a
//! completed, failed run. Raw repository output and free-form substring
//! matching are deliberately outside this boundary.

// The production adapter lives inside the sealed admission module so only
// authenticated provider parsing may ever issue trusted evidence. Regular
// rustc --test stays dependency-free; the separate API harness enables it.
#[cfg(any(not(test), feature = "admission-api-harness"))]
#[allow(dead_code)]
#[path = "admission/github_api.rs"]
mod github_api;

#[allow(dead_code)]
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RunIdentity {
    pub(crate) repo: String,
    pub(crate) run_id: u64,
    pub(crate) attempt: u64,
    pub(crate) head_sha: String,
}

#[allow(dead_code)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ObserveError {
    InvalidIdentity,
    Transport,
    MalformedResponse,
    MismatchedIdentity,
    IncompletePagination,
    ChangedDuringObservation,
}

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AdmissionEvidenceConfidence {
    Explicit,
    Ambiguous,
}

/// Only these provider-owned sources may emit an admission diagnostic.
/// Repository logs, workflow titles, and user-authored annotations are not
/// represented, so they cannot authorize fallback through this API.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PlatformDiagnosticSource {
    CheckRunAnnotation,
    AuthorizedActionsApi,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PlatformDiagnosticKind {
    BillingOrSpending,
    ActionsDisabled,
    PolicyOrRuleset,
    RunnerLabelOrCapacity,
    CredentialOrCheckout,
    CanceledOrSuperseded,
}

impl PlatformDiagnosticKind {
    const COUNT: usize = 6;

    const fn index(self) -> usize {
        match self {
            Self::BillingOrSpending => 0,
            Self::ActionsDisabled => 1,
            Self::PolicyOrRuleset => 2,
            Self::RunnerLabelOrCapacity => 3,
            Self::CredentialOrCheckout => 4,
            Self::CanceledOrSuperseded => 5,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PlatformDiagnostic<'a> {
    // These fields must NOT be crate-visible: otherwise any caller can claim
    // AuthorizedActionsApi provenance and authorize an unsafe fallback.
    // A production issuer must only be added here alongside a verified,
    // identity-bound GitHub API response adapter, never from caller text.
    source: PlatformDiagnosticSource,
    kind: PlatformDiagnosticKind,
    /// Stable provider evidence reference, never a log body or secret.
    evidence_ref: &'a str,
}

// Test-only issuer preserves exhaustive model fixtures without providing a
// production capability for manufacturing trusted platform diagnostics.
#[cfg(test)]
impl<'a> PlatformDiagnostic<'a> {
    pub(crate) const fn synthetic_for_test(
        source: PlatformDiagnosticSource,
        kind: PlatformDiagnosticKind,
        evidence_ref: &'a str,
    ) -> Self {
        Self {
            source,
            kind,
            evidence_ref,
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
    pub(crate) platform_diagnostics: &'a [PlatformDiagnostic<'a>],
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
            platform_diagnostics: &[],
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct AdmissionClassification {
    pub(crate) kind: AdmissionFailureKind,
    pub(crate) confidence: AdmissionEvidenceConfidence,
    pub(crate) fallback_allowed: bool,
    pub(crate) reason: &'static str,
}

impl AdmissionClassification {
    const fn explicit(
        kind: AdmissionFailureKind,
        fallback_allowed: bool,
        reason: &'static str,
    ) -> Self {
        Self {
            kind,
            confidence: AdmissionEvidenceConfidence::Explicit,
            fallback_allowed,
            reason,
        }
    }

    const fn ambiguous(reason: &'static str) -> Self {
        Self {
            kind: AdmissionFailureKind::Unknown,
            confidence: AdmissionEvidenceConfidence::Ambiguous,
            fallback_allowed: false,
            reason,
        }
    }
}

#[allow(dead_code)]
#[derive(Debug)]
pub(crate) struct RunReceipt {
    pub(crate) identity: RunIdentity,
    pub(crate) jobs_observed: u32,
    pub(crate) steps_observed: u32,
    pub(crate) runner_assigned: bool,
    pub(crate) classification: AdmissionClassification,
}

/// Production entry point for the authenticated, read-only GitHub observer.
/// This does not create trusted platform diagnostics and cannot dispatch work.
#[cfg(any(not(test), feature = "admission-api-harness"))]
pub(crate) async fn inspect_authenticated(
    token: &str,
    id: &RunIdentity,
) -> Result<RunReceipt, ObserveError> {
    github_api::inspect_authenticated(token, id).await
}

/// Normal unit tests deliberately avoid network access. The API adapter has a
/// separate hermetic harness under the admission-api-harness feature.
#[cfg(all(test, not(feature = "admission-api-harness")))]
#[allow(dead_code)]
pub(crate) async fn inspect_authenticated(
    _token: &str,
    _id: &RunIdentity,
) -> Result<RunReceipt, ObserveError> {
    Err(ObserveError::Transport)
}

fn valid_evidence_ref(reference: &str) -> bool {
    !reference.is_empty()
        && reference.len() <= 512
        && reference
            .bytes()
            .all(|byte| byte.is_ascii_graphic() && !byte.is_ascii_control())
}

fn unique_diagnostic_kind(
    diagnostics: &[PlatformDiagnostic<'_>],
) -> Result<Option<PlatformDiagnosticKind>, ()> {
    let mut observed = [false; PlatformDiagnosticKind::COUNT];
    for diagnostic in diagnostics {
        // Reading the typed source here is intentional: adding any new source
        // requires a review of this admission boundary.
        match diagnostic.source {
            PlatformDiagnosticSource::CheckRunAnnotation
            | PlatformDiagnosticSource::AuthorizedActionsApi => {}
        }
        if !valid_evidence_ref(diagnostic.evidence_ref) {
            return Err(());
        }
        observed[diagnostic.kind.index()] = true;
    }

    let mut unique = None;
    for diagnostic in diagnostics {
        if observed[diagnostic.kind.index()] {
            if unique.is_some_and(|kind| kind != diagnostic.kind) {
                return Err(());
            }
            unique = Some(diagnostic.kind);
            observed[diagnostic.kind.index()] = false;
        }
    }
    Ok(unique)
}

fn terminal_admission_failure(status: &str, conclusion: &str) -> bool {
    status == "completed"
        && matches!(
            conclusion,
            "failure" | "action_required" | "startup_failure"
        )
}

pub(crate) fn classify_admission_failure(
    evidence: &WorkflowAdmissionEvidence<'_>,
) -> AdmissionClassification {
    let conclusion = evidence
        .conclusion
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    let status = evidence
        .status
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    let steps_started = evidence.steps_observed.is_some_and(|steps| steps > 0);
    // A zero-job observation cannot simultaneously identify a job's runner.
    // Treat this contradictory provider shape as incomplete evidence rather
    // than assuming that no user code was ever admitted.
    let runner_observation_consistent = evidence.jobs_observed != Some(0)
        || (evidence.runner_name.is_none() && evidence.runner_labels.is_empty());
    let observation_complete = evidence.jobs_observed.is_some()
        && evidence.steps_observed == Some(0)
        && runner_observation_consistent;
    // Application-authored check annotations are not independent platform
    // admission evidence, even if they describe billing or runner capacity.
    let trusted_fallback_source = evidence
        .platform_diagnostics
        .iter()
        .all(|item| item.source == PlatformDiagnosticSource::AuthorizedActionsApi);

    let diagnostic = match unique_diagnostic_kind(evidence.platform_diagnostics) {
        Ok(diagnostic) => diagnostic,
        Err(()) => {
            return AdmissionClassification::ambiguous(
                "provider diagnostics were conflicting or lacked a bounded evidence reference",
            );
        }
    };

    if matches!(
        conclusion.as_str(),
        "cancelled" | "canceled" | "skipped" | "stale"
    ) || diagnostic == Some(PlatformDiagnosticKind::CanceledOrSuperseded)
    {
        return AdmissionClassification::explicit(
            AdmissionFailureKind::CanceledOrSuperseded,
            false,
            "the workflow was canceled, skipped, stale, or superseded",
        );
    }

    match diagnostic {
        Some(PlatformDiagnosticKind::CredentialOrCheckout) => {
            return AdmissionClassification::explicit(
                AdmissionFailureKind::CredentialOrCheckout,
                false,
                "an authorized credential or checkout diagnostic was observed",
            );
        }
        Some(PlatformDiagnosticKind::ActionsDisabled) => {
            return AdmissionClassification::explicit(
                AdmissionFailureKind::ActionsDisabled,
                false,
                "GitHub explicitly reported that Actions or the workflow is disabled",
            );
        }
        Some(PlatformDiagnosticKind::PolicyOrRuleset) => {
            return AdmissionClassification::explicit(
                AdmissionFailureKind::PolicyOrRuleset,
                false,
                "an authorized policy or ruleset denial was observed",
            );
        }
        _ => {}
    }

    if steps_started {
        return AdmissionClassification::explicit(
            AdmissionFailureKind::ExecutedCodeFailure,
            false,
            "one or more workflow steps started, so fallback would duplicate executed work",
        );
    }

    let terminal = terminal_admission_failure(&status, &conclusion);
    match diagnostic {
        Some(PlatformDiagnosticKind::BillingOrSpending) => {
            return AdmissionClassification::explicit(
                AdmissionFailureKind::BillingOrSpending,
                terminal && observation_complete && trusted_fallback_source,
                if terminal && observation_complete && trusted_fallback_source {
                    "an authorized billing diagnostic accompanies a completed admission failure with complete zero-step evidence"
                } else {
                    "billing evidence or complete zero-step observations are insufficient for fallback"
                },
            );
        }
        Some(PlatformDiagnosticKind::RunnerLabelOrCapacity) => {
            return AdmissionClassification::explicit(
                AdmissionFailureKind::RunnerLabelOrCapacity,
                terminal && observation_complete && trusted_fallback_source,
                if terminal && observation_complete && trusted_fallback_source {
                    "an authorized runner diagnostic accompanies a completed admission failure with complete zero-step evidence"
                } else {
                    "runner evidence or complete zero-step observations are insufficient for fallback"
                },
            );
        }
        Some(PlatformDiagnosticKind::CanceledOrSuperseded) => unreachable!("handled above"),
        Some(
            PlatformDiagnosticKind::ActionsDisabled
            | PlatformDiagnosticKind::PolicyOrRuleset
            | PlatformDiagnosticKind::CredentialOrCheckout,
        ) => unreachable!("handled above"),
        None => {}
    }

    // These fields remain part of the normalized evidence receipt, but absence
    // or zero values never authorize fallback.
    let _ambiguous_zero_step_shape = (
        evidence.jobs_observed,
        evidence.runner_name,
        evidence.runner_labels,
    );

    AdmissionClassification::ambiguous(
        "zero-step or missing-runner evidence alone is ambiguous; fail closed",
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const BILLING: PlatformDiagnostic<'static> = PlatformDiagnostic::synthetic_for_test(
        PlatformDiagnosticSource::AuthorizedActionsApi,
        PlatformDiagnosticKind::BillingOrSpending,
        "https://api.github.test/check-runs/42/annotations/1",
    );
    const CAPACITY: PlatformDiagnostic<'static> = PlatformDiagnostic::synthetic_for_test(
        PlatformDiagnosticSource::AuthorizedActionsApi,
        PlatformDiagnosticKind::RunnerLabelOrCapacity,
        "actions-job:42:runner-capacity",
    );

    fn failed<'a>(diagnostics: &'a [PlatformDiagnostic<'a>]) -> WorkflowAdmissionEvidence<'a> {
        WorkflowAdmissionEvidence {
            conclusion: Some("failure"),
            status: Some("completed"),
            jobs_observed: Some(0),
            steps_observed: Some(0),
            platform_diagnostics: diagnostics,
            ..WorkflowAdmissionEvidence::default()
        }
    }

    #[test]
    fn explicit_terminal_billing_failure_is_fallback_eligible() {
        let result = classify_admission_failure(&failed(&[BILLING]));
        assert_eq!(result.kind, AdmissionFailureKind::BillingOrSpending);
        assert_eq!(result.confidence, AdmissionEvidenceConfidence::Explicit);
        assert!(result.fallback_allowed);
        assert_eq!(result.kind.as_str(), "billing_or_spending");
        assert!(!result.reason.is_empty());
    }

    #[test]
    fn application_check_annotation_cannot_authorize_fallback() {
        for kind in [
            PlatformDiagnosticKind::BillingOrSpending,
            PlatformDiagnosticKind::RunnerLabelOrCapacity,
        ] {
            let annotation = PlatformDiagnostic::synthetic_for_test(
                PlatformDiagnosticSource::CheckRunAnnotation,
                kind,
                "check-run:42:app-authored",
            );
            let result = classify_admission_failure(&failed(&[annotation]));
            assert!(!result.fallback_allowed, "{kind:?}");
        }
    }

    #[test]
    fn billing_evidence_on_success_or_incomplete_run_fails_closed() {
        for (status, conclusion) in [("completed", "success"), ("queued", "failure")] {
            let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                status: Some(status),
                conclusion: Some(conclusion),
                platform_diagnostics: &[BILLING],
                ..WorkflowAdmissionEvidence::default()
            });
            assert_eq!(result.kind, AdmissionFailureKind::BillingOrSpending);
            assert!(!result.fallback_allowed, "{status}/{conclusion}");
        }
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
            platform_diagnostics: &[],
        });
        assert_eq!(result.kind, AdmissionFailureKind::Unknown);
        assert_eq!(result.confidence, AdmissionEvidenceConfidence::Ambiguous);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn missing_observations_never_authorize_fallback() {
        for diagnostic in [BILLING, CAPACITY] {
            for (jobs, steps) in [
                (None, None),
                (None, Some(0)),
                (Some(0), None),
                (Some(1), None),
                (Some(2), Some(1)),
            ] {
                let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                    conclusion: Some("failure"),
                    status: Some("completed"),
                    jobs_observed: jobs,
                    steps_observed: steps,
                    platform_diagnostics: &[diagnostic],
                    ..WorkflowAdmissionEvidence::default()
                });
                assert!(!result.fallback_allowed, "{jobs:?}/{steps:?}");
            }
            for jobs in [0, 1, 5] {
                let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                    conclusion: Some("failure"),
                    status: Some("completed"),
                    jobs_observed: Some(jobs),
                    steps_observed: Some(0),
                    platform_diagnostics: &[diagnostic],
                    ..WorkflowAdmissionEvidence::default()
                });
                assert!(result.fallback_allowed, "confirmed zero steps, jobs={jobs}");
            }
        }
    }

    #[test]
    fn zero_jobs_with_runner_identity_is_contradictory_and_fails_closed() {
        for diagnostic in [BILLING, CAPACITY] {
            for (runner_name, runner_labels) in [
                (Some("runner-42"), &[][..]),
                (None, &["self-hosted"][..]),
                (Some("runner-42"), &["linux"][..]),
                (Some(""), &[][..]),
            ] {
                let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                    conclusion: Some("failure"),
                    status: Some("completed"),
                    jobs_observed: Some(0),
                    steps_observed: Some(0),
                    runner_name,
                    runner_labels,
                    platform_diagnostics: &[diagnostic],
                });
                assert!(
                    !result.fallback_allowed,
                    "contradictory zero jobs with runner={runner_name:?}, labels={runner_labels:?}"
                );
            }
            assert!(
                classify_admission_failure(&WorkflowAdmissionEvidence {
                    conclusion: Some("failure"),
                    status: Some("completed"),
                    jobs_observed: Some(0),
                    steps_observed: Some(0),
                    platform_diagnostics: &[diagnostic],
                    ..WorkflowAdmissionEvidence::default()
                })
                .fallback_allowed
            );
        }
    }

    #[test]
    fn observed_steps_suppress_billing_and_capacity_fallback() {
        for diagnostic in [BILLING, CAPACITY] {
            let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                conclusion: Some("failure"),
                status: Some("completed"),
                steps_observed: Some(1),
                platform_diagnostics: &[diagnostic],
                ..WorkflowAdmissionEvidence::default()
            });
            assert_eq!(result.kind, AdmissionFailureKind::ExecutedCodeFailure);
            assert!(!result.fallback_allowed);
        }
    }

    #[test]
    fn explicit_terminal_runner_capacity_failure_is_fallback_eligible() {
        let result = classify_admission_failure(&failed(&[CAPACITY]));
        assert_eq!(result.kind, AdmissionFailureKind::RunnerLabelOrCapacity);
        assert!(result.fallback_allowed);
    }

    #[test]
    fn disabled_policy_credentials_and_cancellation_fail_closed() {
        let cases = [
            (
                PlatformDiagnosticKind::ActionsDisabled,
                AdmissionFailureKind::ActionsDisabled,
            ),
            (
                PlatformDiagnosticKind::PolicyOrRuleset,
                AdmissionFailureKind::PolicyOrRuleset,
            ),
            (
                PlatformDiagnosticKind::CredentialOrCheckout,
                AdmissionFailureKind::CredentialOrCheckout,
            ),
            (
                PlatformDiagnosticKind::CanceledOrSuperseded,
                AdmissionFailureKind::CanceledOrSuperseded,
            ),
        ];
        for (kind, expected) in cases {
            let diagnostic = PlatformDiagnostic::synthetic_for_test(
                PlatformDiagnosticSource::AuthorizedActionsApi,
                kind,
                "actions-api:run:42",
            );
            let result = classify_admission_failure(&failed(&[diagnostic]));
            assert_eq!(result.kind, expected);
            assert!(!result.fallback_allowed);
        }
    }

    #[test]
    fn checkout_after_runner_execution_remains_a_checkout_failure() {
        let diagnostic = PlatformDiagnostic::synthetic_for_test(
            PlatformDiagnosticSource::CheckRunAnnotation,
            PlatformDiagnosticKind::CredentialOrCheckout,
            "check-run:42:checkout-404",
        );
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("failure"),
            status: Some("completed"),
            steps_observed: Some(1),
            runner_name: Some("self-hosted-01"),
            platform_diagnostics: &[diagnostic],
            ..WorkflowAdmissionEvidence::default()
        });
        assert_eq!(result.kind, AdmissionFailureKind::CredentialOrCheckout);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn canceled_conclusion_does_not_need_a_diagnostic() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("cancelled"),
            status: Some("completed"),
            ..WorkflowAdmissionEvidence::default()
        });
        assert_eq!(result.kind, AdmissionFailureKind::CanceledOrSuperseded);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn generic_failure_after_execution_is_not_replayed() {
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            conclusion: Some("failure"),
            status: Some("completed"),
            jobs_observed: Some(1),
            steps_observed: Some(4),
            runner_name: Some("self-hosted-01"),
            runner_labels: &["self-hosted", "linux"],
            platform_diagnostics: &[],
        });
        assert_eq!(result.kind, AdmissionFailureKind::ExecutedCodeFailure);
        assert!(!result.fallback_allowed);
    }

    #[test]
    fn conflicting_or_unreferenced_provider_diagnostics_fail_closed() {
        let conflict = classify_admission_failure(&failed(&[BILLING, CAPACITY]));
        assert_eq!(conflict.kind, AdmissionFailureKind::Unknown);
        assert_eq!(conflict.confidence, AdmissionEvidenceConfidence::Ambiguous);
        assert!(!conflict.fallback_allowed);

        let missing_reference = PlatformDiagnostic::synthetic_for_test(
            PlatformDiagnosticSource::AuthorizedActionsApi,
            PlatformDiagnosticKind::BillingOrSpending,
            "",
        );
        let invalid = classify_admission_failure(&failed(&[missing_reference]));
        assert_eq!(invalid.kind, AdmissionFailureKind::Unknown);
        assert!(!invalid.fallback_allowed);
    }

    #[test]
    fn every_output_kind_has_a_stable_machine_label() {
        let labels = [
            AdmissionFailureKind::BillingOrSpending,
            AdmissionFailureKind::ActionsDisabled,
            AdmissionFailureKind::PolicyOrRuleset,
            AdmissionFailureKind::RunnerLabelOrCapacity,
            AdmissionFailureKind::CredentialOrCheckout,
            AdmissionFailureKind::CanceledOrSuperseded,
            AdmissionFailureKind::ExecutedCodeFailure,
            AdmissionFailureKind::Unknown,
        ]
        .map(AdmissionFailureKind::as_str);
        assert_eq!(
            labels
                .iter()
                .copied()
                .collect::<std::collections::HashSet<_>>()
                .len(),
            8
        );
    }
}
