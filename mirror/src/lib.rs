#![forbid(unsafe_code)]

//! Reusable SDK behind the `ores-cli` executable.
//!
//! The library owns command dispatch, audit models, GitHub CLI integration, and
//! stdout rendering. `src/main.rs` contains only process exit/error handling.

/// Audit implementations and option models.
pub mod audit;
/// Typed ChatGPT/OpenAI thread identity and batch-selection contracts.
pub mod chatgpt;
/// GitHub Codespaces loopback preview and Cloudflare Tunnel lifecycle.
pub mod codespace;
/// Bounded ECMA-D protocol client boundary.
pub mod ecmad;
/// Process-level runtime and usage errors.
pub mod error;
/// flags-2-env parsing and typed command resolution.
pub mod flags;
/// Authenticated GitHub CLI subprocess gateway.
pub mod github;
/// Process-level log threshold and streaming controls.
pub mod log_control;
/// Bounded stdio MCP client primitives.
pub mod mcp;
/// flags-2-env adapter for the MCP command subtree.
pub mod mcp_cli;
/// Stable findings and command-report models.
pub mod model;
/// GitHub organization inspection and repository-management commands.
pub mod org;
/// Multi-organization discovery, interactive selection, and streamed reconciliation.
pub mod orgs;
/// JSON, plain, and shell-composable stdout renderers.
pub mod output;
/// Read-only repository standards and offline CLI-name admission planning.
pub mod standards;

mod process;
mod python_systems;

use std::time::Duration;

use next_loggers::SCHEMA as ORES_OTEL_SCHEMA;
use serde_json::json;
use tokio::process::Command;

use crate::audit::{
    ContractAuditOptions, ContractTreeAuditOptions, RepositoryAuditOptions, audit_contract,
    audit_contract_tree, audit_environment, audit_github_org, audit_package, audit_repository,
    audit_service_configs, contract_pairs, evidence_report_path,
};
use crate::error::RuntimeError;
use crate::flags::{CliCommand, CliInvocation, active_flag_contract_path, parse_invocation};
use crate::github::GhCli;
use crate::model::{CommandReport, Finding};
use crate::org::{
    CreateMissingRepositoriesOptions, RepositoryCreationEvent, RepositoryCreationPhase,
    create_missing_repositories, create_missing_repositories_with_progress, inspect_github_org,
    list_missing_repositories, manage_workspace, set_repository_visibility,
};
use crate::orgs::{ProgressEvent, emit_progress_to};
use crate::output::{emit_report, emit_repository_names};
use crate::process::{CaptureLimits, run_bounded};

/// Parse the current process, execute one command, and emit its report.
pub async fn run_process() -> Result<u8, RuntimeError> {
    let argv = std::env::args().collect::<Vec<_>>();
    let invocation = parse_invocation(&argv)?;
    invocation.log_level.install();
    let json_output = invocation.json;
    let result = run_invocation(invocation).await;
    if let Err(error) = &result
        && let Some(report) = error.partial_report()
    {
        // Receipts are command evidence, not optional progress.
        emit_report(report, json_output)?;
    }
    result
}

async fn run_invocation(invocation: CliInvocation) -> Result<u8, RuntimeError> {
    if let CliCommand::Organizations(options) = &invocation.command {
        return orgs::run_process(options, invocation.json).await;
    }
    if let CliCommand::OrgCreateMissing(options) = &invocation.command {
        return run_org_create_process(options, invocation.json).await;
    }
    let repository_name_output = invocation.command.emits_repository_names();
    let json_output = invocation.json;
    let report = execute(invocation).await?;
    let exit_code = report.exit_code();
    if repository_name_output {
        emit_repository_names(&report)?;
    } else {
        emit_report(&report, json_output)?;
    }
    Ok(exit_code)
}

