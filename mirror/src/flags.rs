use std::collections::HashMap;
use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::PathBuf;

use flags2env::BundledFlags2Env;
use serde::Deserialize;

use crate::audit::{
    ContractAuditOptions, EnvironmentAuditOptions, GitHubOrgAuditOptions, PackageAuditOptions,
    RepositoryAuditOptions,
};
use crate::codespace::{CodespaceEdgeAction, CodespaceEdgeCommand, CodespaceEdgeOptions};
use crate::error::RuntimeError;
use crate::log_control::CliLogLevel;
use crate::org::{
    CreateMissingRepositoriesOptions, ListMissingRepositoriesOptions, OrgRepositoryScope,
    RepositoryVisibility, SetRepositoryVisibilityOptions, WorkspaceAction, WorkspaceOptions,
    normalize_repository_arguments, validate_org_login,
};

use crate::orgs::{OrganizationsOptions, flags::OrganizationsFlagValues};

/// Build-time package-owned flags-2-env contract. This path is never resolved
/// relative to the caller's current working directory.
const SOURCE_FLAG_CONTRACT_PATH: &str =
    concat!(env!("CARGO_MANIFEST_DIR"), "/.cli-flags.toml");

/// Environment variable used by zed-pkg to bind an installed binary to its
/// package-owned flags-2-env contract.
pub const FLAG_CONTRACT_ENV: &str = "FLAGS2ENV_CONFIG";

/// Resolve the one flags-2-env contract used for audit, parsing, command
/// resolution, and typed coercion.
///
/// A zed-pkg/post-install launcher binds [`FLAG_CONTRACT_ENV`] to the retained
/// package-owned `.cli-flags.toml`. Direct binary entrypoints bind an embedded
/// materialized copy before reaching this library. Library/source callers may
/// use only the build-time package-owned contract; the current working
/// directory is never searched.
pub fn active_flag_contract_path() -> Result<String, RuntimeError> {
    let path = resolve_flag_contract_path(env::var_os(FLAG_CONTRACT_ENV))?;
    validate_flag_contract_path(path)
}

fn resolve_flag_contract_path(value: Option<OsString>) -> Result<String, RuntimeError> {
    let value = value.unwrap_or_else(|| OsString::from(SOURCE_FLAG_CONTRACT_PATH));
    let value = value.into_string().map_err(|_| {
        RuntimeError::FlagContract(format!(
            "{FLAG_CONTRACT_ENV} must contain a UTF-8 package-owned contract path"
        ))
    })?;
    if value.trim().is_empty() {
        return Err(RuntimeError::FlagContract(format!(
            "{FLAG_CONTRACT_ENV} must not be empty"
        )));
    }
    if !PathBuf::from(&value).is_absolute() {
        return Err(RuntimeError::FlagContract(format!(
            "{FLAG_CONTRACT_ENV} must be an absolute package-owned .cli-flags.toml path"
        )));
    }
    Ok(value)
}

fn validate_flag_contract_path(path: String) -> Result<String, RuntimeError> {
    let metadata = fs::metadata(&path).map_err(|_| {
        RuntimeError::FlagContract(format!(
            "no package-owned .cli-flags.toml was found at `{path}`"
        ))
    })?;
    if !metadata.is_file() {
        return Err(RuntimeError::FlagContract(format!(
            "package-owned .cli-flags.toml is not a regular file at `{path}`"
        )));
    }
    fs::File::open(&path).map_err(|_| {
        RuntimeError::FlagContract(format!(
            "package-owned .cli-flags.toml is not readable at `{path}`"
        ))
    })?;
    Ok(path)
}

/// Fully resolved CLI command.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CliCommand {
    /// Report parser and dependency health.
    Doctor,
    /// Manage a loopback Codespaces preview server and Cloudflare Tunnel connector.
    CodespaceEdge(CodespaceEdgeCommand),
    /// Inspect GitHub organization inventory and available actions.
    Org(OrgRepositoryScope),
    /// List canonical repositories missing from an organization.
    OrgListMissing(ListMissingRepositoriesOptions),
    /// Create selected or all missing canonical repositories.
    OrgCreateMissing(CreateMissingRepositoriesOptions),
    /// Change visibility on selected existing repositories.
    OrgSetRepositoryVisibility(SetRepositoryVisibilityOptions),
    /// Clone, pull, or synchronize a local organization workspace.
    OrgWorkspace(WorkspaceOptions),
    /// Discover, select, and reconcile multiple GitHub organizations.
    Organizations(Box<OrganizationsOptions>),
    /// Audit GitHub organization topology.
    AuditOrg(GitHubOrgAuditOptions),
    /// Audit a local repository.
    AuditRepository(RepositoryAuditOptions),
    /// Audit SOPS-encrypted dotenv requirements against the local environment.
    AuditEnvironment(EnvironmentAuditOptions),
    /// Audit Cargo/zed-pkg publishing metadata.
    AuditPackage(PackageAuditOptions),
    /// Execute the TypeSpec/JSON Schema validator.
    AuditContract(ContractAuditOptions),
}

