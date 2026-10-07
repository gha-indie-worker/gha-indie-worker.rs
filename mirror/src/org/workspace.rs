use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::io::{self, BufRead, IsTerminal, Write};
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

use serde_json::json;
use tokio::process::Command;

use crate::error::RuntimeError;
use crate::github::{GhCli, GitHubRepository};
use crate::model::{CommandReport, Finding};
use crate::process::{CaptureLimits, CapturedOutput, run_bounded};

use super::names::{ensure_complete_inventory, validate_repository_name};
use super::{OrgRepositoryScope, validate_repository_scope};

const DEFAULT_WORKSPACE_ROOT: &str = "~/codes";
const PROCESS_LIMITS: CaptureLimits =
    CaptureLimits::new(Duration::from_secs(15 * 60), 256 * 1024, 256 * 1024);
const GIT_QUERY_LIMITS: CaptureLimits =
    CaptureLimits::new(Duration::from_secs(30), 64 * 1024, 64 * 1024);
const GIT_LOCATION_ENV: &[&str] = &[
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_NAMESPACE",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_PARAMETERS",
];

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
///
/// Existing parent symlinks are resolved so the confirmation shows the actual
/// filesystem destination. The final organization directory itself is never
/// accepted as a symlink.
pub fn workspace_directory(options: &WorkspaceOptions) -> Result<PathBuf, RuntimeError> {
    let root = expand_workspace_root(&options.workspace_root)?;
    let lexical_workspace = root.join(&options.scope.owner);
    reject_direct_workspace_symlink(&lexical_workspace)?;
    resolve_through_existing_ancestor(&lexical_workspace)
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

    require_git_available().await?;
    gh.require_authentication().await?;
    let account = gh.authenticated_login().await?;
    let membership = gh.organization_membership(&options.scope.owner).await?;
    if membership.role != "admin" {
        return Err(RuntimeError::Usage(format!(
            "org {} --all requires organization-owner membership so the repository inventory is authoritative",
            options.action.as_str()
        )));
    }

    let mut repositories = gh
        .list_repositories(&options.scope.owner, options.scope.repo_limit)
        .await?;
    ensure_account_unchanged(gh, &account).await?;
    ensure_complete_inventory(&repositories, &options.scope)?;
    validate_inventory(&repositories, &options.scope.owner)?;
    repositories.sort_by(|left, right| {
        left.name
            .to_ascii_lowercase()
            .cmp(&right.name.to_ascii_lowercase())
    });

    report.insert_metadata("account", json!(&account));
    report.insert_metadata("organizationRole", json!(&membership.role));
    report.insert_metadata("repositoryCount", json!(repositories.len()));

    let repository_names = repositories
        .iter()
        .map(|repository| repository.name.clone())
        .collect::<Vec<_>>();
    let mut cloned = Vec::new();
    let mut pulled = Vec::new();
    let mut skipped = Vec::new();
    let mut failed = Vec::new();

    if let Err(error) = prepare_workspace_directory(&workspace) {
        record_workspace_outcomes(
            &mut report,
            &cloned,
            &pulled,
            &skipped,
            &failed,
            &repository_names,
        );
        return Err(error.with_partial_report(report));
    }

    for (index, repository) in repositories.iter().enumerate() {
        if let Err(error) = ensure_account_unchanged(gh, &account).await {
            record_workspace_outcomes(
                &mut report,
                &cloned,
                &pulled,
                &skipped,
                &failed,
                &repository_names[index..],
            );
            return Err(error.with_partial_report(report));
        }
        if let Err(error) = ensure_workspace_identity(&workspace) {
            record_workspace_outcomes(
                &mut report,
                &cloned,
                &pulled,
                &skipped,
                &failed,
                &repository_names[index..],
            );
            return Err(error.with_partial_report(report));
        }

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
                        format!(
                            "fast-forward pull completed for {}",
                            repository.name_with_owner
                        ),
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

        if let Err(error) = ensure_account_unchanged(gh, &account).await {
            record_workspace_outcomes(
                &mut report,
                &cloned,
                &pulled,
                &skipped,
                &failed,
                &repository_names[index + 1..],
            );
            return Err(error.with_partial_report(report));
        }
    }

    if let Err(error) = ensure_account_unchanged(gh, &account).await {
        record_workspace_outcomes(&mut report, &cloned, &pulled, &skipped, &failed, &[]);
        return Err(error.with_partial_report(report));
    }

    record_workspace_outcomes(&mut report, &cloned, &pulled, &skipped, &failed, &[]);
    Ok(report.finalize())
}