async fn run_org_create_process(
    options: &CreateMissingRepositoriesOptions,
    json_output: bool,
) -> Result<u8, RuntimeError> {
    let gh = GhCli::new();
    let prefix = options
        .scope
        .family_prefix
        .as_deref()
        .unwrap_or(&options.scope.owner);
    emit_progress_to(
        &mut std::io::stdout().lock(),
        &ProgressEvent {
            phase: "preflight",
            organization: Some(options.scope.owner.clone()),
            message: format!(
                "preflight org {}: authenticate + inventory; prefix={prefix}; visibility={}",
                options.scope.owner,
                options.visibility.as_str()
            ),
            details: json!({
                "organization": &options.scope.owner,
                "familyPrefix": prefix,
                "visibility": options.visibility.as_str(),
                "selection": if options.all { "all-missing" } else { "explicit" },
                "repoLimit": options.scope.repo_limit,
            }),
        },
        json_output,
    )?;

    let mut observer = |event: &RepositoryCreationEvent| {
        let (phase, verb) = match event.phase {
            RepositoryCreationPhase::Creating => ("working", "creating"),
            RepositoryCreationPhase::Created => ("done", "created"),
            RepositoryCreationPhase::AlreadyPresent => ("done", "already present"),
            RepositoryCreationPhase::Failed => ("failed", "failed"),
        };
        let target = format!("{}/{}", event.owner, event.repository);
        emit_progress_to(
            &mut std::io::stdout().lock(),
            &ProgressEvent {
                phase,
                organization: Some(event.owner.clone()),
                message: format!(
                    "{verb} repository {}/{}: {target}",
                    event.index, event.total
                ),
                details: json!({
                    "repository": target,
                    "index": event.index,
                    "total": event.total,
                    "exitCode": event.exit_code,
                }),
            },
            json_output,
        )
    };

    let report = create_missing_repositories_with_progress(&gh, options, &mut observer).await?;
    let exit_code = report.exit_code();
    let created = report
        .metadata
        .get("createdRepositories")
        .and_then(serde_json::Value::as_array)
        .map_or(0, |values| values.len());
    let already_present = report
        .metadata
        .get("alreadyPresentRepositories")
        .and_then(serde_json::Value::as_array)
        .map_or(0, |values| values.len());
    let unconfirmed = report
        .metadata
        .get("unconfirmedRepositories")
        .and_then(serde_json::Value::as_array)
        .map_or(0, |values| values.len());
    emit_progress_to(
        &mut std::io::stdout().lock(),
        &ProgressEvent {
            phase: if unconfirmed == 0 { "done" } else { "failed" },
            organization: Some(options.scope.owner.clone()),
            message: format!(
                "completed org {}: created={created} already_present={already_present} unconfirmed={unconfirmed}",
                options.scope.owner
            ),
            details: json!({
                "created": created,
                "alreadyPresent": already_present,
                "unconfirmed": unconfirmed,
            }),
        },
        json_output,
    )?;
    emit_report(&report, json_output)?;
    Ok(exit_code)
}

/// Execute a resolved invocation without writing output.
///
/// SDK consumers can inspect or render the returned report themselves.
pub async fn execute(invocation: CliInvocation) -> Result<CommandReport, RuntimeError> {
    match invocation.command {
        CliCommand::Doctor => doctor().await,
        CliCommand::CodespaceEdge(command) => codespace::execute(&command),
        CliCommand::Organizations(options) => orgs::execute(&options).await,
        CliCommand::Org(scope) => {
            let gh = GhCli::new();
            inspect_github_org(&gh, &scope).await
        }
        CliCommand::OrgListMissing(options) => {
            let gh = GhCli::new();
            list_missing_repositories(&gh, &options).await
        }
        CliCommand::OrgCreateMissing(options) => {
            let gh = GhCli::new();
            create_missing_repositories(&gh, &options).await
        }
        CliCommand::OrgSetRepositoryVisibility(options) => {
            let gh = GhCli::new();
            set_repository_visibility(&gh, &options).await
        }
        CliCommand::OrgWorkspace(options) => {
            let gh = GhCli::new();
            manage_workspace(&gh, &options).await
        }
        CliCommand::AuditOrg(options) => {
            let gh = GhCli::new();
            audit_github_org(&gh, &options).await
        }
        CliCommand::AuditRepository(options) if options.profile == "infra" => {
            let root = options.path.clone();
            let report = audit_infra_repository(&options).await?;
            Ok(python_systems::audit(&root, report))
        }
        CliCommand::AuditRepository(options) if options.profile == "interfaces" => {
            let root = options.path.clone();
            let report = audit_interfaces_repository(&options).await?;
            Ok(python_systems::audit(&root, report))
        }
        CliCommand::AuditRepository(options) if options.profile == "family" => {
            let root = options.path.clone();
            let report = audit_family_repository(&options).await?;
            Ok(python_systems::audit(&root, report))
        }
        CliCommand::AuditRepository(options)
            if matches!(options.profile.as_str(), "standards" | "standards-logs") =>
        {
            let root = options.path.clone();
            let request = standards::StandardsOptions {
                path: options.path,
                scan_logs: options.profile == "standards-logs",
                additional_required_paths: options.additional_required_paths,
            };
            let mut report = standards::audit_standards(&request).await;
            merge_service_config_report(&mut report, audit_service_configs(&root));
            Ok(python_systems::audit(&root, report.finalize()))
        }
        CliCommand::AuditRepository(options) => {
            let root = options.path.clone();
            let mut report = audit_repository(&options);
            merge_service_config_report(&mut report, audit_service_configs(&root));
            Ok(python_systems::audit(&root, report.finalize()))
        }
        CliCommand::AuditEnvironment(options) => audit_environment(&options).await,
        CliCommand::AuditPackage(options) => Ok(audit_package(&options)),
        CliCommand::AuditContract(options) => audit_contract(&options).await,
    }
}