impl CliCommand {
    /// Whether this command uses one raw repository name per stdout line.
    #[must_use]
    pub(crate) fn emits_repository_names(&self) -> bool {
        matches!(self, Self::OrgListMissing(options) if !options.report)
    }
}

/// Process-level invocation after flags-2-env validation and coercion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CliInvocation {
    /// Whether stdout uses newline-delimited JSON logs.
    pub json: bool,
    /// Logging threshold resolved by flags-2-env.
    pub log_level: CliLogLevel,
    /// Command to execute.
    pub command: CliCommand,
}

#[derive(Debug, Default, Deserialize)]
struct ResolvedValues {
    #[serde(rename = "ORES_CLI_JSON", default = "default_true")]
    json: bool,
    #[serde(rename = "ORES_CLI_LOG_LEVEL", default = "default_log_level")]
    log_level: String,
    #[serde(flatten)]
    organizations: OrganizationsFlagValues,

    #[serde(
        rename = "ORES_CLI_CODESPACE_EDGE_PORT",
        default = "default_codespace_edge_port"
    )]
    codespace_edge_port: u16,
    #[serde(
        rename = "ORES_CLI_CODESPACE_EDGE_STATE_DIR",
        default = "default_codespace_edge_state_dir"
    )]
    codespace_edge_state_dir: String,
    #[serde(
        rename = "ORES_CLI_CODESPACE_EDGE_CLOUDFLARED",
        default = "default_cloudflared"
    )]
    codespace_edge_cloudflared: String,

    #[serde(rename = "ORES_CLI_ORG_NAME", default)]
    org_name: String,
    #[serde(rename = "ORES_CLI_ORG_REPO_LIMIT", default = "default_repo_limit")]
    org_repo_limit: usize,
    #[serde(
        rename = "ORES_CLI_ORG_EXPECTED_REPOS",
        default = "default_expected_repos"
    )]
    org_expected_repos: String,
    #[serde(rename = "ORES_CLI_ORG_FAMILY_PREFIX", default)]
    org_family_prefix: String,
    #[serde(
        rename = "ORES_CLI_ORG_FAMILY_MEMBERS",
        default = "default_family_members"
    )]
    org_family_members: String,
    #[serde(rename = "ORES_CLI_ORG_STANDARD_FAMILY", default = "default_true")]
    org_standard_family: bool,
    #[serde(rename = "ORES_CLI_ORG_LIST_REPORT", default)]
    org_list_report: bool,
    #[serde(rename = "ORES_CLI_ORG_CREATE_ALL", default)]
    org_create_all: bool,
    #[serde(rename = "ORES_CLI_ORG_VISIBILITY", default = "default_visibility")]
    org_visibility: String,
    #[serde(rename = "ORES_CLI_ORG_SET_VISIBILITY_ALL", default)]
    org_set_visibility_all: bool,
    #[serde(rename = "ORES_CLI_ORG_SET_VISIBILITY_DRY_RUN", default)]
    org_set_visibility_dry_run: bool,
    #[serde(rename = "ORES_CLI_ORG_SET_VISIBILITY_EXCLUDE", default)]
    org_set_visibility_exclude: String,
    #[serde(rename = "ORES_CLI_ORG_SET_VISIBILITY", default = "default_visibility")]
    org_set_visibility: String,
    #[serde(rename = "ORES_CLI_ORG_SET_VISIBILITY_ACCEPT_CONSEQUENCES", default)]
    org_set_visibility_accept_consequences: bool,

    #[serde(rename = "ORES_CLI_ORG_CLONE_ALL", default)]
    org_clone_all: bool,
    #[serde(rename = "ORES_CLI_ORG_CLONE_DIR", default = "default_workspace_dir")]
    org_clone_dir: String,
    #[serde(rename = "ORES_CLI_ORG_CLONE_NON_INTERACTIVE", default)]
    org_clone_non_interactive: bool,
    #[serde(rename = "ORES_CLI_ORG_PULL_ALL", default)]
    org_pull_all: bool,
    #[serde(rename = "ORES_CLI_ORG_PULL_DIR", default = "default_workspace_dir")]
    org_pull_dir: String,
    #[serde(rename = "ORES_CLI_ORG_PULL_NON_INTERACTIVE", default)]
    org_pull_non_interactive: bool,
    #[serde(rename = "ORES_CLI_ORG_SYNC_ALL", default)]
    org_sync_all: bool,
    #[serde(rename = "ORES_CLI_ORG_SYNC_DIR", default = "default_workspace_dir")]
    org_sync_dir: String,
    #[serde(rename = "ORES_CLI_ORG_SYNC_NON_INTERACTIVE", default)]
    org_sync_non_interactive: bool,

    #[serde(rename = "ORES_CLI_GITHUB_OWNER", default)]
    github_owner: String,
    #[serde(rename = "ORES_CLI_GITHUB_REPO_LIMIT", default = "default_repo_limit")]
    github_repo_limit: usize,
    #[serde(rename = "ORES_CLI_EXPECTED_REPOS", default = "default_expected_repos")]
    expected_repos: String,
    #[serde(rename = "ORES_CLI_FAMILY_PREFIX", default)]
    family_prefix: String,
    #[serde(rename = "ORES_CLI_FAMILY_MEMBERS", default = "default_family_members")]
    family_members: String,
    #[serde(rename = "ORES_CLI_REQUIRE_DOCS_REPO", default = "default_true")]
    require_docs_repo: bool,
    #[serde(rename = "ORES_CLI_CHECK_LAYOUT", default = "default_true")]
    check_layout: bool,
    #[serde(
        rename = "ORES_CLI_LAYOUT_REQUIRED",
        default = "default_layout_required"
    )]
    layout_required: String,
    #[serde(
        rename = "ORES_CLI_MAX_LAYOUT_REPOS",
        default = "default_max_layout_repos"
    )]
    max_layout_repos: usize,
    #[serde(rename = "ORES_CLI_INCLUDE_ARCHIVED", default)]
    include_archived: bool,

    #[serde(rename = "ORES_CLI_REPOSITORY_PATH", default = "default_dot")]
    repository_path: String,
    #[serde(rename = "ORES_CLI_REPOSITORY_PROFILE", default = "default_profile")]
    repository_profile: String,
    #[serde(rename = "ORES_CLI_REQUIRED_PATHS", default)]
    required_paths: String,

    #[serde(rename = "ORES_CLI_ENV_PATH", default = "default_dot")]
    environment_path: String,
    #[serde(
        rename = "ORES_CLI_ENV_ENCRYPTED_DIR",
        default = "default_encrypted_env_dir"
    )]
    environment_encrypted_dir: String,
    #[serde(
        rename = "ORES_CLI_ENV_ENVIRONMENTS",
        default = "default_environment_selection"
    )]
    environment_environments: String,
    #[serde(rename = "ORES_CLI_ENV_SOPS", default = "default_sops")]
    environment_sops: String,
    #[serde(
        rename = "ORES_CLI_ENV_MAX_CIPHERTEXT_BYTES",
        default = "default_max_ciphertext_bytes"
    )]
    environment_max_ciphertext_bytes: u64,
    #[serde(rename = "ORES_CLI_ENV_REQUIRE_NON_EMPTY", default = "default_true")]
    environment_require_non_empty: bool,

    #[serde(rename = "ORES_CLI_PACKAGE_PATH", default = "default_dot")]
    package_path: String,
    #[serde(rename = "ORES_CLI_REQUIRE_CARGO_LOCK", default = "default_true")]
    require_cargo_lock: bool,

    #[serde(rename = "ORES_CLI_TYPESPEC", default)]
    typespec: String,
    #[serde(rename = "ORES_CLI_JSON_SCHEMA", default)]
    json_schema: String,
    #[serde(
        rename = "ORES_CLI_CONTRACT_REPORT",
        default = "default_contract_report"
    )]
    contract_report: String,
    #[serde(rename = "ORES_CLI_VALIDATOR", default = "default_validator")]
    validator: String,
}