fn record_workspace_outcomes(
    report: &mut CommandReport,
    cloned: &[String],
    pulled: &[String],
    skipped: &[String],
    failed: &[String],
    not_attempted: &[String],
) {
    report.insert_metadata("clonedRepositories", json!(cloned));
    report.insert_metadata("pulledRepositories", json!(pulled));
    report.insert_metadata("skippedRepositories", json!(skipped));
    report.insert_metadata("failedRepositories", json!(failed));
    report.insert_metadata("notAttemptedRepositories", json!(not_attempted));
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
    if checkout_target_present(local)? {
        validate_checkout(local).await?;
        verify_origin(local, &repository.name_with_owner).await?;

        return match action {
            WorkspaceAction::Clone => Ok(RepositoryOutcome::Skipped(format!(
                "{} already has a verified local checkout",
                repository.name_with_owner
            ))),
            WorkspaceAction::Pull | WorkspaceAction::Sync => {
                pull_repository(local, &repository.name_with_owner).await
            }
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

fn checkout_target_present(local: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(local) {
        Ok(_) => Ok(true),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(_) => Err("could not inspect local repository target".to_owned()),
    }
}

async fn clone_repository(
    repository: &GitHubRepository,
    local: &Path,
) -> Result<RepositoryOutcome, String> {
    if checkout_target_present(local)? {
        return Err(
            "local repository target appeared before clone; refusing to overwrite it".to_owned(),
        );
    }

    let command = clone_command(repository, local);
    let output = run_bounded(
        command,
        format!("gh repo clone {}", repository.name_with_owner),
        PROCESS_LIMITS,
    )
    .await
    .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(format!(
            "clone failed with exit code {:?}; inspect the local target before retrying",
            output.status.code()
        ));
    }

    validate_checkout(local).await?;
    verify_origin(local, &repository.name_with_owner).await?;
    ensure_clean_checkout(local).await?;
    Ok(RepositoryOutcome::Cloned)
}

async fn pull_repository(
    local: &Path,
    expected_repository: &str,
) -> Result<RepositoryOutcome, String> {
    ensure_clean_checkout(local).await?;
    let remote_branch = current_origin_upstream(local).await?;

    let command = pull_command(local, &remote_branch);
    let output = run_bounded(
        command,
        format!("git -C {} pull --ff-only --no-rebase", local.display()),
        PROCESS_LIMITS,
    )
    .await
    .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(format!(
            "pull failed with exit code {:?}; local changes were not merged automatically",
            output.status.code()
        ));
    }

    validate_checkout(local).await?;
    verify_origin(local, expected_repository).await?;
    ensure_clean_checkout(local).await?;
    Ok(RepositoryOutcome::Pulled)
}

fn clone_command(repository: &GitHubRepository, local: &Path) -> Command {
    let mut command = Command::new("gh");
    sanitize_git_environment(&mut command);
    configure_transient_git_safety(&mut command);
    command
        .env("GH_HOST", "github.com")
        .arg("repo")
        .arg("clone")
        .arg(&repository.name_with_owner)
        .arg(local)
        .arg("--")
        .arg("--no-recurse-submodules");
    command
}

fn pull_command(local: &Path, remote_branch: &str) -> Command {
    let mut command = git_command(local);
    command
        .arg("pull")
        .arg("--ff-only")
        .arg("--no-rebase")
        .arg("--recurse-submodules=no")
        .arg("--")
        .arg("origin")
        .arg(remote_branch);
    command
}

async fn validate_checkout(local: &Path) -> Result<(), String> {
    let metadata =
        fs::symlink_metadata(local).map_err(|_| "could not inspect local checkout".to_owned())?;
    if metadata.file_type().is_symlink() {
        return Err("local repository path is a symbolic link; refusing to traverse it".to_owned());
    }
    if !metadata.is_dir() {
        return Err("local repository path exists but is not a directory".to_owned());
    }

    let dot_git = local.join(".git");
    let git_metadata = fs::symlink_metadata(&dot_git)
        .map_err(|_| "local repository directory is missing .git metadata".to_owned())?;
    if git_metadata.file_type().is_symlink() {
        return Err("local .git metadata is a symbolic link; refusing to traverse it".to_owned());
    }
    if !git_metadata.is_dir() && !git_metadata.is_file() {
        return Err("local .git metadata is not a regular file or directory".to_owned());
    }

    let mut command = git_command(local);
    command.arg("rev-parse").arg("--show-toplevel");
    let output = run_git_query(command, local, "rev-parse --show-toplevel").await?;
    if !output.status.success() {
        return Err("local repository directory is not a valid Git work tree".to_owned());
    }
    let root = output.stdout.trim();
    if root.is_empty() || root.contains(['\n', '\r']) {
        return Err("Git returned an invalid work-tree root".to_owned());
    }

    let expected = fs::canonicalize(local)
        .map_err(|_| "could not canonicalize local repository path".to_owned())?;
    let actual = fs::canonicalize(root)
        .map_err(|_| "could not canonicalize Git work-tree root".to_owned())?;
    if actual != expected {
        return Err(
            "local repository path is nested in or redirected to a different Git work tree"
                .to_owned(),
        );
    }
    Ok(())
}

async fn ensure_clean_checkout(local: &Path) -> Result<(), String> {
    let mut command = git_command(local);
    command
        .arg("status")
        .arg("--porcelain=v1")
        .arg("--untracked-files=normal");
    let output = run_git_query(command, local, "status --porcelain=v1").await?;
    if !output.status.success() {
        return Err("could not inspect local repository status".to_owned());
    }
    if output.stdout.is_empty() {
        Ok(())
    } else {
        Err(
            "local checkout has uncommitted or untracked changes; refusing to pull automatically"
                .to_owned(),
        )
    }
}

async fn current_origin_upstream(local: &Path) -> Result<String, String> {
    let mut branch_command = git_command(local);
    branch_command
        .arg("symbolic-ref")
        .arg("--quiet")
        .arg("--short")
        .arg("HEAD");
    let branch = run_git_query(branch_command, local, "symbolic-ref --short HEAD").await?;
    if !branch.status.success() || branch.stdout.trim().is_empty() {
        return Err("local checkout is detached or has no current branch".to_owned());
    }

    let mut upstream_command = git_command(local);
    upstream_command
        .arg("rev-parse")
        .arg("--abbrev-ref")
        .arg("--symbolic-full-name")
        .arg("@{upstream}");
    let upstream = run_git_query(
        upstream_command,
        local,
        "rev-parse --abbrev-ref --symbolic-full-name @{upstream}",
    )
    .await?;
    if !upstream.status.success() {
        return Err("current branch has no configured upstream; refusing to guess".to_owned());
    }

    origin_upstream_branch(&upstream.stdout).ok_or_else(|| {
        "current branch upstream is not origin/*; refusing to pull from another remote".to_owned()
    })
}

fn origin_upstream_branch(value: &str) -> Option<String> {
    let value = value.trim();
    let branch = value.strip_prefix("origin/")?;
    if branch.is_empty() || branch.chars().any(char::is_control) {
        return None;
    }
    Some(branch.to_owned())
}

async fn verify_origin(local: &Path, expected: &str) -> Result<(), String> {
    let mut command = git_command(local);
    command.arg("remote").arg("get-url").arg("origin");
    let output = run_git_query(command, local, "remote get-url origin").await?;
    if !output.status.success() {
        return Err("could not read origin remote".to_owned());
    }
    let Some(actual) = normalize_github_remote(&output.stdout) else {
        return Err("origin is not a supported HTTPS or SSH github.com repository URL".to_owned());
    };
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(
            "origin does not match the expected GitHub repository; refusing to modify checkout"
                .to_owned(),
        );
    }
    Ok(())
}

