#![forbid(unsafe_code)]

//! Pure recovery-controller state machine.
//!
//! Persistence/dispatch adapters may use this model, but this module performs
//! no I/O and grants no execution authority. Every dangerous transition is
//! explicit and versioned so durable stores can apply compare-and-swap updates.

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub(crate) struct RecoveryKey {
    pub(crate) repo: String,
    pub(crate) run_id: u64,
    pub(crate) attempt: u64,
    pub(crate) head_sha: String,
}

impl RecoveryKey {
    pub(crate) fn new(
        repo: impl Into<String>,
        run_id: u64,
        attempt: u64,
        head_sha: impl Into<String>,
    ) -> Option<Self> {
        let repo = repo.into();
        let head_sha = head_sha.into();
        let Some((owner, name)) = repo.split_once('/') else {
            return None;
        };
        let valid_part = |part: &str| {
            !part.is_empty()
                && part.len() <= 100
                && part != "."
                && part != ".."
                && part
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
        };
        if !valid_part(owner)
            || !valid_part(name)
            || name.contains("..")
            || run_id == 0
            || attempt == 0
            || head_sha.len() != 40
            || !head_sha.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return None;
        }
        Some(Self {
            repo,
            run_id,
            attempt,
            head_sha,
        })
    }

    pub(crate) fn stable_id(&self) -> String {
        format!(
            "github/{}/{}/{}/{}",
            self.repo.to_ascii_lowercase(),
            self.run_id,
            self.attempt,
            self.head_sha.to_ascii_lowercase()
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RecoveryState {
    Observed,
    Verified,
    Denied,
    Claimed,
    DispatchPending,
    Dispatched,
    TerminalSucceeded,
    TerminalFailed,
}

impl RecoveryState {
    pub(crate) const fn terminal(self) -> bool {
        matches!(
            self,
            Self::Denied | Self::TerminalSucceeded | Self::TerminalFailed
        )
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct RecoveryRecord {
    pub(crate) key: RecoveryKey,
    pub(crate) state: RecoveryState,
    pub(crate) version: u64,
    pub(crate) owner: Option<String>,
    pub(crate) dispatch_id: Option<String>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum TransitionError {
    VersionMismatch,
    InvalidTransition,
    MissingOwner,
    MissingDispatchId,
    IdentityConflict,
}

impl RecoveryRecord {
    pub(crate) fn observed(key: RecoveryKey) -> Self {
        Self {
            key,
            state: RecoveryState::Observed,
            version: 0,
            owner: None,
            dispatch_id: None,
        }
    }

    pub(crate) fn apply(
        &mut self,
        expected_version: u64,
        next: RecoveryState,
        owner: Option<&str>,
        dispatch_id: Option<&str>,
    ) -> Result<(), TransitionError> {
        if self.version != expected_version {
            return Err(TransitionError::VersionMismatch);
        }
        if self.state.terminal() {
            return Err(TransitionError::InvalidTransition);
        }

        let allowed = matches!(
            (self.state, next),
            (RecoveryState::Observed, RecoveryState::Verified)
                | (RecoveryState::Observed, RecoveryState::Denied)
                | (RecoveryState::Verified, RecoveryState::Claimed)
                | (RecoveryState::Verified, RecoveryState::Denied)
                | (RecoveryState::Claimed, RecoveryState::DispatchPending)
                | (RecoveryState::Claimed, RecoveryState::Denied)
                | (RecoveryState::DispatchPending, RecoveryState::Dispatched)
                | (RecoveryState::DispatchPending, RecoveryState::Denied)
                | (RecoveryState::Dispatched, RecoveryState::TerminalSucceeded)
                | (RecoveryState::Dispatched, RecoveryState::TerminalFailed)
        );
        if !allowed {
            return Err(TransitionError::InvalidTransition);
        }

        if matches!(
            next,
            RecoveryState::Claimed
                | RecoveryState::DispatchPending
                | RecoveryState::Dispatched
                | RecoveryState::TerminalSucceeded
                | RecoveryState::TerminalFailed
        ) {
            let requested_owner = owner.or(self.owner.as_deref()).filter(|s| !s.is_empty());
            let Some(requested_owner) = requested_owner else {
                return Err(TransitionError::MissingOwner);
            };
            if let Some(existing) = self.owner.as_deref() {
                if existing != requested_owner {
                    return Err(TransitionError::IdentityConflict);
                }
            }
            self.owner = Some(requested_owner.to_string());
        }

        if matches!(
            next,
            RecoveryState::DispatchPending
                | RecoveryState::Dispatched
                | RecoveryState::TerminalSucceeded
                | RecoveryState::TerminalFailed
        ) {
            let requested_dispatch = dispatch_id
                .or(self.dispatch_id.as_deref())
                .filter(|s| !s.is_empty());
            let Some(requested_dispatch) = requested_dispatch else {
                return Err(TransitionError::MissingDispatchId);
            };
            if let Some(existing) = self.dispatch_id.as_deref() {
                if existing != requested_dispatch {
                    return Err(TransitionError::IdentityConflict);
                }
            }
            self.dispatch_id = Some(requested_dispatch.to_string());
        }

        self.state = next;
        self.version = self
            .version
            .checked_add(1)
            .ok_or(TransitionError::VersionMismatch)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> RecoveryKey {
        RecoveryKey::new(
            "gha-indie-worker/gha-indie-worker.rs",
            42,
            2,
            "a".repeat(40),
        )
        .unwrap()
    }

    #[test]
    fn identity_is_exact_and_stable() {
        let key = key();
        assert_eq!(
            key.stable_id(),
            format!(
                "github/gha-indie-worker/gha-indie-worker.rs/42/2/{}",
                "a".repeat(40)
            )
        );
        for bad in [
            RecoveryKey::new("onepart", 42, 2, "a".repeat(40)),
            RecoveryKey::new("owner/repo", 0, 2, "a".repeat(40)),
            RecoveryKey::new("owner/repo", 42, 0, "a".repeat(40)),
            RecoveryKey::new("owner/repo", 42, 2, "not-a-sha"),
            RecoveryKey::new("../owner/repo", 42, 2, "a".repeat(40)),
        ] {
            assert!(bad.is_none());
        }
    }

    #[test]
    fn no_observation_to_dispatch_shortcut_exists() {
        let mut record = RecoveryRecord::observed(key());
        for forbidden in [
            RecoveryState::Claimed,
            RecoveryState::DispatchPending,
            RecoveryState::Dispatched,
            RecoveryState::TerminalSucceeded,
            RecoveryState::TerminalFailed,
        ] {
            assert_eq!(
                record.apply(0, forbidden, Some("worker-a"), Some("dispatch-1")),
                Err(TransitionError::InvalidTransition)
            );
            assert_eq!(record.state, RecoveryState::Observed);
            assert_eq!(record.version, 0);
        }
    }

    #[test]
    fn compare_and_swap_version_prevents_duplicate_claims() {
        let mut record = RecoveryRecord::observed(key());
        record
            .apply(0, RecoveryState::Verified, None, None)
            .unwrap();
        record
            .apply(1, RecoveryState::Claimed, Some("worker-a"), None)
            .unwrap();
        assert_eq!(
            record.apply(1, RecoveryState::Claimed, Some("worker-b"), None),
            Err(TransitionError::VersionMismatch)
        );
        assert_eq!(record.owner.as_deref(), Some("worker-a"));
    }

    #[test]
    fn owner_and_dispatch_identity_cannot_change() {
        let mut record = RecoveryRecord::observed(key());
        record
            .apply(0, RecoveryState::Verified, None, None)
            .unwrap();
        record
            .apply(1, RecoveryState::Claimed, Some("worker-a"), None)
            .unwrap();
        assert_eq!(
            record.apply(
                2,
                RecoveryState::DispatchPending,
                Some("worker-b"),
                Some("dispatch-1")
            ),
            Err(TransitionError::IdentityConflict)
        );
        record
            .apply(
                2,
                RecoveryState::DispatchPending,
                Some("worker-a"),
                Some("dispatch-1"),
            )
            .unwrap();
        assert_eq!(
            record.apply(
                3,
                RecoveryState::Dispatched,
                Some("worker-a"),
                Some("dispatch-2")
            ),
            Err(TransitionError::IdentityConflict)
        );
    }

    #[test]
    fn denied_and_terminal_records_are_absorbing() {
        let mut denied = RecoveryRecord::observed(key());
        denied
            .apply(0, RecoveryState::Denied, None, None)
            .unwrap();
        assert!(denied.state.terminal());
        assert_eq!(
            denied.apply(1, RecoveryState::Verified, None, None),
            Err(TransitionError::InvalidTransition)
        );

        let mut done = RecoveryRecord::observed(key());
        done.apply(0, RecoveryState::Verified, None, None).unwrap();
        done.apply(1, RecoveryState::Claimed, Some("worker-a"), None)
            .unwrap();
        done.apply(
            2,
            RecoveryState::DispatchPending,
            Some("worker-a"),
            Some("dispatch-1"),
        )
        .unwrap();
        done.apply(
            3,
            RecoveryState::Dispatched,
            Some("worker-a"),
            Some("dispatch-1"),
        )
        .unwrap();
        done.apply(
            4,
            RecoveryState::TerminalSucceeded,
            Some("worker-a"),
            Some("dispatch-1"),
        )
        .unwrap();
        assert!(done.state.terminal());
        assert_eq!(
            done.apply(
                5,
                RecoveryState::TerminalFailed,
                Some("worker-a"),
                Some("dispatch-1")
            ),
            Err(TransitionError::InvalidTransition)
        );
    }

    #[test]
    fn dispatch_requires_both_claim_owner_and_stable_dispatch_id() {
        let mut record = RecoveryRecord::observed(key());
        record
            .apply(0, RecoveryState::Verified, None, None)
            .unwrap();
        assert_eq!(
            record.apply(1, RecoveryState::Claimed, None, None),
            Err(TransitionError::MissingOwner)
        );
        record
            .apply(1, RecoveryState::Claimed, Some("worker-a"), None)
            .unwrap();
        assert_eq!(
            record.apply(
                2,
                RecoveryState::DispatchPending,
                Some("worker-a"),
                None
            ),
            Err(TransitionError::MissingDispatchId)
        );
    }
}
