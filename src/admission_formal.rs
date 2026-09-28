#![forbid(unsafe_code)]

#[path = "admission.rs"]
mod admission;

use admission::{
    classify_admission_failure, AdmissionEvidenceConfidence, AdmissionFailureKind,
    PlatformDiagnostic, PlatformDiagnosticKind, PlatformDiagnosticSource,
    WorkflowAdmissionEvidence,
};

const BILLING: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::CheckRunAnnotation,
    kind: PlatformDiagnosticKind::BillingOrSpending,
    evidence_ref: "check-run:42:billing",
};
const CAPACITY: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::AuthorizedActionsApi,
    kind: PlatformDiagnosticKind::RunnerLabelOrCapacity,
    evidence_ref: "actions-job:42:capacity",
};
const DISABLED: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::AuthorizedActionsApi,
    kind: PlatformDiagnosticKind::ActionsDisabled,
    evidence_ref: "actions-policy:disabled",
};
const POLICY: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::AuthorizedActionsApi,
    kind: PlatformDiagnosticKind::PolicyOrRuleset,
    evidence_ref: "ruleset:42",
};
const CREDENTIAL: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::CheckRunAnnotation,
    kind: PlatformDiagnosticKind::CredentialOrCheckout,
    evidence_ref: "check-run:42:checkout",
};
const CANCELED: PlatformDiagnostic<'static> = PlatformDiagnostic {
    source: PlatformDiagnosticSource::AuthorizedActionsApi,
    kind: PlatformDiagnosticKind::CanceledOrSuperseded,
    evidence_ref: "actions-run:42:superseded",
};