fn merge_service_config_report(report: &mut CommandReport, config_report: CommandReport) {
    let config_has_issues = config_report.issue_count() != 0;
    let CommandReport {
        findings, metadata, ..
    } = config_report;
    if config_has_issues {
        report
            .findings
            .retain(|finding| finding.code != "repository-structure-ready");
    }
    report.insert_metadata("serviceConfigAudit", json!(metadata));
    for finding in findings {
        report.push(finding);
    }
}

async fn audit_interfaces_repository(
    options: &RepositoryAuditOptions,
) -> Result<CommandReport, RuntimeError> {
    let mut report = audit_repository(options);
    merge_service_config_report(&mut report, audit_service_configs(&options.path));
    let tree_options = ContractTreeAuditOptions {
        path: options.path.clone(),
        report_root: options
            .path
            .join(".typespec-json-schema-validator/contract-tree"),
        validator: "tjsv".to_owned(),
    };
    let parity = audit_contract_tree(&tree_options).await?;
    report.findings.extend(parity.findings);
    report.insert_metadata("interfacesTjsv", json!(parity.metadata));
    Ok(report.finalize())
}

async fn audit_infra_repository(
    options: &RepositoryAuditOptions,
) -> Result<CommandReport, RuntimeError> {
    let mut report = audit_repository(options);
    merge_service_config_report(&mut report, audit_service_configs(&options.path));
    let contract_root = options.path.join("contracts/infra-gitops");
    let contract_options = ContractAuditOptions {
        typespec: contract_root.join("main.tsp"),
        schema: contract_root.join("authored.schema.json"),
        report: options
            .path
            .join(".typespec-json-schema-validator/infra-gitops/report.json"),
        validator: "tjsv".to_owned(),
    };

    // The infra profile intentionally makes TJSV a required runtime dependency.
    // Structural findings and parity findings are independent evidence, so run
    // both lanes rather than allowing one failure class to mask the other.
    let parity = audit_contract(&contract_options).await?;
    report.findings.extend(parity.findings);
    report.insert_metadata("infraTjsv", json!(parity.metadata));
    report.push(
        Finding::info(
            "infra-tjsv-executed",
            "infra profile executed canonical tjsv against the independent provider contract authorities",
        )
        .with_target("contracts/infra-gitops"),
    );

    Ok(report.finalize())
}

/// `audit repo --profile family`: structural family expectations plus a live
/// `tjsv` execution over every discovered contract pair. This is the
/// cross-language contract gate: the independently authored TypeSpec and JSON
/// Schema authorities must agree (and their consumer projection must verify)
/// before a family repository is admitted.
async fn audit_family_repository(
    options: &RepositoryAuditOptions,
) -> Result<CommandReport, RuntimeError> {
    let mut report = audit_repository(options);
    merge_service_config_report(&mut report, audit_service_configs(&options.path));

    let pairs = contract_pairs(&options.path);
    let mut executed = Vec::with_capacity(pairs.len());
    for pair in &pairs {
        let directory = options.path.join(&pair.directory);
        let contract_options = ContractAuditOptions {
            typespec: directory.join("main.tsp"),
            schema: directory.join("authored.schema.json"),
            report: evidence_report_path(&options.path, pair),
            validator: "tjsv".to_owned(),
        };
        // Like the infra profile, the family profile makes TJSV a required
        // runtime dependency: structural and parity findings are independent
        // evidence, so both lanes run for every pair.
        let parity = audit_contract(&contract_options).await?;
        let passed = parity.issue_count() == 0;
        report.findings.extend(parity.findings);
        report.insert_metadata(
            format!("familyTjsv.{}", pair.directory),
            json!(parity.metadata),
        );
        executed.push(json!({
            "contract": pair.directory,
            "report": pair.report,
            "passed": passed,
        }));
    }
    report.insert_metadata("familyTjsvExecutions", json!(executed));
    if !pairs.is_empty() {
        report.push(
            Finding::info(
                "family-tjsv-executed",
                format!(
                    "family profile executed canonical tjsv against {} independent contract pair(s)",
                    pairs.len()
                ),
            )
            .with_target("contracts"),
        );
    }

    Ok(report.finalize())
}

