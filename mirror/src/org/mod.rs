use crate::error::RuntimeError;

mod actions;
mod create;
mod inspect;
mod list;
mod names;
mod visibility;
mod workspace;
#[cfg(test)]
mod prefix_reconciliation_tests;

pub use create::{
    RepositoryCreationEvent, RepositoryCreationPhase, create_missing_repositories,
    create_missing_repositories_with_progress,
};
pub use inspect::inspect_github_org;
pub use list::list_missing_repositories;
pub use names::normalize_repository_arguments;
pub use visibility::{SetRepositoryVisibilityOptions, set_repository_visibility};
pub use workspace::{
    WorkspaceAction, WorkspaceOptions, default_workspace_root, manage_workspace,
    workspace_directory,
};
pub(crate) use names::{expected_repository_names, validate_org_login};

/// Shared repository expectations for organization inspection and mutation commands.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrgRepositoryScope {
    /// GitHub organization login.
    pub owner: String,
    /// Maximum repositories requested from `gh repo list`.
    pub repo_limit: usize,
    /// Exact repository names expected in addition to a standard family.
    pub expected_repositories: Vec<String>,
    /// Optional prefix for standard repository-family members.
    pub family_prefix: Option<String>,
    /// Standard repository-family suffixes.
    pub family_members: Vec<String>,
}

/// Options for `org list-missing-repos`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListMissingRepositoriesOptions {
    /// Organization and canonical repository scope.
    pub scope: OrgRepositoryScope,
    /// Optional canonical repository names used to filter the result.
    pub selected_repositories: Vec<String>,
    /// Emit a normal command report instead of shell-composable repository names.
    pub report: bool,
}

/// Visibility assigned to newly created repositories.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RepositoryVisibility {
    /// Only organization members with access can see the repository.
    Private,
    /// Enterprise members can see the repository.
    Internal,
    /// Anyone can see the repository.
    Public,
}

impl RepositoryVisibility {
    /// GitHub API value for this visibility.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Private => "private",
            Self::Internal => "internal",
            Self::Public => "public",
        }
    }
}

/// Options for `org create-missing-repos`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreateMissingRepositoriesOptions {
    /// Organization and canonical repository scope.
    pub scope: OrgRepositoryScope,
    /// Explicit canonical repositories to create.
    pub selected_repositories: Vec<String>,
    /// Create every currently missing canonical repository.
    pub all: bool,
    /// Visibility assigned to each created repository.
    pub visibility: RepositoryVisibility,
}

pub(super) fn validate_repository_scope(scope: &OrgRepositoryScope) -> Result<(), RuntimeError> {
    names::validate_repository_scope(scope)
}
