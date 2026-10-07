use std::io;

use thiserror::Error;

use crate::model::{CommandReport, Finding};

/// Failures in the CLI or a required runtime dependency.
#[derive(Debug, Error)]
pub enum RuntimeError {
    /// The package-owned flags-2-env contract could not be located or opened.
    #[error("CLI flag contract unavailable: {0}")]
    FlagContract(String),
    /// The CLI contract or invocation could not be parsed.
    #[error("invalid invocation: {0}")]
    Usage(String),
    /// A required subprocess could not be started.
    #[error("could not start required command `{command}`: {source}")]
    Spawn {
        /// Program name.
        command: String,
        /// Underlying process error.
        #[source]
        source: io::Error,
    },
    /// A required subprocess completed unsuccessfully.
    #[error("required command `{command}` failed with exit code {exit_code:?}: {message}")]
    Dependency {
        /// Human-readable command label.
        command: String,
        /// Child exit code when supplied by the platform.
        exit_code: Option<i32>,
        /// Bounded diagnostic text.
        message: String,
    },
    /// An internal report or renderer invariant was violated.
    #[error("internal invariant failed: {0}")]
    Invariant(String),
    /// Filesystem or output IO failed.
    #[error(transparent)]
    Io(#[from] io::Error),
    /// JSON serialization or decoding failed.
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    /// Structured logging through ores-otel failed.
    #[error("ores-otel logging failed: {0}")]
    Telemetry(String),
    /// An operation stopped after assembling a plan or receiving write receipts.
    /// SDK callers can inspect the retained report without triggering output.
    #[error("{source}; partial operation report retained")]
    PartialOperation {
        /// Original runtime failure; never downgraded to a successful report.
        #[source]
        source: Box<RuntimeError>,
        /// Acknowledged outcomes and any explicitly unconfirmed target.
        report: Box<CommandReport>,
        /// Original process exit code, retained even when the report has findings.
        exit_code: u8,
    },
}

impl RuntimeError {
    /// Conventional process exit code for this failure class.
    #[must_use]
    pub const fn exit_code(&self) -> u8 {
        match self {
            Self::FlagContract(_) => 1,
            Self::Usage(_) => 64,
            Self::PartialOperation { exit_code, .. } => *exit_code,
            Self::Spawn { .. }
            | Self::Dependency { .. }
            | Self::Invariant(_)
            | Self::Io(_)
            | Self::Json(_)
            | Self::Telemetry(_) => 70,
        }
    }

    /// Evidence retained when an operation fails after earlier work succeeded.
    #[must_use]
    pub fn partial_report(&self) -> Option<&CommandReport> {
        match self {
            Self::PartialOperation { report, .. } => Some(report),
            _ => None,
        }
    }

    pub(crate) fn with_partial_report(self, mut report: CommandReport) -> Self {
        let exit_code = self.exit_code();
        report.insert_metadata("partial", serde_json::json!(true));
        report.insert_metadata("runtimeExitCode", serde_json::json!(exit_code));
        report.push(Finding::error(
            "operation-interrupted",
            "operation stopped; inspect acknowledged and unconfirmed targets before rerunning",
        ));
        Self::PartialOperation {
            source: Box::new(self),
            report: Box::new(report.finalize()),
            exit_code,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn missing_flag_contract_is_exit_one() {
        let error = RuntimeError::FlagContract("missing .cli-flags.toml".to_owned());
        assert_eq!(error.exit_code(), 1);
    }

    #[test]
    fn partial_evidence_does_not_downgrade_a_runtime_failure() {
        let mut report = CommandReport::new("org create-missing-repos");
        report.insert_metadata("createdRepositories", json!(["alpha-clients"]));
        let error = RuntimeError::Io(io::ErrorKind::BrokenPipe.into()).with_partial_report(report);
        assert_eq!(error.exit_code(), 70);
        let partial = error.partial_report().unwrap();
        assert_eq!(partial.metadata["partial"], true);
        assert_eq!(partial.metadata["runtimeExitCode"], 70);
        assert_eq!(
            partial.metadata["createdRepositories"],
            json!(["alpha-clients"])
        );
    }

    #[test]
    fn partial_evidence_preserves_usage_exit_codes_and_is_not_printed_in_errors() {
        let mut report = CommandReport::new("orgs create-missing-repos");
        report.insert_metadata("privateEvidence", json!("do-not-put-on-stderr"));
        let error = RuntimeError::Usage("account changed".to_owned()).with_partial_report(report);
        assert_eq!(error.exit_code(), 64);
        assert!(!error.to_string().contains("do-not-put-on-stderr"));
        assert!(
            RuntimeError::Usage("invalid".to_owned())
                .partial_report()
                .is_none()
        );
    }
}
