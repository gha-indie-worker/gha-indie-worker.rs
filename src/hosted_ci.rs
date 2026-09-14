//! Structural classification for GitHub-hosted CI observations.
//!
//! This module deliberately does not parse arbitrary GitHub error prose. A
//! caller may supply an admission hint only after deriving it from a stable,
//! machine-readable signal. Zero-step failures without such a hint remain
//! unknown and must never be treated as source-level test evidence or as green.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum HostedJobStatus {
    Queued,
    InProgress,
    Completed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum HostedJobConclusion {
    Success,
    Failure,
    Cancelled,
    TimedOut,
    Neutral,
    Skipped,
    ActionRequired,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RunnerAdmissionHint {
    BudgetExhausted,
    QuotaExceeded,
    NoEligibleRunner,
    RunnerProvisioningFailure,
    RunnerServiceUnavailable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct HostedJobObservation {
    pub(crate) status: HostedJobStatus,
    pub(crate) conclusion: Option<HostedJobConclusion>,
    /// `None` means GitHub did not materialize a steps collection. `Some(0)`
    /// is treated equivalently: no source step executed.
    pub(crate) step_count: Option<usize>,
    /// Set only from stable, machine-readable admission evidence. Free-form
    /// log/error text must not be converted into this hint by substring match.
    pub(crate) admission_hint: Option<RunnerAdmissionHint>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum HostedRunClassification {
    NonTerminal,
    StepfulSuccess,
    StepfulFailure,
    ZeroStepRunnerAdmissionFailure,
    ZeroStepUnknown,
    Cancelled,
}

impl HostedJobObservation {
    pub(crate) fn classify(self) -> HostedRunClassification {
        if self.status != HostedJobStatus::Completed {
            return HostedRunClassification::NonTerminal;
        }

        if self.conclusion == Some(HostedJobConclusion::Cancelled) {
            return HostedRunClassification::Cancelled;
        }

        if self.step_count.unwrap_or(0) > 0 {
            return if self.conclusion == Some(HostedJobConclusion::Success) {
                HostedRunClassification::StepfulSuccess
            } else {
                HostedRunClassification::StepfulFailure
            };
        }

        if is_failure_like(self.conclusion) && self.admission_hint.is_some() {
            HostedRunClassification::ZeroStepRunnerAdmissionFailure
        } else {
            HostedRunClassification::ZeroStepUnknown
        }
    }
}

fn is_failure_like(conclusion: Option<HostedJobConclusion>) -> bool {
    matches!(
        conclusion,
        Some(
            HostedJobConclusion::Failure
                | HostedJobConclusion::TimedOut
                | HostedJobConclusion::ActionRequired
        )
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn completed(
        conclusion: HostedJobConclusion,
        step_count: Option<usize>,
        admission_hint: Option<RunnerAdmissionHint>,
    ) -> HostedJobObservation {
        HostedJobObservation {
            status: HostedJobStatus::Completed,
            conclusion: Some(conclusion),
            step_count,
            admission_hint,
        }
    }

    #[test]
    fn stepful_success_is_real_execution_evidence() {
        assert_eq!(
            completed(HostedJobConclusion::Success, Some(4), None).classify(),
            HostedRunClassification::StepfulSuccess
        );
    }

    #[test]
    fn stepful_failure_stays_source_failure_even_with_admission_hint() {
        assert_eq!(
            completed(
                HostedJobConclusion::Failure,
                Some(2),
                Some(RunnerAdmissionHint::BudgetExhausted),
            )
            .classify(),
            HostedRunClassification::StepfulFailure
        );
    }

    #[test]
    fn null_steps_plus_machine_admission_hint_is_admission_failure() {
        assert_eq!(
            completed(
                HostedJobConclusion::Failure,
                None,
                Some(RunnerAdmissionHint::BudgetExhausted),
            )
            .classify(),
            HostedRunClassification::ZeroStepRunnerAdmissionFailure
        );
    }

    #[test]
    fn empty_steps_plus_runner_unavailable_hint_is_admission_failure() {
        assert_eq!(
            completed(
                HostedJobConclusion::Failure,
                Some(0),
                Some(RunnerAdmissionHint::RunnerServiceUnavailable),
            )
            .classify(),
            HostedRunClassification::ZeroStepRunnerAdmissionFailure
        );
    }

    #[test]
    fn zero_step_failure_without_machine_hint_fails_closed_as_unknown() {
        assert_eq!(
            completed(HostedJobConclusion::Failure, None, None).classify(),
            HostedRunClassification::ZeroStepUnknown
        );
    }

    #[test]
    fn zero_step_success_is_not_accepted_as_green() {
        assert_eq!(
            completed(HostedJobConclusion::Success, None, None).classify(),
            HostedRunClassification::ZeroStepUnknown
        );
    }

    #[test]
    fn cancelled_and_nonterminal_jobs_do_not_trigger_admission_fallback() {
        assert_eq!(
            completed(HostedJobConclusion::Cancelled, None, None).classify(),
            HostedRunClassification::Cancelled
        );

        assert_eq!(
            HostedJobObservation {
                status: HostedJobStatus::InProgress,
                conclusion: None,
                step_count: None,
                admission_hint: Some(RunnerAdmissionHint::NoEligibleRunner),
            }
            .classify(),
            HostedRunClassification::NonTerminal
        );
    }

    #[test]
    fn every_admission_hint_can_promote_a_zero_step_failure() {
        for hint in [
            RunnerAdmissionHint::BudgetExhausted,
            RunnerAdmissionHint::QuotaExceeded,
            RunnerAdmissionHint::NoEligibleRunner,
            RunnerAdmissionHint::RunnerProvisioningFailure,
            RunnerAdmissionHint::RunnerServiceUnavailable,
        ] {
            assert_eq!(
                completed(HostedJobConclusion::Failure, None, Some(hint)).classify(),
                HostedRunClassification::ZeroStepRunnerAdmissionFailure
            );
        }
    }

    #[test]
    fn neutral_and_skipped_zero_step_jobs_stay_unknown() {
        for conclusion in [HostedJobConclusion::Neutral, HostedJobConclusion::Skipped] {
            assert_eq!(
                completed(
                    conclusion,
                    None,
                    Some(RunnerAdmissionHint::RunnerProvisioningFailure),
                )
                .classify(),
                HostedRunClassification::ZeroStepUnknown
            );
        }
    }

    #[test]
    fn queued_job_is_nonterminal() {
        assert_eq!(
            HostedJobObservation {
                status: HostedJobStatus::Queued,
                conclusion: None,
                step_count: None,
                admission_hint: None,
            }
            .classify(),
            HostedRunClassification::NonTerminal
        );
    }
}
