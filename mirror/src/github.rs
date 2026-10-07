use std::collections::BTreeSet;
use std::time::Duration;

use ores_middleware::{InMemoryTokenBucket, RateLimiter};
use serde::Deserialize;
use tokio::process::Command;
use tokio::time::sleep;

use crate::error::RuntimeError;
use crate::process::{CaptureLimits, run_bounded};

mod input;
mod memberships;

pub use memberships::OrganizationMembership;

/// Repository metadata returned by `gh repo list`.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GitHubRepository {
    /// Short repository name.
    pub name: String,
    /// Owner-qualified repository name.
    pub name_with_owner: String,
    /// Whether GitHub marks the repository archived.
    pub is_archived: bool,
    /// Whether the repository is a fork.
    pub is_fork: bool,
    /// Whether the repository is private.
    pub is_private: bool,
    /// Browser URL.
    pub url: String,
}

/// Basic organization metadata returned by `gh api orgs/{owner}`.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct GitHubOrganization {
    /// Canonical organization login.
    pub login: String,
    /// Human-readable organization name.
    #[serde(default)]
    pub name: Option<String>,
    /// Organization description.
    #[serde(default)]
    pub description: Option<String>,
    /// Browser URL.
    pub html_url: String,
    /// Free-form organization location.
    #[serde(default)]
    pub location: Option<String>,
    /// Total private repositories when the authenticated account may view it.
    #[serde(default)]
    pub total_private_repos: Option<usize>,
}

/// Repository metadata returned after successful organization repository creation.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct CreatedGitHubRepository {
    /// Short repository name.
    pub name: String,
    /// Owner-qualified repository name.
    pub full_name: String,
    /// Browser URL.
    pub html_url: String,
    /// Whether GitHub created the repository as private.
    pub private: bool,
}

/// Repository visibility metadata returned by `gh repo list`.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GitHubRepositoryVisibility {
    /// Short repository name.
    pub name: String,
    /// Owner-qualified repository name.
    pub name_with_owner: String,
    /// GitHub visibility value: public, private, or internal.
    pub visibility: String,
    /// Whether the authenticated viewer has repository-admin permission.
    pub viewer_can_administer: bool,
    /// Whether GitHub marks the repository archived.
    pub is_archived: bool,
    /// Whether the repository is a fork.
    pub is_fork: bool,
    /// Number of forks in this repository's network reported by GitHub.
    #[serde(default)]
    pub fork_count: usize,
}

/// Repository metadata returned after a successful visibility update.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct UpdatedGitHubRepository {
    /// Short repository name.
    pub name: String,
    /// Owner-qualified repository name.
    pub full_name: String,
    /// Browser URL.
    pub html_url: String,
    /// Whether GitHub reports the repository as private.
    pub private: bool,
    /// GitHub visibility value.
    pub visibility: String,
}

/// Captured unsuccessful `gh` result that can be represented as an audit finding.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GhCommandFailure {
    /// Child exit code.
    pub exit_code: Option<i32>,
    /// Bounded diagnostic text.
    pub diagnostic: String,
}

#[derive(Debug)]
struct ProcessResult {
    success: bool,
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
}

/// Authenticated GitHub CLI gateway.
///
/// Credentials remain in the `gh` credential store; no token is accepted by this
/// API or placed on argv.
pub struct GhCli {
    limiter: InMemoryTokenBucket,
}

impl Default for GhCli {
    fn default() -> Self {
        Self::new()
    }
}

impl GhCli {
    /// Create a gateway with a bounded local request limiter supplied by
    /// `ores-middleware`.
    #[must_use]
    pub fn new() -> Self {
        Self {
            limiter: InMemoryTokenBucket::default(),
        }
    }

    /// Require an active GitHub.com login.
    pub async fn require_authentication(&self) -> Result<(), RuntimeError> {
        let result = self
            .execute(vec![
                "auth".to_owned(),
                "status".to_owned(),
                "--active".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
            ])
            .await?;
        if result.success {
            Ok(())
        } else {
            Err(RuntimeError::Dependency {
                command: "gh auth status --active --hostname github.com".to_owned(),
                exit_code: result.exit_code,
                message: bounded_diagnostic(&result),
            })
        }
    }