const fn default_true() -> bool {
    true
}

const fn default_repo_limit() -> usize {
    1_000
}

const fn default_max_layout_repos() -> usize {
    50
}

const fn default_max_ciphertext_bytes() -> u64 {
    8 * 1024 * 1024
}

const fn default_codespace_edge_port() -> u16 {
    8080
}

fn default_codespace_edge_state_dir() -> String {
    ".ores/codespace-edge".to_owned()
}

fn default_cloudflared() -> String {
    "cloudflared".to_owned()
}

fn default_log_level() -> String {
    "info".to_owned()
}

fn default_expected_repos() -> String {
    ".github".to_owned()
}

fn default_family_members() -> String {
    "interfaces,clients,sync,cli,monorepo,infra,desktop-app.rs,flutter,lambdas,api-server.rs,admin-api-server.rs,web-server.rs,admin-web-server.rs,docs,mcp-server.rs,admin-mcp-server.rs,lib-core,pub-lib-core,orm-core".to_owned()
}

fn default_visibility() -> String {
    "private".to_owned()
}

fn default_workspace_dir() -> String {
    "~/codes".to_owned()
}

fn default_layout_required() -> String {
    "README.md,LICENSE,AGENTS.md,.github,.zpkg.toml".to_owned()
}

fn default_dot() -> String {
    ".".to_owned()
}

