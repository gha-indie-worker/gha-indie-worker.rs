//! Pure, testable database authority policy for the build server.
//! No network, ORM, secret, or migration dependency belongs in this module.
//! The full server connection code in db.rs is still separately integration-tested.
/// Stable runtime database authority names shared with the hardened ORM fleet.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DatabaseCapabilityProfile {
    WebReadOnly,
    ApiReadWrite,
    WorkerReadOnly,
    WorkerReadWrite,
    Migrator,
}

impl DatabaseCapabilityProfile {
    pub(crate) fn parse(value: &str) -> Result<Self, DatabaseProfileError> {
        match value.trim() {
            "web_ro" => Ok(Self::WebReadOnly),
            "api_rw" => Ok(Self::ApiReadWrite),
            "worker_ro" => Ok(Self::WorkerReadOnly),
            "worker_rw" => Ok(Self::WorkerReadWrite),
            "migrator" => Ok(Self::Migrator),
            _ => Err(DatabaseProfileError::Unknown),
        }
    }

    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::WebReadOnly => "web_ro",
            Self::ApiReadWrite => "api_rw",
            Self::WorkerReadOnly => "worker_ro",
            Self::WorkerReadWrite => "worker_rw",
            Self::Migrator => "migrator",
        }
    }

    const fn permits_write_pool(self) -> bool {
        matches!(self, Self::ApiReadWrite | Self::WorkerReadWrite)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DatabaseProfileError {
    Unknown,
    ReadOnlyOrMigrator,
}

#[cfg(test)]
mod capability_policy_tests {
    use super::{DatabaseCapabilityProfile as Profile, DatabaseProfileError};

    const ALL: [Profile; 5] = [
        Profile::WebReadOnly,
        Profile::ApiReadWrite,
        Profile::WorkerReadOnly,
        Profile::WorkerReadWrite,
        Profile::Migrator,
    ];

    #[test]
    fn all_profile_names_round_trip_without_aliases() {
        for profile in ALL {
            assert_eq!(Profile::parse(profile.as_str()), Ok(profile));
        }
    }

    #[test]
    fn write_authority_is_exactly_api_or_worker_rw() {
        for profile in ALL {
            let expected = matches!(profile, Profile::ApiReadWrite | Profile::WorkerReadWrite);
            assert_eq!(profile.permits_write_pool(), expected, "{:?}", profile);
        }
    }

    #[test]
    fn read_only_and_migrator_never_gain_write_authority() {
        for profile in [
            Profile::WebReadOnly,
            Profile::WorkerReadOnly,
            Profile::Migrator,
        ] {
            assert!(!profile.permits_write_pool(), "{:?}", profile);
        }
    }

    #[test]
    fn unknown_or_privileged_aliases_are_rejected() {
        for value in [
            "",
            "admin",
            "superuser",
            "root",
            "read_write",
            "web_rw",
            "worker_write",
            "api_ro",
            "migrator_rw",
            "MIGRATOR",
            "Api_Rw",
            "api_rw:write",
            "api_rw\0",
            "worker_rw\nadmin",
        ] {
            assert_eq!(
                Profile::parse(value),
                Err(DatabaseProfileError::Unknown),
                "{value:?}"
            );
        }
    }

    #[test]
    fn trim_only_surrounding_whitespace() {
        assert_eq!(Profile::parse(" \tapi_rw\n"), Ok(Profile::ApiReadWrite));
        assert_eq!(Profile::parse("worker_rw\r"), Ok(Profile::WorkerReadWrite));
        assert_eq!(
            Profile::parse("worker rw"),
            Err(DatabaseProfileError::Unknown)
        );
    }

    #[test]
    fn error_kinds_are_distinct_for_callers() {
        assert_ne!(
            DatabaseProfileError::ReadOnlyOrMigrator,
            DatabaseProfileError::Unknown
        );
    }
}
