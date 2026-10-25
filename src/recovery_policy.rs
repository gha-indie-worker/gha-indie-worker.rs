//! Fail-closed identity and dispatch policy for the recovery observation seam.
//! Compiles with rustc alone. No allow decision exists here.

// Canonical identity helper retained for the deny-only model and future verified adapters.
#[allow(dead_code)]
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AttemptKey {
    pub(crate) repo: String,
    pub(crate) run_id: u64,
    pub(crate) attempt: u64,
    pub(crate) head_sha: String,
}

#[allow(dead_code)]
impl AttemptKey {
    pub(crate) fn new(repo: &str, run_id: u64, attempt: u64, head_sha: &str) -> Option<Self> {
        let mut parts = repo.split('/');
        let owner = parts.next()?;
        let name = parts.next()?;
        if parts.next().is_some()
            || !valid_part(owner)
            || !valid_part(name)
            || name.contains("..")
            || run_id == 0
            || attempt == 0
            || run_id > i64::MAX as u64
            || attempt > i64::MAX as u64
            || head_sha.len() != 40
            || !head_sha.bytes().all(|c| c.is_ascii_hexdigit())
        {
            return None;
        }
        Some(Self {
            repo: repo.to_ascii_lowercase(),
            run_id,
            attempt,
            head_sha: head_sha.to_ascii_lowercase(),
        })
    }

    /// Domain-separated and unambiguous input to the durable dedupe digest.
    pub(crate) fn canonical_identity(&self) -> String {
        format!(
            "github-recovery-observation-v1\0{}\0{}\0{}\0{}",
            self.repo, self.run_id, self.attempt, self.head_sha
        )
    }
}

#[allow(dead_code)]
fn valid_part(part: &str) -> bool {
    !part.is_empty()
        && part.len() <= 100
        && part != "."
        && part != ".."
        && part
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'-'))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Denial {
    ExecutedSteps,
    RunnerAssigned,
    UnverifiedDiagnostic,
    DispatchDisabled,
}

impl Denial {
    #[allow(dead_code)]
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::ExecutedSteps => "executed_steps",
            Self::RunnerAssigned => "runner_assigned",
            Self::UnverifiedDiagnostic => "unverified_diagnostic",
            Self::DispatchDisabled => "dispatch_disabled",
        }
    }
}

/// Deny-only, even if a future classifier gains explicit provider evidence.
pub(crate) const fn decide(
    observed_steps: u32,
    runner_assigned: bool,
    classifier_fallback_allowed: bool,
) -> Denial {
    if observed_steps > 0 {
        Denial::ExecutedSteps
    } else if runner_assigned {
        Denial::RunnerAssigned
    } else if !classifier_fallback_allowed {
        Denial::UnverifiedDiagnostic
    } else {
        Denial::DispatchDisabled
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_identity_is_case_insensitive_but_attempt_and_sha_bound() {
        let a = AttemptKey::new("OWNER/Repo", 42, 1, &"A".repeat(40)).unwrap();
        let b = AttemptKey::new("owner/repo", 42, 1, &"a".repeat(40)).unwrap();
        assert_eq!(a.canonical_identity(), b.canonical_identity());
        assert_ne!(
            a.canonical_identity(),
            AttemptKey::new("owner/repo", 42, 2, &"a".repeat(40))
                .unwrap()
                .canonical_identity()
        );
        assert_ne!(
            a.canonical_identity(),
            AttemptKey::new("owner/repo", 42, 1, &"b".repeat(40))
                .unwrap()
                .canonical_identity()
        );
    }

    #[test]
    fn invalid_or_unbounded_identifiers_are_rejected() {
        let sha = "a".repeat(40);
        for repo in [
            "",
            "owner",
            "owner/repo/other",
            "../repo",
            "owner/../repo",
            "owner/a..b",
            "owner/repo?x=1",
            "owner/repo\nother",
        ] {
            assert!(AttemptKey::new(repo, 1, 1, &sha).is_none(), "{repo:?}");
        }
        assert!(AttemptKey::new("owner/repo", 0, 1, &sha).is_none());
        assert!(AttemptKey::new("owner/repo", 1, 0, &sha).is_none());
        assert!(AttemptKey::new("owner/repo", u64::MAX, 1, &sha).is_none());
        assert!(AttemptKey::new("owner/repo", 1, u64::MAX, &sha).is_none());
        assert!(AttemptKey::new("owner/repo", 1, 1, "bad").is_none());
    }

    #[test]
    fn exhaustive_bounded_matrix_never_authorizes_dispatch() {
        for steps in 0..=4 {
            for assigned in [false, true] {
                for classifier_allowed in [false, true] {
                    let denial = decide(steps, assigned, classifier_allowed);
                    let expected = if steps > 0 {
                        Denial::ExecutedSteps
                    } else if assigned {
                        Denial::RunnerAssigned
                    } else if classifier_allowed {
                        Denial::DispatchDisabled
                    } else {
                        Denial::UnverifiedDiagnostic
                    };
                    assert_eq!(denial, expected);
                    assert!(!denial.as_str().is_empty());
                }
            }
        }
    }
}