fn default_profile() -> String {
    "baseline".to_owned()
}

fn default_encrypted_env_dir() -> String {
    "env/enc".to_owned()
}

fn default_environment_selection() -> String {
    "dev".to_owned()
}

fn default_sops() -> String {
    "sops".to_owned()
}

fn default_contract_report() -> String {
    ".typespec-json-schema-validator/report.json".to_owned()
}

fn default_validator() -> String {
    "tjsv".to_owned()
}

/// Parse argv with the bundled flags-2-env runtime.
///
/// Environment values are read by flags-2-env itself. No second command-line
/// parser is used.
pub fn parse_invocation(argv: &[String]) -> Result<CliInvocation, RuntimeError> {
    let parser = BundledFlags2Env::new();
    let contract_path = active_flag_contract_path()?;
    let contract_path = contract_path.as_str();
    parser
        .audit_config(Some(contract_path))
        .map_err(|_| RuntimeError::Usage("flag contract audit failed".to_owned()))?;

    let structured = parser
        .parse_structured(argv, Some(contract_path))
        .map_err(|_| {
            RuntimeError::Usage("flag parsing failed: unknown option or invalid value".to_owned())
        })?;

    if !structured.unknown_options.is_empty() {
        return Err(RuntimeError::Usage(format!(
            "unknown options: {} rejected argument(s)",
            structured.unknown_options.len()
        )));
    }
    if !structured.errors.is_empty() {
        return Err(RuntimeError::Usage(format!(
            "flag parsing failed: {} invalid argument(s)",
            structured.errors.len()
        )));
    }

    let resolved = parser
        .resolve_commands(argv, Some(contract_path))
        .map_err(|_| RuntimeError::Usage("command resolution failed".to_owned()))?;

    let values = coerce_values(
        &parser,
        &structured.dotenv,
        &structured.dotenv_overrides,
        &structured.provided_flags,
        contract_path,
    )?;
    let log_level = CliLogLevel::parse(&values.log_level)?;
    let path = normalize_command_path(resolved.path, &structured.command, &structured.subcommands);

    let command = match path.as_slice() {
        [doctor] if doctor == "doctor" => {
            ensure_no_extras(&structured.extras, "doctor")?;
            CliCommand::Doctor
        }
        [codespace, edge, action]
            if codespace == "codespace"
                && edge == "edge"
                && matches!(action.as_str(), "up" | "status" | "down" | "supervise") =>
        {
            ensure_no_extras(&structured.extras, "codespace edge")?;
            let action = match action.as_str() {
                "up" => CodespaceEdgeAction::Up,
                "status" => CodespaceEdgeAction::Status,
                "down" => CodespaceEdgeAction::Down,
                "supervise" => CodespaceEdgeAction::Supervise,
                _ => unreachable!("guard restricts codespace edge action"),
            };
            CliCommand::CodespaceEdge(CodespaceEdgeCommand {
                action,
                options: build_codespace_edge_options(&values)?,
            })
        }
        [orgs] if orgs == "orgs" => {
            ensure_no_extras(&structured.extras, "orgs")?;
            CliCommand::Organizations(Box::new(values.organizations.resolve("list")?))
        }
        [orgs, action] if orgs == "orgs" => {
            ensure_no_extras(&structured.extras, "orgs")?;
            CliCommand::Organizations(Box::new(values.organizations.resolve(action)?))
        }
        [org] if org == "org" => {
            ensure_no_extras(&structured.extras, "org")?;
            CliCommand::Org(build_org_scope(&values)?)
        }
        [org, list] if org == "org" && list == "list-missing-repos" => {
            CliCommand::OrgListMissing(ListMissingRepositoriesOptions {
                scope: build_org_scope(&values)?,
                selected_repositories: normalize_repository_arguments(&structured.extras)?,
                report: values.org_list_report,
            })
        }
        [org, create] if org == "org" && create == "create-missing-repos" => {
            CliCommand::OrgCreateMissing(CreateMissingRepositoriesOptions {
                scope: build_org_scope(&values)?,
                selected_repositories: normalize_repository_arguments(&structured.extras)?,
                all: values.org_create_all,
                visibility: parse_visibility(&values.org_visibility)?,
            })
        }
        [org, set] if org == "org" && set == "set-repo-visibility" => {
            if !values.org_set_visibility_dry_run {
                if !structured.provided_flags.contains_key("ORES_CLI_ORG_NAME") {
                    return Err(RuntimeError::Usage(
                        "`set-repo-visibility` apply requires an explicit --name/--org/--owner argument; environment values cannot select a destructive target organization"
                            .to_owned(),
                    ));
                }
                if values.org_set_visibility_all
                    && !structured
                        .provided_flags
                        .contains_key("ORES_CLI_ORG_SET_VISIBILITY_ALL")
                {
                    return Err(RuntimeError::Usage(
                        "`set-repo-visibility --all` apply requires --all explicitly on argv"
                            .to_owned(),
                    ));
                }
                if !values.org_set_visibility_exclude.trim().is_empty()
                    && !structured
                        .provided_flags
                        .contains_key("ORES_CLI_ORG_SET_VISIBILITY_EXCLUDE")
                {
                    return Err(RuntimeError::Usage(
                        "`set-repo-visibility` apply does not accept --exclude solely from the environment"
                            .to_owned(),
                    ));
                }
                if !structured
                    .provided_flags
                    .contains_key("ORES_CLI_ORG_SET_VISIBILITY")
                {
                    return Err(RuntimeError::Usage(
                        "`set-repo-visibility` apply requires an explicit --visibility argument; environment/default values are not accepted for destructive apply"
                            .to_owned(),
                    ));
                }
                if !structured
                    .provided_flags
                    .contains_key("ORES_CLI_ORG_SET_VISIBILITY_ACCEPT_CONSEQUENCES")
                {
                    return Err(RuntimeError::Usage(
                        "`set-repo-visibility` apply requires explicit --accept-visibility-change-consequences on argv"
                            .to_owned(),
                    ));
                }
            }
            CliCommand::OrgSetRepositoryVisibility(SetRepositoryVisibilityOptions {
                scope: build_org_scope(&values)?,
                selected_repositories: normalize_repository_arguments(&structured.extras)?,
                all: values.org_set_visibility_all,
                excluded_repositories: normalize_repository_arguments(&[
                    values.org_set_visibility_exclude.clone(),
                ])?,
                visibility: parse_visibility(&values.org_set_visibility)?,
                dry_run: values.org_set_visibility_dry_run,
                accept_visibility_change_consequences:
                    values.org_set_visibility_accept_consequences,
            })
        }
        [org, action]
            if org == "org" && matches!(action.as_str(), "clone" | "pull" | "sync") =>
        {
            ensure_no_extras(&structured.extras, "org workspace")?;
            let (workspace_action, all, directory, non_interactive, all_key, non_interactive_key) =
                match action.as_str() {
                    "clone" => (
                        WorkspaceAction::Clone,
                        values.org_clone_all,
                        &values.org_clone_dir,
                        values.org_clone_non_interactive,
                        "ORES_CLI_ORG_CLONE_ALL",
                        "ORES_CLI_ORG_CLONE_NON_INTERACTIVE",
                    ),
                    "pull" => (
                        WorkspaceAction::Pull,
                        values.org_pull_all,
                        &values.org_pull_dir,
                        values.org_pull_non_interactive,
                        "ORES_CLI_ORG_PULL_ALL",
                        "ORES_CLI_ORG_PULL_NON_INTERACTIVE",
                    ),
                    "sync" => (
                        WorkspaceAction::Sync,
                        values.org_sync_all,
                        &values.org_sync_dir,
                        values.org_sync_non_interactive,
                        "ORES_CLI_ORG_SYNC_ALL",
                        "ORES_CLI_ORG_SYNC_NON_INTERACTIVE",
                    ),
                    _ => unreachable!("guard restricts workspace action"),
                };
            if !all || !structured.provided_flags.contains_key(all_key) {
                return Err(RuntimeError::Usage(format!(
                    "`org {action}` requires explicit --all on argv"
                )));
            }
            if non_interactive
                && !structured.provided_flags.contains_key(non_interactive_key)
            {
                return Err(RuntimeError::Usage(format!(
                    "`org {action} --non-interactive` must be supplied explicitly on argv"
                )));
            }
            if non_interactive
                && !structured.provided_flags.contains_key("ORES_CLI_ORG_NAME")
            {
                return Err(RuntimeError::Usage(format!(
                    "`org {action} --non-interactive` requires an explicit --name/--org/--owner argument"
                )));
            }
            if directory.trim().is_empty() {
                return Err(RuntimeError::Usage("--dir must not be empty".to_owned()));
            }
            CliCommand::OrgWorkspace(WorkspaceOptions {
                scope: build_org_scope(&values)?,
                action: workspace_action,
                workspace_root: PathBuf::from(directory.trim()),
                all,
                non_interactive,
            })
        }
        [audit, org] if audit == "audit" && org == "org" => {
            ensure_no_extras(&structured.extras, "audit org")?;
            if values.github_owner.trim().is_empty() {
                return Err(RuntimeError::Usage(
                    "`audit org` requires --org=<github-login>".to_owned(),
                ));
            }
            if values.github_repo_limit == 0 {
                return Err(RuntimeError::Usage(
                    "--repo-limit must be greater than zero".to_owned(),
                ));
            }
            if values.max_layout_repos == 0 {
                return Err(RuntimeError::Usage(
                    "--max-layout-repos must be greater than zero".to_owned(),
                ));
            }

            CliCommand::AuditOrg(GitHubOrgAuditOptions {
                owner: values.github_owner.trim().to_owned(),
                repo_limit: values.github_repo_limit,
                expected_repositories: split_csv(&values.expected_repos),
                family_prefix: non_empty(values.family_prefix),
                family_members: split_csv(&values.family_members),
                require_docs_repository: values.require_docs_repo,
                check_layout: values.check_layout,
                required_root_entries: split_csv(&values.layout_required),
                max_layout_repositories: values.max_layout_repos,
                include_archived: values.include_archived,
            })
        }
        [audit, repo] if audit == "audit" && repo == "repo" => {
            ensure_no_extras(&structured.extras, "audit repo")?;
            CliCommand::AuditRepository(RepositoryAuditOptions {
                path: PathBuf::from(values.repository_path),
                profile: values.repository_profile,
                additional_required_paths: split_csv(&values.required_paths),
            })
        }
        [audit, environment] if audit == "audit" && environment == "env" => {
            ensure_no_extras(&structured.extras, "audit env")?;
            if values.environment_encrypted_dir.trim().is_empty() {
                return Err(RuntimeError::Usage(
                    "--encrypted-dir must not be empty".to_owned(),
                ));
            }
            if values.environment_sops.trim().is_empty() {
                return Err(RuntimeError::Usage(
                    "--sops must name a SOPS executable".to_owned(),
                ));
            }
            if values.environment_max_ciphertext_bytes == 0 {
                return Err(RuntimeError::Usage(
                    "--max-ciphertext-bytes must be greater than zero".to_owned(),
                ));
            }
            CliCommand::AuditEnvironment(EnvironmentAuditOptions {
                path: PathBuf::from(values.environment_path),
                encrypted_dir: PathBuf::from(values.environment_encrypted_dir),
                environments: parse_environment_selection(&values.environment_environments)?,
                sops: values.environment_sops,
                max_ciphertext_bytes: values.environment_max_ciphertext_bytes,
                require_non_empty: values.environment_require_non_empty,
            })
        }
        [audit, package] if audit == "audit" && package == "package" => {
            ensure_no_extras(&structured.extras, "audit package")?;
            CliCommand::AuditPackage(PackageAuditOptions {
                path: PathBuf::from(values.package_path),
                require_cargo_lock: values.require_cargo_lock,
            })
        }
        [audit, contract] if audit == "audit" && contract == "contract" => {
            ensure_no_extras(&structured.extras, "audit contract")?;
            if values.typespec.trim().is_empty() || values.json_schema.trim().is_empty() {
                return Err(RuntimeError::Usage(
                    "`audit contract` requires --typespec and --schema".to_owned(),
                ));
            }
            if values.validator.trim().is_empty() || values.validator.contains(char::is_whitespace)
            {
                return Err(RuntimeError::Usage(
                    "--validator must be one executable name or path, not a shell fragment"
                        .to_owned(),
                ));
            }
            CliCommand::AuditContract(ContractAuditOptions {
                typespec: PathBuf::from(values.typespec),
                schema: PathBuf::from(values.json_schema),
                report: PathBuf::from(values.contract_report),
                validator: values.validator,
            })
        }
        _ => {
            return Err(RuntimeError::Usage(
                "unknown command: expected doctor, codespace edge up/status/down, org, org list-missing-repos, org create-missing-repos, org set-repo-visibility, org clone/pull/sync, audit org, audit repo, audit env, audit package, or audit contract".to_owned(),
            ));
        }
    };

    Ok(CliInvocation {
        json: values.json,
        log_level,
        command,
    })
}