fn normalize_github_remote(value: &str) -> Option<String> {
    let value = value.trim().trim_end_matches('/');
    let repository = value
        .strip_prefix("git@github.com:")
        .or_else(|| value.strip_prefix("ssh://git@github.com/"))
        .or_else(|| value.strip_prefix("https://github.com/"))?
        .trim_end_matches(".git");
    let mut parts = repository.split('/');
    let owner = parts.next()?;
    let name = parts.next()?;
    if owner.is_empty()
        || name.is_empty()
        || parts.next().is_some()
        || validate_repository_name(name).is_err()
    {
        return None;
    }
    Some(format!("{owner}/{name}"))
}

async fn run_git_query(
    command: Command,
    local: &Path,
    operation: &str,
) -> Result<CapturedOutput, String> {
    run_bounded(
        command,
        format!("git -C {} {operation}", local.display()),
        GIT_QUERY_LIMITS,
    )
    .await
    .map_err(|error| error.to_string())
}

fn git_command(local: &Path) -> Command {
    let mut command = Command::new("git");
    sanitize_git_environment(&mut command);
    command
        .arg("-c")
        .arg(format!("core.hooksPath={}", disabled_hooks_path()))
        .arg("-c")
        .arg("core.fsmonitor=false")
        .arg("-C")
        .arg(local);
    command
}

