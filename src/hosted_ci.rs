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
    fn timeout_and_action_required_are_failure_like_only_with_admission_evidence() {
        for conclusion in [
            HostedJobConclusion::TimedOut,
            HostedJobConclusion::ActionRequired,
        ] {
            assert_eq!(
                completed(
                    conclusion,
                    None,
                    Some(RunnerAdmissionHint::RunnerProvisioningFailure),
                )
                .classify(),
                HostedRunClassification::ZeroStepRunnerAdmissionFailure
            );
            assert_eq!(
                completed(conclusion, None, None).classify(),
                HostedRunClassification::ZeroStepUnknown
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

#[cfg(test)]
mod oreslang_inverted_index_contract {
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    struct Handle {
        owner: u64,
        slot: usize,
        generation: u64,
    }

    #[derive(Clone, Copy)]
    struct Slot {
        row: usize,
        generation: u64,
        live: bool,
    }

    struct Rows {
        owner: u64,
        rows: Vec<usize>,
        slots: Vec<Slot>,
        free: Vec<usize>,
        closed: bool,
    }

    impl Rows {
        fn new(owner: u64) -> Self {
            Self {
                owner,
                rows: Vec::new(),
                slots: Vec::new(),
                free: Vec::new(),
                closed: false,
            }
        }

        fn issue(&self, slot: usize) -> Handle {
            Handle {
                owner: self.owner,
                slot,
                generation: self.slots[slot].generation,
            }
        }

        fn push(&mut self) -> Result<Handle, &'static str> {
            if self.closed {
                return Err("closed");
            }
            let row = self.rows.len();
            if row >= i32::MAX as usize {
                return Err("row-count");
            }
            let slot = if let Some(slot) = self.free.pop() {
                slot
            } else {
                self.slots.push(Slot {
                    row: 0,
                    generation: 1,
                    live: false,
                });
                self.slots.len() - 1
            };
            self.slots[slot].row = row;
            self.slots[slot].live = true;
            self.rows.push(slot);
            Ok(self.issue(slot))
        }

        fn index(&self, value: i64) -> Result<Handle, &'static str> {
            if self.closed {
                return Err("closed");
            }
            if value < 0 || value > i32::MAX as i64 {
                return Err("bounds");
            }
            let row = value as usize;
            if row >= self.rows.len() {
                return Err("bounds");
            }
            let slot = self.rows[row];
            let state = self.slots.get(slot).ok_or("corrupt")?;
            if !state.live || state.row != row {
                return Err("corrupt");
            }
            Ok(self.issue(slot))
        }

        fn resolve(&self, handle: Handle) -> Result<usize, &'static str> {
            if self.closed {
                return Err("closed");
            }
            if handle.owner != self.owner {
                return Err("foreign");
            }
            let state = self.slots.get(handle.slot).ok_or("foreign")?;
            if !state.live || state.generation != handle.generation {
                return Err("stale");
            }
            Ok(state.row)
        }

        fn erase_swap(&mut self, handle: Handle) -> Result<(), &'static str> {
            let row = self.resolve(handle)?;
            let last = self.rows.len() - 1;
            if row != last {
                let moved = self.rows[last];
                self.rows[row] = moved;
                self.slots[moved].row = row;
            }
            self.rows.pop();
            let state = &mut self.slots[handle.slot];
            state.live = false;
            if state.generation != u64::MAX {
                state.generation += 1;
                self.free.push(handle.slot);
            }
            Ok(())
        }

        fn close(&mut self) {
            self.closed = true;
            for slot in &mut self.slots {
                slot.live = false;
            }
        }
    }

    #[test]
    fn dense_index_swap_delete_aba_owner_and_close_contract() {
        let mut rows = Rows::new(1);
        for bad in [i64::MIN, -1, i32::MAX as i64 + 1, i64::MAX] {
            assert_eq!(rows.index(bad), Err("bounds"));
        }
        assert_eq!(rows.index(0), Err("bounds"));

        let first = rows.push().unwrap();
        let middle = rows.push().unwrap();
        let last = rows.push().unwrap();
        let saved = rows.index(1).unwrap();
        assert_eq!(saved, middle);

        rows.erase_swap(first).unwrap();
        assert_eq!(rows.resolve(last), Ok(0));
        assert_eq!(rows.resolve(saved), Ok(1));
        assert_eq!(rows.index(0).unwrap().slot, last.slot);

        rows.erase_swap(saved).unwrap();
        assert_eq!(rows.resolve(saved), Err("stale"));
        let reused = rows.push().unwrap();
        assert_eq!(reused.slot, middle.slot);
        assert_ne!(reused.generation, middle.generation);
        assert_eq!(rows.resolve(saved), Err("stale"));

        let mut foreign = Rows::new(2);
        assert_eq!(rows.resolve(foreign.push().unwrap()), Err("foreign"));

        rows.close();
        assert_eq!(rows.index(0), Err("closed"));
    }

    #[test]
    fn read_and_write_lowering_preserve_once_only_evaluation_order() {
        let mut rows = Rows::new(30);
        rows.push().unwrap();

        let mut read_order = Vec::new();
        let mut receiver_count = 0;
        let mut index_count = 0;
        let selected = {
            receiver_count += 1;
            read_order.push("receiver");
            let receiver = &rows;
            index_count += 1;
            read_order.push("index");
            let index = 0_i64;
            receiver.index(index).unwrap()
        };
        assert_eq!(rows.resolve(selected), Ok(0));
        assert_eq!(receiver_count, 1);
        assert_eq!(index_count, 1);
        assert_eq!(read_order, vec!["receiver", "index"]);

        let mut write_order = Vec::new();
        let mut value_count = 0;
        let mut write_receiver_count = 0;
        let mut write_index_count = 0;
        let assigned = {
            value_count += 1;
            write_order.push("value");
            let value = 41_i64;
            write_receiver_count += 1;
            write_order.push("receiver");
            let receiver = &rows;
            write_index_count += 1;
            write_order.push("index");
            let index = 0_i64;
            let _target = receiver.index(index).unwrap();
            value
        };
        assert_eq!(assigned, 41);
        assert_eq!(value_count, 1);
        assert_eq!(write_receiver_count, 1);
        assert_eq!(write_index_count, 1);
        assert_eq!(write_order, vec!["value", "receiver", "index"]);
    }
}