static BILLING_ONLY: [PlatformDiagnostic<'static>; 1] = [BILLING];
static CAPACITY_ONLY: [PlatformDiagnostic<'static>; 1] = [CAPACITY];
static DISABLED_ONLY: [PlatformDiagnostic<'static>; 1] = [DISABLED];
static POLICY_ONLY: [PlatformDiagnostic<'static>; 1] = [POLICY];
static CREDENTIAL_ONLY: [PlatformDiagnostic<'static>; 1] = [CREDENTIAL];
static CANCELED_ONLY: [PlatformDiagnostic<'static>; 1] = [CANCELED];
static CONFLICTING: [PlatformDiagnostic<'static>; 2] = [BILLING, CAPACITY];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DiagnosticCase {
    None,
    Billing,
    Capacity,
    Disabled,
    Policy,
    Credential,
    Canceled,
}

impl DiagnosticCase {
    const ALL: [Self; 7] = [
        Self::None,
        Self::Billing,
        Self::Capacity,
        Self::Disabled,
        Self::Policy,
        Self::Credential,
        Self::Canceled,
    ];

    fn evidence(self) -> &'static [PlatformDiagnostic<'static>] {
        match self {
            Self::None => &[],
            Self::Billing => &BILLING_ONLY,
            Self::Capacity => &CAPACITY_ONLY,
            Self::Disabled => &DISABLED_ONLY,
            Self::Policy => &POLICY_ONLY,
            Self::Credential => &CREDENTIAL_ONLY,
            Self::Canceled => &CANCELED_ONLY,
        }
    }

    const fn explicit_nonfallback_kind(self) -> Option<AdmissionFailureKind> {
        match self {
            Self::Disabled => Some(AdmissionFailureKind::ActionsDisabled),
            Self::Policy => Some(AdmissionFailureKind::PolicyOrRuleset),
            Self::Credential => Some(AdmissionFailureKind::CredentialOrCheckout),
            Self::Canceled => Some(AdmissionFailureKind::CanceledOrSuperseded),
            Self::None | Self::Billing | Self::Capacity => None,
        }
    }
}

fn is_canceled(conclusion: Option<&str>) -> bool {
    matches!(
        conclusion,
        Some("cancelled" | "canceled" | "skipped" | "stale")
    )
}

fn is_terminal_admission_failure(status: Option<&str>, conclusion: Option<&str>) -> bool {
    status == Some("completed")
        && matches!(
            conclusion,
            Some("failure" | "action_required" | "startup_failure")
        )
}

#[test]
fn all_756_valid_evidence_states_obey_the_fallback_safety_contract() {
    let statuses = [None, Some("queued"), Some("in_progress"), Some("completed")];
    let conclusions = [
        None,
        Some("success"),
        Some("failure"),
        Some("action_required"),
        Some("startup_failure"),
        Some("cancelled"),
        Some("canceled"),
        Some("skipped"),
        Some("stale"),
    ];
    let step_counts = [None, Some(0), Some(1)];
    let mut checked = 0_u32;

    for status in statuses {
        for conclusion in conclusions {
            for steps_observed in step_counts {
                for diagnostic in DiagnosticCase::ALL {
                    let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                        status,
                        conclusion,
                        jobs_observed: Some(1),
                        steps_observed,
                        runner_name: Some("bounded-runner"),
                        runner_labels: &["self-hosted", "linux"],
                        platform_diagnostics: diagnostic.evidence(),
                    });
                    checked += 1;

                    assert!(!result.reason.is_empty());
                    if result.kind == AdmissionFailureKind::Unknown {
                        assert_eq!(result.confidence, AdmissionEvidenceConfidence::Ambiguous);
                        assert!(!result.fallback_allowed);
                    }

                    if result.fallback_allowed {
                        assert_eq!(result.confidence, AdmissionEvidenceConfidence::Explicit);
                        assert!(matches!(
                            result.kind,
                            AdmissionFailureKind::BillingOrSpending
                                | AdmissionFailureKind::RunnerLabelOrCapacity
                        ));
                        assert!(matches!(
                            diagnostic,
                            DiagnosticCase::Billing | DiagnosticCase::Capacity
                        ));
                        assert!(is_terminal_admission_failure(status, conclusion));
                        assert!(!steps_observed.is_some_and(|steps| steps > 0));
                    }

                    if is_canceled(conclusion) {
                        assert_eq!(result.kind, AdmissionFailureKind::CanceledOrSuperseded);
                        assert!(!result.fallback_allowed);
                        continue;
                    }

                    if let Some(expected_kind) = diagnostic.explicit_nonfallback_kind() {
                        assert_eq!(result.kind, expected_kind);
                        assert!(!result.fallback_allowed);
                        continue;
                    }

                    if steps_observed.is_some_and(|steps| steps > 0) {
                        assert_eq!(result.kind, AdmissionFailureKind::ExecutedCodeFailure);
                        assert!(!result.fallback_allowed);
                        continue;
                    }

                    match diagnostic {
                        DiagnosticCase::Billing => {
                            assert_eq!(result.kind, AdmissionFailureKind::BillingOrSpending);
                            assert_eq!(
                                result.fallback_allowed,
                                is_terminal_admission_failure(status, conclusion)
                            );
                        }
                        DiagnosticCase::Capacity => {
                            assert_eq!(result.kind, AdmissionFailureKind::RunnerLabelOrCapacity);
                            assert_eq!(
                                result.fallback_allowed,
                                is_terminal_admission_failure(status, conclusion)
                            );
                        }
                        DiagnosticCase::None => {
                            assert_eq!(result.kind, AdmissionFailureKind::Unknown);
                            assert!(!result.fallback_allowed);
                        }
                        DiagnosticCase::Disabled
                        | DiagnosticCase::Policy
                        | DiagnosticCase::Credential
                        | DiagnosticCase::Canceled => unreachable!("handled above"),
                    }
                }
            }
        }
    }

    assert_eq!(checked, 756);
}

#[test]
fn all_108_conflicting_diagnostic_states_fail_closed() {
    let statuses = [None, Some("queued"), Some("in_progress"), Some("completed")];
    let conclusions = [
        None,
        Some("success"),
        Some("failure"),
        Some("action_required"),
        Some("startup_failure"),
        Some("cancelled"),
        Some("canceled"),
        Some("skipped"),
        Some("stale"),
    ];
    let step_counts = [None, Some(0), Some(1)];
    let mut checked = 0_u32;

    for status in statuses {
        for conclusion in conclusions {
            for steps_observed in step_counts {
                let result = classify_admission_failure(&WorkflowAdmissionEvidence {
                    status,
                    conclusion,
                    jobs_observed: Some(1),
                    steps_observed,
                    runner_name: Some("bounded-runner"),
                    runner_labels: &["self-hosted", "linux"],
                    platform_diagnostics: &CONFLICTING,
                });
                checked += 1;
                assert_eq!(result.kind, AdmissionFailureKind::Unknown);
                assert_eq!(result.confidence, AdmissionEvidenceConfidence::Ambiguous);
                assert!(!result.fallback_allowed);
            }
        }
    }

    assert_eq!(checked, 108);
}

#[test]
fn malformed_provider_references_never_authorize_fallback() {
    for reference in ["", "contains space", "contains\nnewline"] {
        let diagnostic = PlatformDiagnostic {
            evidence_ref: reference,
            ..BILLING
        };
        let result = classify_admission_failure(&WorkflowAdmissionEvidence {
            status: Some("completed"),
            conclusion: Some("failure"),
            jobs_observed: Some(0),
            steps_observed: Some(0),
            runner_name: None,
            runner_labels: &[],
            platform_diagnostics: &[diagnostic],
        });
        assert_eq!(result.kind, AdmissionFailureKind::Unknown);
        assert_eq!(result.confidence, AdmissionEvidenceConfidence::Ambiguous);
        assert!(!result.fallback_allowed);
    }
}
