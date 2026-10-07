use std::io;
use std::path::PathBuf;
use std::time::Duration;

pub mod error {
    use std::io;
    #[derive(Debug, thiserror::Error)]
    pub enum RuntimeError {
        #[error("usage: {0}")]
        Usage(String),
        #[error(transparent)]
        Io(#[from] io::Error),
    }
}

pub mod github {
    use crate::error::RuntimeError;

    #[derive(Debug, Clone)]
    pub struct GitHubRepository {
        pub name: String,
        pub name_with_owner: String,
        pub is_archived: bool,
        pub is_fork: bool,
        pub is_private: bool,
        pub url: String,
    }

    pub struct GhCli;

    impl GhCli {
        pub async fn require_authentication(&self) -> Result<(), RuntimeError> {
            Ok(())
        }

        pub async fn list_repositories(
            &self,
            _owner: &str,
            _limit: usize,
        ) -> Result<Vec<GitHubRepository>, RuntimeError> {
            Ok(Vec::new())
        }
    }
}

pub mod model {
    use serde_json::Value;
    use std::collections::BTreeMap;

    #[derive(Debug)]
    pub struct Finding;

    impl Finding {
        pub fn info(_code: impl Into<String>, _message: impl Into<String>) -> Self { Self }
        pub fn error(_code: impl Into<String>, _message: impl Into<String>) -> Self { Self }
        pub fn with_target(self, _target: impl Into<String>) -> Self { self }
    }

    #[derive(Debug)]
    pub struct CommandReport {
        pub metadata: BTreeMap<String, Value>,
    }

    impl CommandReport {
        pub fn new(_command: impl Into<String>) -> Self {
            Self { metadata: BTreeMap::new() }
        }
        pub fn with_metadata(mut self, key: impl Into<String>, value: Value) -> Self {
            self.metadata.insert(key.into(), value);
            self
        }
        pub fn insert_metadata(&mut self, key: impl Into<String>, value: Value) {
            self.metadata.insert(key.into(), value);
        }
        pub fn push(&mut self, _finding: Finding) {}
        pub fn finalize(self) -> Self { self }
    }
}

pub mod process {
    use std::process::ExitStatus;
    use std::time::Duration;
    use tokio::process::Command;
    use crate::error::RuntimeError;

    #[derive(Debug, Clone, Copy)]
    pub struct CaptureLimits {
        pub deadline: Duration,
        pub stdout_bytes: usize,
        pub stderr_bytes: usize,
    }

    impl CaptureLimits {
        pub const fn new(deadline: Duration, stdout_bytes: usize, stderr_bytes: usize) -> Self {
            Self { deadline, stdout_bytes, stderr_bytes }
        }
    }

    pub struct CapturedOutput {
        pub status: ExitStatus,
        pub stdout: String,
        pub stderr: String,
    }

    pub async fn run_bounded(
        _command: Command,
        _label: impl Into<String>,
        _limits: CaptureLimits,
    ) -> Result<CapturedOutput, RuntimeError> {
        unimplemented!("compile-only harness")
    }
}

pub mod org {
    use std::path::PathBuf;
    use crate::error::RuntimeError;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct OrgRepositoryScope {
        pub owner: String,
        pub repo_limit: usize,
        pub expected_repositories: Vec<String>,
        pub family_prefix: Option<String>,
        pub family_members: Vec<String>,
    }

    pub fn validate_repository_scope(_scope: &OrgRepositoryScope) -> Result<(), RuntimeError> {
        Ok(())
    }

    #[path = "../../../mirror/src/org/workspace.rs"]
    pub mod workspace;
}

#[test]
fn exact_workspace_module_is_linked() {
    let options = org::workspace::WorkspaceOptions {
        scope: org::OrgRepositoryScope {
            owner: "example-org".to_owned(),
            repo_limit: 1000,
            expected_repositories: vec![],
            family_prefix: None,
            family_members: vec![],
        },
        action: org::workspace::WorkspaceAction::Sync,
        workspace_root: PathBuf::from("/tmp/codes"),
        all: true,
        non_interactive: true,
    };
    assert_eq!(
        org::workspace::workspace_directory(&options).unwrap(),
        PathBuf::from("/tmp/codes/example-org")
    );
}
