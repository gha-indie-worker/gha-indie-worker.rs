//! Public NATS wire constants consumed by this worker.
//!
//! These values are a deliberately small compatibility surface copied from the
//! shared generated contract so the public worker crate does not require a
//! private monorepo checkout merely to compile. The canonical fleet authority
//! remains the shared NATS subject definitions; keep the tests below strict so
//! drift is obvious during review.

pub const BUILD_SERVER_EVENTS_SUBJECT: &str = "dd.remote.build_server.events";
pub const BUILD_SERVER_IMAGES_SUBJECT: &str = "dd.remote.build_server.images";
pub const BUILD_SERVER_REQUESTS_SUBJECT: &str = "dd.remote.build_server.requests";
pub const BUILD_SERVER_REQUESTS_QUEUE_GROUP: &str = "dd-build-server";
pub const BUILD_SERVER_RESULTS_SUBJECT: &str = "dd.remote.build_server.results";
pub const RUNTIME_CRITICAL_EVENTS_SUBJECT: &str = "dd.remote.events.critical";
pub const DD_REMOTE_BUILD_JOBS_STREAM_NAME: &str = "DD_REMOTE_BUILD_JOBS";

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    use super::*;

    #[test]
    fn build_subjects_are_namespaced_and_distinct() {
        let subjects = [
            BUILD_SERVER_EVENTS_SUBJECT,
            BUILD_SERVER_IMAGES_SUBJECT,
            BUILD_SERVER_REQUESTS_SUBJECT,
            BUILD_SERVER_RESULTS_SUBJECT,
        ];
        assert!(subjects
            .iter()
            .all(|subject| subject.starts_with("dd.remote.build_server.")));
        let unique: BTreeSet<_> = subjects.into_iter().collect();
        assert_eq!(unique.len(), 4);
    }

    #[test]
    fn durable_request_contract_matches_the_request_subject() {
        assert_eq!(
            BUILD_SERVER_REQUESTS_SUBJECT,
            "dd.remote.build_server.requests"
        );
        assert_eq!(BUILD_SERVER_REQUESTS_QUEUE_GROUP, "dd-build-server");
        assert_eq!(DD_REMOTE_BUILD_JOBS_STREAM_NAME, "DD_REMOTE_BUILD_JOBS");
    }

    #[test]
    fn critical_events_stay_on_the_shared_runtime_bus() {
        assert_eq!(RUNTIME_CRITICAL_EVENTS_SUBJECT, "dd.remote.events.critical");
    }
}
