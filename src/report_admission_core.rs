//! Dependency-free security predicates shared by the durable journal and
//! GitHub Check Run recovery. This file is independently rustc --test-able
//! even when the upstream monorepo-only path dependencies are unavailable.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct AttemptIdentity<'a> {
    pub owner: &'a str,
    pub repo: &'a str,
    pub head_sha: &'a str,
    pub attempt_id: &'a str,
    pub installation_id: Option<u64>,
}

/// A different repo, commit, attempt, or installation cannot donate its local
/// success to the check run recorded in the durable journal.
pub(crate) fn same_attempt(expected: AttemptIdentity<'_>, actual: AttemptIdentity<'_>) -> bool {
    expected.owner.eq_ignore_ascii_case(actual.owner)
        && expected.repo.eq_ignore_ascii_case(actual.repo)
        && expected.head_sha == actual.head_sha
        && expected.attempt_id == actual.attempt_id
        && expected.installation_id == actual.installation_id
}

/// A failed/ambiguous GitHub create is NOT evidence no Check Run was created.
/// Reserve exactly one attempt on disk before POST; never re-POST on a retry
/// without an operator-reviewed recovery protocol.
pub(crate) fn may_reserve_create(
    check_run_id: Option<u64>,
    create_attempted: bool,
    pending: bool,
    execution_active: bool,
) -> bool {
    check_run_id.is_none() && !create_attempted && pending && execution_active
}

/// Never mark local success as App-delivered unless a specific already-bound
/// Check Run accepted a terminal verdict after execution was durably recorded.
pub(crate) fn may_mark_delivered(
    recorded_check_id: Option<u64>,
    returned_check_id: Option<u64>,
    execution_terminal: bool,
    has_conclusion: bool,
) -> bool {
    matches!((recorded_check_id, returned_check_id), (Some(a), Some(b)) if a > 0 && a == b)
        && execution_terminal
        && has_conclusion
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity<'a>(sha: &'a str) -> AttemptIdentity<'a> {
        AttemptIdentity {
            owner: "acme",
            repo: "ci",
            head_sha: sha,
            attempt_id: "delivery-001",
            installation_id: Some(42),
        }
    }

    #[test]
    fn exact_revision_and_attempt_match_case_insensitive_repo_spelling() {
        let head = "a".repeat(40);
        let expected = identity(&head);
        let actual = AttemptIdentity { owner: "ACME", repo: "CI", ..expected };
        assert!(same_attempt(expected, actual));
    }

    #[test]
    fn branch_movement_cannot_transfer_a_prior_verdict() {
        let a = "a".repeat(40);
        let b = "b".repeat(40);
        assert!(!same_attempt(identity(&a), identity(&b)));
    }

    #[test]
    fn wrong_app_installation_cannot_reuse_check_attempt() {
        let a = "a".repeat(40);
        let expected = identity(&a);
        assert!(!same_attempt(expected, AttemptIdentity {
            installation_id: Some(99), ..expected
        }));
        assert!(!same_attempt(expected, AttemptIdentity {
            attempt_id: "delivery-002", ..expected
        }));
        assert!(!same_attempt(expected, AttemptIdentity {
            repo: "other", ..expected
        }));
        assert!(!same_attempt(expected, AttemptIdentity {
            owner: "other", ..expected
        }));
    }

    #[test]
    fn only_one_unresolved_create_can_be_reserved() {
        assert!(may_reserve_create(None, false, true, true));
        assert!(!may_reserve_create(None, true, true, true));
        assert!(!may_reserve_create(Some(5), false, true, true));
        assert!(!may_reserve_create(None, false, false, true));
        assert!(!may_reserve_create(None, false, true, false));
    }

    #[test]
    fn ambiguous_create_response_never_permits_blind_recreation() {
        // After a crash, the journal still has an attempted marker but no ID.
        assert!(!may_reserve_create(None, true, true, true));
    }

    #[test]
    fn only_matching_nonzero_app_check_is_delivered() {
        assert!(may_mark_delivered(Some(7), Some(7), true, true));
        assert!(!may_mark_delivered(None, None, true, true));
        assert!(!may_mark_delivered(Some(7), None, true, true));
        assert!(!may_mark_delivered(None, Some(7), true, true));
        assert!(!may_mark_delivered(Some(7), Some(8), true, true));
        assert!(!may_mark_delivered(Some(0), Some(0), true, true));
    }

    #[test]
    fn active_execution_or_absent_conclusion_is_never_trusted_success() {
        assert!(!may_mark_delivered(Some(7), Some(7), false, true));
        assert!(!may_mark_delivered(Some(7), Some(7), true, false));
    }
}