fn sanitize_git_environment(command: &mut Command) {
    for key in GIT_LOCATION_ENV {
        command.env_remove(key);
    }
}

fn configure_transient_git_safety(command: &mut Command) {
    command
        .env("GIT_CONFIG_COUNT", "2")
        .env("GIT_CONFIG_KEY_0", "core.hooksPath")
        .env("GIT_CONFIG_VALUE_0", disabled_hooks_path())
        .env("GIT_CONFIG_KEY_1", "core.fsmonitor")
        .env("GIT_CONFIG_VALUE_1", "false");
}

const fn disabled_hooks_path() -> &'static str {
    if cfg!(windows) { "NUL" } else { "/dev/null" }
}

async fn require_git_available() -> Result<(), RuntimeError> {
    let mut command = Command::new("git");
    sanitize_git_environment(&mut command);
    command.arg("--version");
    let output = run_bounded(
        command,
        "git --version",
        CaptureLimits::new(Duration::from_secs(15), 16 * 1024, 16 * 1024),
    )
    .await?;
    if output.status.success() {
        Ok(())
    } else {
        Err(RuntimeError::Dependency {
            command: "git --version".to_owned(),
            exit_code: output.status.code(),
            message: "git is required for organization workspace operations".to_owned(),
        })
    }
}

fn validate_inventory(repositories: &[GitHubRepository], owner: &str) -> Result<(), RuntimeError> {
    let mut names = BTreeSet::new();
    for repository in repositories {
        if validate_repository_name(&repository.name).is_err() {
            return Err(RuntimeError::Invariant(
                "GitHub returned an invalid repository name in organization inventory".to_owned(),
            ));
        }
        let expected_full_name = format!("{owner}/{}", repository.name);
        let expected_url = format!("https://github.com/{owner}/{}", repository.name);
        if !repository
            .name_with_owner
            .eq_ignore_ascii_case(&expected_full_name)
            || !repository.url.eq_ignore_ascii_case(&expected_url)
        {
            return Err(RuntimeError::Invariant(
                "GitHub returned inconsistent repository identity metadata".to_owned(),
            ));
        }
        if !names.insert(repository.name.to_ascii_lowercase()) {
            return Err(RuntimeError::Invariant(
                "GitHub returned duplicate repository identities in organization inventory"
                    .to_owned(),
            ));
        }
    }
    Ok(())
}

async fn ensure_account_unchanged(gh: &GhCli, expected: &str) -> Result<(), RuntimeError> {
    let current = gh.authenticated_login().await?;
    if current.eq_ignore_ascii_case(expected) {
        Ok(())
    } else {
        Err(RuntimeError::Invariant(
            "active GitHub account changed during organization workspace operation".to_owned(),
        ))
    }
}

fn expand_workspace_root(root: &Path) -> Result<PathBuf, RuntimeError> {
    let text = root
        .to_str()
        .ok_or_else(|| RuntimeError::Usage("--dir must be valid UTF-8".to_owned()))?;
    if text.trim().is_empty() || text.chars().any(char::is_control) {
        return Err(RuntimeError::Usage(
            "--dir must be a nonempty path without control characters".to_owned(),
        ));
    }

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

    if expanded
        .components()
        .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(RuntimeError::Usage(
            "--dir must not contain parent-directory traversal".to_owned(),
        ));
    }

    let absolute = if expanded.is_absolute() {
        expanded
    } else {
        env::current_dir()?.join(expanded)
    };
    Ok(absolute)
}

fn home_directory() -> Result<String, RuntimeError> {
    let value = env::var("HOME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            RuntimeError::Usage(
                "cannot expand --dir because HOME is not set; pass an absolute --dir".to_owned(),
            )
        })?;
    if value.chars().any(char::is_control) || !Path::new(&value).is_absolute() {
        return Err(RuntimeError::Usage(
            "HOME must be an absolute path without control characters to expand --dir".to_owned(),
        ));
    }
    Ok(value)
}