fn coerce_values(
    parser: &BundledFlags2Env,
    dotenv: &HashMap<String, String>,
    dotenv_overrides: &HashMap<String, String>,
    provided_flags: &HashMap<String, String>,
    contract_path: &str,
) -> Result<ResolvedValues, RuntimeError> {
    // flags-2-env exposes each precedence layer independently. Preserve its
    // documented order instead of merging the fully resolved/defaulted map over
    // the process environment: dotenv < environment < dotenv_override < argv.
    let mut values = dotenv.clone();
    values.extend(env::vars());
    values.extend(dotenv_overrides.clone());
    values.extend(provided_flags.clone());
    parser
        .coerce(&values, Some(contract_path))
        .map_err(|_| RuntimeError::Usage("invalid typed flag or environment value".to_owned()))
}

fn normalize_command_path(
    mut path: Vec<String>,
    command: &str,
    subcommands: &[String],
) -> Vec<String> {
    if path.is_empty() {
        if !command.trim().is_empty() {
            path.push(command.trim().to_owned());
        }
        path.extend(
            subcommands
                .iter()
                .map(|value| value.trim())
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned),
        );
    }
    path
}

fn build_codespace_edge_options(
    values: &ResolvedValues,
) -> Result<CodespaceEdgeOptions, RuntimeError> {
    if values.codespace_edge_port == 0 {
        return Err(RuntimeError::Usage(
            "--port must be between 1 and 65535".to_owned(),
        ));
    }
    if values.codespace_edge_state_dir.trim().is_empty() {
        return Err(RuntimeError::Usage(
            "--state-dir must not be empty".to_owned(),
        ));
    }
    if values.codespace_edge_cloudflared.trim().is_empty() {
        return Err(RuntimeError::Usage(
            "--cloudflared must name an executable or path".to_owned(),
        ));
    }
    Ok(CodespaceEdgeOptions {
        port: values.codespace_edge_port,
        state_dir: PathBuf::from(values.codespace_edge_state_dir.trim()),
        cloudflared: values.codespace_edge_cloudflared.trim().to_owned(),
    })
}