    /// Fetch basic metadata for one GitHub organization.
    pub async fn organization(&self, owner: &str) -> Result<GitHubOrganization, RuntimeError> {
        let owner = input::owner(owner)?;
        let result = self
            .execute(vec![
                "api".to_owned(),
                format!("orgs/{owner}"),
                "--method".to_owned(),
                "GET".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
            ])
            .await?;
        if !result.success {
            return Err(RuntimeError::Dependency {
                command: format!("gh api orgs/{owner}"),
                exit_code: result.exit_code,
                message: bounded_diagnostic(&result),
            });
        }
        serde_json::from_str(&result.stdout).map_err(RuntimeError::from)
    }

    /// List repositories visible to the authenticated account.
    pub async fn list_repositories(
        &self,
        owner: &str,
        limit: usize,
    ) -> Result<Vec<GitHubRepository>, RuntimeError> {
        let owner = input::owner(owner)?;
        if limit == 0 {
            return Err(RuntimeError::Usage(
                "repository limit must be positive".to_owned(),
            ));
        }
        let result = self
            .execute(vec![
                "repo".to_owned(),
                "list".to_owned(),
                owner.clone(),
                "--limit".to_owned(),
                limit.to_string(),
                "--json".to_owned(),
                "name,nameWithOwner,isArchived,isFork,isPrivate,url".to_owned(),
            ])
            .await?;
        if !result.success {
            return Err(RuntimeError::Dependency {
                command: format!("gh repo list {owner}"),
                exit_code: result.exit_code,
                message: bounded_diagnostic(&result),
            });
        }
        serde_json::from_str(&result.stdout).map_err(RuntimeError::from)
    }

    /// List repository names with exact GitHub visibility values.
    pub async fn list_repository_visibilities(
        &self,
        owner: &str,
        limit: usize,
    ) -> Result<Vec<GitHubRepositoryVisibility>, RuntimeError> {
        let owner = input::owner(owner)?;
        if limit == 0 {
            return Err(RuntimeError::Usage(
                "repository limit must be positive".to_owned(),
            ));
        }
        let result = self
            .execute(vec![
                "repo".to_owned(),
                "list".to_owned(),
                owner.clone(),
                "--limit".to_owned(),
                limit.to_string(),
                "--json".to_owned(),
                "name,nameWithOwner,visibility,viewerCanAdminister,isArchived,isFork,forkCount".to_owned(),
            ])
            .await?;
        if !result.success {
            return Err(RuntimeError::Dependency {
                command: format!("gh repo list {owner}"),
                exit_code: result.exit_code,
                message: bounded_diagnostic(&result),
            });
        }
        serde_json::from_str(&result.stdout).map_err(RuntimeError::from)
    }

    /// Create and initialize one repository in an organization.
    ///
    /// GitHub API rejections are returned as data so callers can continue a
    /// multi-repository operation and emit one finding per failed target.
    /// Mutations are never automatically retried after a timeout: the server
    /// may have committed the operation even when the response was lost.
    pub async fn create_repository(
        &self,
        owner: &str,
        name: &str,
        visibility: &str,
    ) -> Result<Result<CreatedGitHubRepository, GhCommandFailure>, RuntimeError> {
        let owner = input::owner(owner)?;
        input::repository_name(name)?;
        input::visibility(visibility)?;
        let result = self
            .execute(vec![
                "api".to_owned(),
                format!("orgs/{owner}/repos"),
                "--method".to_owned(),
                "POST".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
                "-f".to_owned(),
                format!("name={name}"),
                "-f".to_owned(),
                format!("visibility={visibility}"),
                "-F".to_owned(),
                "auto_init=true".to_owned(),
            ])
            .await?;

        if !result.success {
            return Ok(Err(GhCommandFailure {
                exit_code: result.exit_code,
                diagnostic: bounded_diagnostic(&result),
            }));
        }

        serde_json::from_str(&result.stdout)
            .map(Ok)
            .map_err(RuntimeError::from)
    }

    /// Change visibility on one existing repository.
    ///
    /// Mutation failures are returned as data so the caller can retain a
    /// sanitized partial-operation receipt and stop without retrying.
    pub async fn update_repository_visibility(
        &self,
        owner: &str,
        name: &str,
        visibility: &str,
    ) -> Result<Result<UpdatedGitHubRepository, GhCommandFailure>, RuntimeError> {
        let owner = input::owner(owner)?;
        input::repository_name(name)?;
        input::visibility(visibility)?;
        let result = self
            .execute(vec![
                "api".to_owned(),
                format!("repos/{owner}/{name}"),
                "--method".to_owned(),
                "PATCH".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
                "-f".to_owned(),
                format!("visibility={visibility}"),
            ])
            .await?;

        if !result.success {
            return Ok(Err(GhCommandFailure {
                exit_code: result.exit_code,
                diagnostic: bounded_diagnostic(&result),
            }));
        }

        serde_json::from_str(&result.stdout)
            .map(Ok)
            .map_err(RuntimeError::from)
    }