fn reject_direct_workspace_symlink(workspace: &Path) -> Result<(), RuntimeError> {
    match fs::symlink_metadata(workspace) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(RuntimeError::Usage(
            "final organization workspace path must not be a symbolic link".to_owned(),
        )),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn resolve_through_existing_ancestor(path: &Path) -> Result<PathBuf, RuntimeError> {
    let mut cursor = path.to_path_buf();
    let mut missing = Vec::new();

    loop {
        match fs::symlink_metadata(&cursor) {
            Ok(_) => break,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let name = cursor.file_name().ok_or_else(|| {
                    RuntimeError::Usage("could not resolve workspace path".to_owned())
                })?;
                missing.push(name.to_os_string());
                cursor = cursor
                    .parent()
                    .ok_or_else(|| {
                        RuntimeError::Usage("could not resolve workspace parent".to_owned())
                    })?
                    .to_path_buf();
            }
            Err(error) => return Err(error.into()),
        }
    }

    let metadata = fs::metadata(&cursor)?;
    if !metadata.is_dir() {
        return Err(RuntimeError::Usage(
            "existing workspace ancestor is not a directory".to_owned(),
        ));
    }
    let mut resolved = fs::canonicalize(&cursor)?;
    for name in missing.iter().rev() {
        resolved.push(name);
    }
    Ok(resolved)
}

fn prepare_workspace_directory(workspace: &Path) -> Result<(), RuntimeError> {
    match fs::symlink_metadata(workspace) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(RuntimeError::Invariant(
                    "workspace path changed to a symlink or non-directory after confirmation"
                        .to_owned(),
                ));
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir_all(workspace)?;
        }
        Err(error) => return Err(error.into()),
    }
    ensure_workspace_identity(workspace)
}