fn build_org_scope(values: &ResolvedValues) -> Result<OrgRepositoryScope, RuntimeError> {
    let owner = validate_org_login(&values.org_name)?;
    if values.org_repo_limit == 0 {
        return Err(RuntimeError::Usage(
            "--repo-limit must be greater than zero".to_owned(),
        ));
    }

    let family_prefix = non_empty(values.org_family_prefix.clone());
    let family_members = if values.org_standard_family {
        split_csv(&values.org_family_members)
    } else {
        Vec::new()
    };
    Ok(OrgRepositoryScope {
        owner,
        repo_limit: values.org_repo_limit,
        expected_repositories: split_csv(&values.org_expected_repos),
        family_prefix,
        family_members,
    })
}

fn parse_environment_selection(value: &str) -> Result<Vec<String>, RuntimeError> {
    let values = split_csv(value);
    if values.is_empty() || values == ["all"] {
        return Ok(Vec::new());
    }
    if values.iter().any(|value| value == "all")
        || values
            .iter()
            .any(|value| !matches!(value.as_str(), "dev" | "stage" | "prod"))
    {
        return Err(RuntimeError::Usage(
            "--environments must be `all` or a comma-separated subset of dev, stage, prod"
                .to_owned(),
        ));
    }
    Ok(values)
}