async fn doctor() -> Result<CommandReport, RuntimeError> {
    let mut report = CommandReport::new("doctor");
    let descriptor = ores_middleware::descriptor();
    let flag_contract = active_flag_contract_path()?;

    report.insert_metadata("flagContract", json!(&flag_contract));
    report.insert_metadata("otelSchema", json!(ORES_OTEL_SCHEMA));
    report.insert_metadata("middlewarePackage", json!(descriptor.package_name));
    report.insert_metadata(
        "middlewareContractVersion",
        json!(descriptor.contract_version),
    );
    report.insert_metadata("middlewareCapabilities", json!(descriptor.capabilities));
    report.insert_metadata(
        "rateLimitAuthority",
        json!("ores-rate-limit/ores-rl-lib-core via zed-pkg"),
    );

    report.push(Finding::info(
        "flag-contract-valid",
        format!("flags-2-env audited `{flag_contract}` before command dispatch"),
    ));
    report.push(Finding::info(
        "ores-otel-ready",
        format!("structured stdout uses {ORES_OTEL_SCHEMA}"),
    ));
    report.push(Finding::info(
        "ores-middleware-ready",
        "GitHub subprocess requests use the ores-middleware bounded token bucket",
    ));
    report.push(Finding::info(
        "rate-limit-package-tracked",
        "the canonical rate-limit core is declared in .zpkg.toml",
    ));

    record_program(&mut report, "gh", &["--version"], true, "version", true).await;
    record_program(
        &mut report,
        "tjsv",
        &["doctor", "--quiet"],
        false,
        "doctor",
        false,
    )
    .await;
    record_program(&mut report, "sops", &["--version"], false, "version", true).await;

    Ok(report.finalize())
}

async fn record_program(
    report: &mut CommandReport,
    program: &str,
    args: &[&str],
    required: bool,
    probe_name: &str,
    capture_version: bool,
) {
    let mut command = Command::new(program);
    command.args(args);
    let result = run_bounded(
        command,
        format!("{program} {probe_name} probe"),
        CaptureLimits::new(Duration::from_secs(10), 64 * 1024, 64 * 1024),
    )
    .await;

    match result {
        Ok(output) if output.status.success() => {
            let mut finding = Finding::info(
                format!("{program}-available"),
                format!("`{program}` is available"),
            )
            .with_target(program.to_owned());
            if capture_version {
                let version = output.stdout.lines().next().unwrap_or_default().trim();
                finding = finding.with_detail("version", json!(version));
            }
            report.push(finding);
        }
        Ok(output) => {
            let diagnostic = if output.stderr.trim().is_empty() {
                output.stdout.trim()
            } else {
                output.stderr.trim()
            };
            let code = format!("{program}-unavailable");
            let message = format!(
                "`{program}` returned exit code {:?}: {}",
                output.status.code(),
                truncate(diagnostic)
            );
            let finding = if required {
                Finding::warning(code, message)
            } else {
                Finding::info(code, message)
            };
            report.push(
                finding
                    .with_target(program.to_owned())
                    .with_detail("requiredForDefaultAudit", json!(required)),
            );
        }
        Err(error) => {
            let code = format!("{program}-unavailable");
            let message = format!("`{program}` probe failed: {error}");
            let finding = if required {
                Finding::warning(code, message)
            } else {
                Finding::info(code, message)
            };
            report.push(
                finding
                    .with_target(program.to_owned())
                    .with_detail("requiredForDefaultAudit", json!(required)),
            );
        }
    }
}

fn truncate(value: &str) -> String {
    const LIMIT: usize = 1_024;
    if value.len() <= LIMIT {
        return value.to_owned();
    }
    let mut boundary = LIMIT;
    while !value.is_char_boundary(boundary) {
        boundary -= 1;
    }
    format!("{}…", &value[..boundary])
}