    /// Fetch top-level entries for one repository.
    ///
    /// A non-zero API response is returned as data so the organization auditor
    /// can emit a stdout finding instead of treating a target-repository problem
    /// as an `ores-cli` runtime failure.
    pub async fn root_entries(
        &self,
        repository: &str,
    ) -> Result<Result<BTreeSet<String>, GhCommandFailure>, RuntimeError> {
        let repository = input::repository(repository)?;
        let result = self
            .execute(vec![
                "api".to_owned(),
                format!("repos/{repository}/contents"),
                "--method".to_owned(),
                "GET".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
            ])
            .await?;

        if !result.success {
            return Ok(Err(GhCommandFailure {
                exit_code: result.exit_code,
                diagnostic: bounded_diagnostic(&result),
            }));
        }

        #[derive(Deserialize)]
        struct RootEntry {
            name: String,
        }

        let entries = serde_json::from_str::<Vec<RootEntry>>(&result.stdout)?
            .into_iter()
            .map(|entry| entry.name)
            .collect();
        Ok(Ok(entries))
    }

    /// Read one UTF-8 repository file through the authenticated GitHub API.
    ///
    /// Missing or inaccessible target files are returned as data so callers can
    /// emit target findings without treating them as `ores-cli` runtime errors.
    /// The path is encoded as literal path segments, not API query parameters.
    pub async fn repository_file(
        &self,
        repository: &str,
        path: &str,
    ) -> Result<Result<String, GhCommandFailure>, RuntimeError> {
        let repository = input::repository(repository)?;
        let path = input::contents_path(path)?;
        let result = self
            .execute(vec![
                "api".to_owned(),
                format!("repos/{repository}/contents/{path}"),
                "--method".to_owned(),
                "GET".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github.raw+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
            ])
            .await?;

        if !result.success {
            return Ok(Err(GhCommandFailure {
                exit_code: result.exit_code,
                diagnostic: bounded_diagnostic(&result),
            }));
        }

        Ok(Ok(result.stdout))
    }

    async fn execute(&self, args: Vec<String>) -> Result<ProcessResult, RuntimeError> {
        self.acquire_permit().await?;
        let mut command = Command::new("gh");
        command
            .args(&args)
            .env("GH_HOST", "github.com")
            .env("GH_PROMPT_DISABLED", "1")
            .env("GH_PAGER", "cat")
            .env("PAGER", "cat");

        let output = run_bounded(
            command,
            "gh",
            CaptureLimits::new(Duration::from_secs(120), 32 * 1024 * 1024, 64 * 1024),
        )
        .await?;

        Ok(ProcessResult {
            success: output.status.success(),
            exit_code: output.status.code(),
            stdout: output.stdout,
            stderr: output.stderr,
        })
    }

    async fn acquire_permit(&self) -> Result<(), RuntimeError> {
        for _ in 0..20 {
            if self.limiter.allow("ores-cli:gh:github.com", 32, 20.0).await {
                return Ok(());
            }
            sleep(Duration::from_millis(50)).await;
        }

        Err(RuntimeError::Dependency {
            command: "ores-middleware local GitHub rate limiter".to_owned(),
            exit_code: None,
            message: "could not acquire a bounded local gh request permit".to_owned(),
        })
    }
}

fn bounded_diagnostic(result: &ProcessResult) -> String {
    let value = if result.stderr.trim().is_empty() {
        result.stdout.trim()
    } else {
        result.stderr.trim()
    };
    const LIMIT: usize = 4_096;
    if value.len() <= LIMIT {
        return value.to_owned();
    }
    let mut boundary = LIMIT;
    while !value.is_char_boundary(boundary) {
        boundary -= 1;
    }
    format!("{}…", &value[..boundary])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn invalid_sdk_inputs_fail_before_executing_gh() {
        let gh = GhCli::new();
        assert!(matches!(
            gh.organization("../users").await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.list_repositories("--help", 10).await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.list_repositories("org", 0).await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.root_entries("org/repo?ref=other").await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.repository_file("org/repo", "../README.md").await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.create_repository("org", "a/b", "private").await,
            Err(RuntimeError::Usage(_))
        ));
        assert!(matches!(
            gh.create_repository("org", "repo", "invalid").await,
            Err(RuntimeError::Usage(_))
        ));
    }
}