fn parse_visibility(value: &str) -> Result<RepositoryVisibility, RuntimeError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "private" => Ok(RepositoryVisibility::Private),
        "internal" => Ok(RepositoryVisibility::Internal),
        "public" => Ok(RepositoryVisibility::Public),
        _ => Err(RuntimeError::Usage(
            "--visibility must be private, internal, or public".to_owned(),
        )),
    }
}

fn ensure_no_extras(values: &[String], command: &str) -> Result<(), RuntimeError> {
    if values.is_empty() {
        Ok(())
    } else {
        Err(RuntimeError::Usage(format!(
            "`{command}` does not accept positional arguments (received {})",
            values.len()
        )))
    }
}

fn split_csv(value: &str) -> Vec<String> {
    let mut values = value
        .split(',')
        .map(str::trim)
        .filter(|item| !item.is_empty())
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    values.sort();
    values.dedup();
    values
}

fn non_empty(value: String) -> Option<String> {
    let value = value.trim().to_owned();
    (!value.is_empty()).then_some(value)
}

#[cfg(test)]
mod tests {
    use std::ffi::OsString;

    use super::{
        PathBuf, SOURCE_FLAG_CONTRACT_PATH, ensure_no_extras, non_empty,
        parse_environment_selection, parse_visibility, resolve_flag_contract_path, split_csv,
        validate_flag_contract_path,
    };
    use crate::org::{RepositoryVisibility, validate_org_login};

