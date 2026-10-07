use std::env;
use std::fs;
use std::io::{self, BufRead, IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::json;
use tokio::process::Command;

use crate::error::RuntimeError;
use crate::github::{GhCli, GitHubRepository};
use crate::model::{CommandReport, Finding};
use crate::process::{CaptureLimits, run_bounded};

use super::{OrgRepositoryScope, validate_repository_scope};

const DEFAULT_WORKSPACE_ROOT: &str = "~/codes";
const PROCESS_LIMITS: CaptureLimits =
    CaptureLimits::new(Duration::from_secs(15 * 60), 256 * 1024, 256 * 1024);

/// Local organization-workspace operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkspaceAction {
    /// Clone repositories that do not yet have a local checkout.
    Clone,
    /// Pull existing local checkouts without cloning missing repositories.
    Pull,
    /// Clone missing repositories and pull existing local checkouts.
    Sync,
}

impl WorkspaceAction {
    /// Canonical CLI command component.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Clone => "clone",
            Self::Pull => "pull",
            Self::Sync => "sync",
        }
    }
}

/// Options for local organization workspace commands.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceOptions {
    /// Organization and repository inventory scope.
    pub scope: OrgRepositoryScope,
    /// Operation applied to the organization workspace.
    pub action: WorkspaceAction,
    /// Root directory below which the organization directory is created.
    pub workspace_root: PathBuf,
    /// Explicit acknowledgement that every visible repository is selected.
    pub all: bool,
    /// Skip the terminal path-confirmation prompt.
    pub non_interactive: bool,
}

/// Default workspace root used when --dir is omitted.
#[must_use]
pub fn default_workspace_root() -> PathBuf {
    PathBuf::from(DEFAULT_WORKSPACE_ROOT)
}

/// Resolve the final organization workspace path without creating it.
pub fn workspace_directory(options: &WorkspaceOptions) -> Result<PathBuf, RuntimeError> {
    let root = expand_workspace_root(&options.workspace_root)?;
    Ok(root.join(&options.scope.owner))
}