fn ensure_workspace_identity(workspace: &Path) -> Result<(), RuntimeError> {
    let metadata = fs::symlink_metadata(workspace)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(RuntimeError::Invariant(
            "workspace path changed to a symlink or non-directory during operation".to_owned(),
        ));
    }
    let actual = fs::canonicalize(workspace)?;
    if actual != workspace {
        return Err(RuntimeError::Invariant(
            "workspace canonical path changed during operation".to_owned(),
        ));
    }
    Ok(())
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

    fn repository(name: &str) -> GitHubRepository {
        GitHubRepository {
            name: name.to_owned(),
            name_with_owner: format!("example-org/{name}"),
            is_archived: false,
            is_fork: false,
            is_private: true,
            url: format!("https://github.com/example-org/{name}"),
        }
    }

    #[test]
    fn remote_normalization_accepts_only_supported_github_forms() {
        for remote in [
            "git@github.com:example-org/repo.git",
            "ssh://git@github.com/example-org/repo.git",
            "https://github.com/example-org/repo.git",
        ] {
            assert_eq!(
                normalize_github_remote(remote).as_deref(),
                Some("example-org/repo")
            );
        }
        for remote in [
            "http://github.com/example-org/repo",
            "https://token@github.com/example-org/repo.git",
            "https://example.com/example-org/repo.git",
            "https://github.com/a/b/c.git",
            "https://github.com/example-org/..",
        ] {
            assert!(normalize_github_remote(remote).is_none(), "{remote}");
        }
    }

    #[test]
    fn upstream_must_be_on_origin() {
        assert_eq!(
            origin_upstream_branch("origin/main\n").as_deref(),
            Some("main")
        );
        assert_eq!(
            origin_upstream_branch("origin/feature/harden").as_deref(),
            Some("feature/harden")
        );
        for value in ["", "origin/", "fork/main", "upstream/main", "origin/a\nb"] {
            assert!(origin_upstream_branch(value).is_none(), "{value:?}");
        }
    }

    #[test]
    fn organization_inventory_identity_is_validated_before_filesystem_work() {
        let valid = vec![repository("api"), repository("docs")];
        assert!(validate_inventory(&valid, "example-org").is_ok());

        let mut wrong_owner = repository("api");
        wrong_owner.name_with_owner = "other/api".to_owned();
        assert!(validate_inventory(&[wrong_owner], "example-org").is_err());

        let mut wrong_url = repository("api");
        wrong_url.url = "https://github.com/other/api".to_owned();
        assert!(validate_inventory(&[wrong_url], "example-org").is_err());

        let duplicate = vec![repository("API"), repository("api")];
        assert!(validate_inventory(&duplicate, "example-org").is_err());

        let mut traversal = repository("api");
        traversal.name = "..".to_owned();
        assert!(validate_inventory(&[traversal], "example-org").is_err());
    }

    fn command_args(command: &Command) -> Vec<String> {
        command
            .as_std()
            .get_args()
            .map(|argument| argument.to_string_lossy().into_owned())
            .collect()
    }

    #[test]
    fn git_commands_disable_hooks_and_external_fsmonitor() {
        let args = command_args(&git_command(Path::new("/tmp/repo")));
        let hooks = format!("core.hooksPath={}", disabled_hooks_path());
        assert!(
            args.windows(2)
                .any(|pair| pair[0] == "-c" && pair[1] == hooks)
        );
        assert!(
            args.windows(2)
                .any(|pair| pair[0] == "-c" && pair[1] == "core.fsmonitor=false")
        );
    }

    #[test]
    fn pull_command_is_fast_forward_only_and_scoped_to_origin_without_submodules() {
        let args = command_args(&pull_command(Path::new("/tmp/repo"), "main"));
        let pull = args.iter().position(|value| value == "pull").unwrap();
        assert_eq!(
            &args[pull + 1..],
            [
                "--ff-only".to_owned(),
                "--no-rebase".to_owned(),
                "--recurse-submodules=no".to_owned(),
                "--".to_owned(),
                "origin".to_owned(),
                "main".to_owned(),
            ]
        );
    }

    #[test]
    fn clone_command_pins_github_and_disables_submodule_recursion() {
        let repo = repository("api");
        let command = clone_command(&repo, Path::new("/tmp/api"));
        let args = command_args(&command);
        assert_eq!(
            args,
            vec![
                "repo".to_owned(),
                "clone".to_owned(),
                "example-org/api".to_owned(),
                "/tmp/api".to_owned(),
                "--".to_owned(),
                "--no-recurse-submodules".to_owned(),
            ]
        );
        let environment = command
            .as_std()
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().into_owned(),
                    value.map(|value| value.to_string_lossy().into_owned()),
                )
            })
            .collect::<std::collections::BTreeMap<_, _>>();
        assert_eq!(environment["GH_HOST"].as_deref(), Some("github.com"));
        assert_eq!(environment["GIT_CONFIG_COUNT"].as_deref(), Some("2"));
        assert_eq!(
            environment["GIT_CONFIG_KEY_0"].as_deref(),
            Some("core.hooksPath")
        );
        assert_eq!(
            environment["GIT_CONFIG_KEY_1"].as_deref(),
            Some("core.fsmonitor")
        );
    }

    #[cfg(unix)]
    #[test]
    fn broken_symlink_checkout_target_is_never_treated_as_missing() {
        use std::os::unix::fs::symlink;

        let temporary = tempfile::tempdir().unwrap();
        let local = temporary.path().join("repo");
        symlink(temporary.path().join("does-not-exist"), &local).unwrap();
        assert!(checkout_target_present(&local).unwrap());
    }

    #[test]
    fn disabled_hook_path_is_platform_specific_and_nonempty() {
        assert!(!disabled_hooks_path().is_empty());
        if cfg!(windows) {
            assert_eq!(disabled_hooks_path(), "NUL");
        } else {
            assert_eq!(disabled_hooks_path(), "/dev/null");
        }
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

    #[test]
    fn workspace_directory_rejects_parent_traversal_and_controls() {
        let mut value = options(WorkspaceAction::Sync);
        value.workspace_root = PathBuf::from("/tmp/codes/../other");
        assert!(workspace_directory(&value).is_err());

        value.workspace_root = PathBuf::from("/tmp/codes\nspoof");
        assert!(workspace_directory(&value).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn direct_workspace_symlink_is_rejected_but_parent_symlink_is_resolved() {
        use std::os::unix::fs::symlink;

        let temporary = tempfile::tempdir().unwrap();
        let actual_root = temporary.path().join("actual");
        fs::create_dir(&actual_root).unwrap();

        let root_link = temporary.path().join("codes");
        symlink(&actual_root, &root_link).unwrap();
        let mut value = options(WorkspaceAction::Sync);
        value.workspace_root = root_link;
        assert_eq!(
            workspace_directory(&value).unwrap(),
            fs::canonicalize(&actual_root).unwrap().join("example-org")
        );

        let target = temporary.path().join("target");
        fs::create_dir(&target).unwrap();
        symlink(&target, actual_root.join("example-org")).unwrap();
        assert!(workspace_directory(&value).is_err());
    }
}