    #[test]
    fn csv_values_are_trimmed_sorted_and_deduplicated() {
        assert_eq!(
            split_csv(" zed-docs, .github,zed-docs ,,"),
            vec![".github".to_owned(), "zed-docs".to_owned()]
        );
    }

    #[test]
    fn environment_selection_is_canonical_and_fail_closed() {
        assert_eq!(
            parse_environment_selection("all").expect("all"),
            Vec::<String>::new()
        );
        assert_eq!(
            parse_environment_selection("prod, dev,dev").expect("selected environments"),
            vec!["dev".to_owned(), "prod".to_owned()]
        );
        assert!(parse_environment_selection("dev,all").is_err());
        assert!(parse_environment_selection("qa").is_err());
        assert!(parse_environment_selection("../prod").is_err());
    }

    #[test]
    fn empty_optional_strings_become_none() {
        assert_eq!(non_empty("  ".to_owned()), None);
        assert_eq!(non_empty("zed".to_owned()), Some("zed".to_owned()));
    }

    #[test]
    fn organization_names_reject_api_path_injection() {
        assert_eq!(
            validate_org_login("example-org").expect("valid organization"),
            "example-org"
        );
        assert!(validate_org_login("example/org").is_err());
    }

    #[test]
    fn repository_visibility_is_closed_over_supported_values() {
        assert_eq!(
            parse_visibility("private").expect("private visibility"),
            RepositoryVisibility::Private
        );
        assert!(parse_visibility("secret").is_err());
    }

    #[test]
    fn semantic_usage_errors_do_not_reflect_supplied_values() {
        let secret = "ghp_secret_value";
        let visibility_error = parse_visibility(secret)
            .expect_err("invalid visibility")
            .to_string();
        assert!(!visibility_error.contains(secret));

        let environment_error = parse_environment_selection(secret)
            .expect_err("invalid environment selection")
            .to_string();
        assert!(!environment_error.contains(secret));

        let positional_error = ensure_no_extras(&[secret.to_owned()], "doctor")
            .expect_err("unexpected positional")
            .to_string();
        assert!(!positional_error.contains(secret));
        assert!(positional_error.contains("received 1"));
    }

    #[test]
    fn package_contract_resolution_never_uses_the_current_working_directory() {
        assert_eq!(
            resolve_flag_contract_path(None).expect("package source contract"),
            SOURCE_FLAG_CONTRACT_PATH
        );
        let package_owned = PathBuf::from(SOURCE_FLAG_CONTRACT_PATH);
        let package_owned = package_owned
            .to_str()
            .expect("package source path must be UTF-8")
            .to_owned();
        assert_eq!(
            resolve_flag_contract_path(Some(OsString::from(&package_owned)))
                .expect("absolute package contract"),
            package_owned
        );
        assert!(resolve_flag_contract_path(Some(OsString::from(".cli-flags.toml"))).is_err());
        assert!(
            resolve_flag_contract_path(Some(OsString::from("../owned/.cli-flags.toml"))).is_err()
        );
        assert!(resolve_flag_contract_path(Some(OsString::from("  "))).is_err());
    }

    #[test]
    fn missing_package_contract_fails_with_exit_one() {
        let missing = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join(".definitely-missing-ores-cli-flags-contract.toml")
            .display()
            .to_string();
        let error = validate_flag_contract_path(missing).expect_err("missing contract");
        assert_eq!(error.exit_code(), 1);
        assert!(error.to_string().contains(".cli-flags.toml"));
    }
}