/// Clone and/or pull every repository selected by the workspace command.
pub async fn manage_workspace(
    gh: &GhCli,
    options: &WorkspaceOptions,
) -> Result<CommandReport, RuntimeError> {
    validate_repository_scope(&options.scope)?;
    if !options.all {
        return Err(RuntimeError::Usage(format!(
            "org {} requires explicit --all",
            options.action.as_str()
        )));
    }

    let workspace = workspace_directory(options)?;
    let mut report = CommandReport::new(format!("org {}", options.action.as_str()))
        .with_metadata("owner", json!(&options.scope.owner))
        .with_metadata("workspace", json!(workspace.display().to_string()))
        .with_metadata("action", json!(options.action.as_str()))
        .with_metadata("nonInteractive", json!(options.non_interactive));

    if !options.non_interactive && !confirm_workspace_path(&workspace, options)? {
        report.insert_metadata("cancelled", json!(true));
        report.push(Finding::info(
            "workspace-cancelled",
            "workspace operation cancelled before authentication, inventory, or filesystem mutation",
        ));
        return Ok(report.finalize());
    }

    gh.require_authentication().await?;
    let mut repositories = gh
        .list_repositories(&options.scope.owner, options.scope.repo_limit)
        .await?;
    if repositories.len() >= options.scope.repo_limit {
        return Err(RuntimeError::Usage(format!(
            "repository inventory reached --repo-limit={}; raise the limit before using org {} --all",
            options.scope.repo_limit,
            options.action.as_str()
        )));
    }
    repositories.sort_by(|left, right| left.name.cmp(&right.name));

    fs::create_dir_all(&workspace)?;

    let mut cloned = Vec::new();
    let mut pulled = Vec::new();
    let mut skipped = Vec::new();
    let mut failed = Vec::new();

    for repository in &repositories {
        let local = workspace.join(&repository.name);
        match manage_repository(options.action, repository, &local).await {
            Ok(RepositoryOutcome::Cloned) => {
                cloned.push(repository.name.clone());
                report.push(
                    Finding::info(
                        "repository-cloned",
                        format!("cloned {}", repository.name_with_owner),
                    )
                    .with_target(local.display().to_string()),
                );
            }
            Ok(RepositoryOutcome::Pulled) => {
                pulled.push(repository.name.clone());
                report.push(
                    Finding::info(
                        "repository-pulled",
                        format!("fast-forward pull completed for {}", repository.name_with_owner),
                    )
                    .with_target(local.display().to_string()),
                );
            }
            Ok(RepositoryOutcome::Skipped(reason)) => {
                skipped.push(repository.name.clone());
                report.push(
                    Finding::info("repository-skipped", reason)
                        .with_target(local.display().to_string()),
                );
            }
            Err(message) => {
                failed.push(repository.name.clone());
                report.push(
                    Finding::error("repository-workspace-failed", message)
                        .with_target(local.display().to_string()),
                );
            }
        }
    }

    report.insert_metadata("repositoryCount", json!(repositories.len()));
    report.insert_metadata("clonedRepositories", json!(cloned));
    report.insert_metadata("pulledRepositories", json!(pulled));
    report.insert_metadata("skippedRepositories", json!(skipped));
    report.insert_metadata("failedRepositories", json!(failed));
    Ok(report.finalize())
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum RepositoryOutcome {
    Cloned,
    Pulled,
    Skipped(String),
}

async fn manage_repository(
    action: WorkspaceAction,
    repository: &GitHubRepository,
    local: &Path,
) -> Result<RepositoryOutcome, String> {
    if local.exists() {
        let metadata = fs::symlink_metadata(local)
            .map_err(|error| format!("could not inspect local checkout: {error}"))?;
        if metadata.file_type().is_symlink() {
            return Err("local repository path is a symbolic link; refusing to traverse it".to_owned());
        }
        if !metadata.is_dir() {
            return Err("local repository path exists but is not a directory".to_owned());
        }
        if !local.join(".git").exists() {
            return Err("local repository directory exists but is not a Git checkout".to_owned());
        }
        verify_origin(local, &repository.name_with_owner).await?;

        return match action {
            WorkspaceAction::Clone => Ok(RepositoryOutcome::Skipped(format!(
                "{} already has a verified local checkout",
                repository.name_with_owner
            ))),
            WorkspaceAction::Pull | WorkspaceAction::Sync => pull_repository(local).await,
        };
    }

    match action {
        WorkspaceAction::Pull => Ok(RepositoryOutcome::Skipped(format!(
            "{} has no local checkout; pull does not clone missing repositories",
            repository.name_with_owner
        ))),
        WorkspaceAction::Clone | WorkspaceAction::Sync => clone_repository(repository, local).await,
    }
}

async fn clone_repository(
    repository: &GitHubRepository,
    local: &Path,
) -> Result<RepositoryOutcome, String> {
    let mut command = Command::new("gh");
    command
        .arg("repo")
        .arg("clone")
        .arg(&repository.name_with_owner)
        .arg(local);
    let output = run_bounded(
        command,
        format!("gh repo clone {}", repository.name_with_owner),
        PROCESS_LIMITS,
    )
    .await
    .map_err(|error| error.to_string())?;
    if output.status.success() {
        Ok(RepositoryOutcome::Cloned)
    } else {
        Err(format!(
            "clone failed with exit code {:?}: {}",
            output.status.code(),
            diagnostic(&output.stderr, &output.stdout)
        ))
    }
}

async fn pull_repository(local: &Path) -> Result<RepositoryOutcome, String> {
    let mut command = Command::new("git");
    command.arg("-C").arg(local).arg("pull").arg("--ff-only");
    let output = run_bounded(
        command,
        format!("git -C {} pull --ff-only", local.display()),
        PROCESS_LIMITS,
    )
    .await
    .map_err(|error| error.to_string())?;
    if output.status.success() {
        Ok(RepositoryOutcome::Pulled)
    } else {
        Err(format!(
            "pull failed with exit code {:?}: {}",
            output.status.code(),
            diagnostic(&output.stderr, &output.stdout)
        ))
    }
}

async fn verify_origin(local: &Path, expected: &str) -> Result<(), String> {
    let mut command = Command::new("git");
    command
        .arg("-C")
        .arg(local)
        .arg("remote")
        .arg("get-url")
        .arg("origin");
    let output = run_bounded(
        command,
        format!("git -C {} remote get-url origin", local.display()),
        CaptureLimits::new(Duration::from_secs(30), 16 * 1024, 16 * 1024),
    )
    .await
    .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(format!(
            "could not read origin remote: {}",
            diagnostic(&output.stderr, &output.stdout)
        ));
    }
    let actual = normalize_github_remote(&output.stdout).ok_or_else(|| {
        format!(
            "origin is not a recognized github.com repository URL: {}",
            output.stdout.trim()
        )
    })?;
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(format!(
            "origin mismatch: expected {expected}, found {actual}; refusing to modify checkout"
        ));
    }
    Ok(())
}

fn normalize_github_remote(value: &str) -> Option<String> {
    let value = value.trim().trim_end_matches('/');
    let repository = value
        .strip_prefix("git@github.com:")
        .or_else(|| value.strip_prefix("ssh://git@github.com/"))
        .or_else(|| value.strip_prefix("https://github.com/"))
        .or_else(|| value.strip_prefix("http://github.com/"))?
        .trim_end_matches(".git");
    let mut parts = repository.split('/');
    let owner = parts.next()?;
    let name = parts.next()?;
    if owner.is_empty() || name.is_empty() || parts.next().is_some() {
        return None;
    }
    Some(format!("{owner}/{name}"))
}

fn diagnostic(stderr: &str, stdout: &str) -> String {
    let stderr = stderr.trim();
    if !stderr.is_empty() {
        return stderr.to_owned();
    }
    let stdout = stdout.trim();
    if !stdout.is_empty() {
        return stdout.to_owned();
    }
    "no diagnostic output".to_owned()
}

fn expand_workspace_root(root: &Path) -> Result<PathBuf, RuntimeError> {
    let text = root
        .to_str()
        .ok_or_else(|| RuntimeError::Usage("--dir must be valid UTF-8".to_owned()))?;
    let expanded = if text == "~" {
        PathBuf::from(home_directory()?)
    } else if let Some(suffix) = text.strip_prefix("~/") {
        PathBuf::from(home_directory()?).join(suffix)
    } else if text.starts_with('~') {
        return Err(RuntimeError::Usage(
            "--dir supports only ~ or ~/..., not named-user expansion".to_owned(),
        ));
    } else {
        root.to_path_buf()
    };

    if expanded.is_absolute() {
        Ok(expanded)
    } else {
        Ok(env::current_dir()?.join(expanded))
    }
}

fn home_directory() -> Result<String, RuntimeError> {
    env::var("HOME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            RuntimeError::Usage(
                "cannot expand --dir because HOME is not set; pass an absolute --dir".to_owned(),
            )
        })
}

fn confirm_workspace_path(
    workspace: &Path,
    options: &WorkspaceOptions,
) -> Result<bool, RuntimeError> {
    if !io::stdin().is_terminal() || !io::stderr().is_terminal() {
        return Err(RuntimeError::Usage(format!(
            "org {} requires terminal confirmation before touching {}; rerun with explicit --non-interactive for automation",
            options.action.as_str(),
            workspace.display()
        )));
    }
    confirm_workspace_path_from(
        &mut io::stdin().lock(),
        &mut io::stderr().lock(),
        workspace,
        options,
    )
}

fn confirm_workspace_path_from(
    reader: &mut impl BufRead,
    writer: &mut impl Write,
    workspace: &Path,
    options: &WorkspaceOptions,
) -> Result<bool, RuntimeError> {
    writeln!(writer, "Organization: {}", options.scope.owner)?;
    writeln!(writer, "Workspace path: {}", workspace.display())?;
    writeln!(
        writer,
        "Action: {} all visible repositories",
        options.action.as_str()
    )?;
    write!(writer, "Proceed with this path? Type YES to continue: ")?;
    writer.flush()?;
    let line = read_bounded_line(reader)?;
    Ok(line == "YES\n" || line == "YES\r\n")
}

fn read_bounded_line(reader: &mut impl BufRead) -> Result<String, RuntimeError> {
    let mut bytes = Vec::new();
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            return Ok(String::new());
        }
        let end = available
            .iter()
            .position(|byte| *byte == b'\n')
            .map_or(available.len(), |index| index + 1);
        if bytes.len() + end > 4096 {
            return Err(RuntimeError::Usage(
                "interactive confirmation exceeds 4096 bytes".to_owned(),
            ));
        }
        bytes.extend_from_slice(&available[..end]);
        let complete = available[end - 1] == b'\n';
        reader.consume(end);
        if complete {
            break;
        }
    }
    String::from_utf8(bytes)
        .map_err(|_| RuntimeError::Usage("interactive confirmation must be UTF-8".to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn options(action: WorkspaceAction) -> WorkspaceOptions {
        WorkspaceOptions {
            scope: OrgRepositoryScope {
                owner: "example-org".to_owned(),
                repo_limit: 1000,
                expected_repositories: vec![".github".to_owned()],
                family_prefix: Some("example-org".to_owned()),
                family_members: Vec::new(),
            },
            action,
            workspace_root: PathBuf::from("/tmp/codes"),
            all: true,
            non_interactive: false,
        }
    }

    #[test]
    fn remote_normalization_accepts_standard_github_forms() {
        for remote in [
            "git@github.com:example-org/repo.git",
            "ssh://git@github.com/example-org/repo.git",
            "https://github.com/example-org/repo.git",
            "http://github.com/example-org/repo",
        ] {
            assert_eq!(
                normalize_github_remote(remote).as_deref(),
                Some("example-org/repo")
            );
        }
        assert!(normalize_github_remote("https://example.com/example-org/repo.git").is_none());
        assert!(normalize_github_remote("https://github.com/a/b/c.git").is_none());
    }

    #[test]
    fn confirmation_requires_exact_yes_line_and_shows_path() {
        let mut output = Vec::new();
        assert!(
            confirm_workspace_path_from(
                &mut io::Cursor::new(b"YES\n"),
                &mut output,
                Path::new("/tmp/codes/example-org"),
                &options(WorkspaceAction::Sync),
            )
            .unwrap()
        );
        let text = String::from_utf8(output).unwrap();
        assert!(text.contains("Workspace path: /tmp/codes/example-org"));

        for input in [b"yes\n".as_slice(), b" YES\n", b"YES \n", b"YES"] {
            assert!(
                !confirm_workspace_path_from(
                    &mut io::Cursor::new(input),
                    &mut Vec::new(),
                    Path::new("/tmp/codes/example-org"),
                    &options(WorkspaceAction::Sync),
                )
                .unwrap()
            );
        }
    }

    #[test]
    fn workspace_directory_appends_the_org_once() {
        let value = workspace_directory(&options(WorkspaceAction::Clone)).unwrap();
        assert_eq!(value, PathBuf::from("/tmp/codes/example-org"));
    }
}
