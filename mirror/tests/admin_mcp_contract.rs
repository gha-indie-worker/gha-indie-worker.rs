//! The standard repository catalog must retain both separately permissioned MCP servers.
//! These are native parser/declaration regressions, not deployed authorization tests.

use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use flags2env::BundledFlags2Env;
use serde_json::Value;

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).to_path_buf()
}

fn contract() -> toml::Value {
    toml::from_str(&fs::read_to_string(root().join(".cli-flags.toml")).unwrap()).unwrap()
}

fn declarations(node: &toml::Value, found: &mut Vec<(String, String)>) {
    if let Some(table) = node.as_table() {
        if let Some(flag) = table
            .get("flags")
            .and_then(|flags| flags.get("family-members"))
        {
            found.push((
                flag["env"].as_str().unwrap().to_owned(),
                flag["default"].as_str().unwrap().to_owned(),
            ));
        }
        if let Some(commands) = table.get("commands").and_then(toml::Value::as_table) {
            for child in commands.values() {
                declarations(child, found);
            }
        }
    }
}

fn families() -> Vec<(String, String)> {
    let mut result = Vec::new();
    declarations(&contract(), &mut result);
    result
}

fn members(value: &str) -> BTreeSet<&str> {
    value.split(',').map(str::trim).collect()
}

static PARSER_LOCK: Mutex<()> = Mutex::new(());

fn parse(args: &[&str]) -> (flags2env::StructuredParse, Value) {
    let _guard = PARSER_LOCK.lock().unwrap();
    let parser = BundledFlags2Env::new();
    let path = root().join(".cli-flags.toml");
    let path = path.to_str().unwrap();
    parser.audit_config(Some(path)).unwrap();
    let args = args.iter().map(|arg| (*arg).to_owned()).collect::<Vec<_>>();
    let parsed = parser.parse_structured(&args, Some(path)).unwrap();
    assert!(parsed.errors.is_empty(), "unexpected parser error");
    assert!(parsed.unknown_options.is_empty(), "unexpected unknown flag");
    let mut values = HashMap::<String, String>::new();
    values.extend(parsed.dotenv.clone());
    values.extend(parsed.dotenv_overrides.clone());
    values.extend(parsed.provided_flags.clone());
    let coerced = parser.coerce(&values, Some(path)).unwrap();
    (parsed, coerced)
}

#[test]
fn every_standard_family_requires_exactly_one_regular_and_admin_mcp() {
    let families = families();
    assert!(
        families.len() >= 2,
        "org and audit org must declare their family"
    );
    for (key, value) in &families {
        let set = members(value);
        assert!(set.contains("mcp-server.rs"), "{key}: missing regular MCP");
        assert!(
            set.contains("admin-mcp-server.rs"),
            "{key}: missing admin MCP"
        );
        assert!(!set.contains(""), "{key}: empty suffix");
        assert_eq!(
            set.len(),
            value.split(',').count(),
            "{key}: duplicate suffix"
        );
    }
    let first = members(&families[0].1);
    assert!(families.iter().all(|(_, value)| members(value) == first));
}

#[test]
fn serde_fallback_cannot_drift_from_the_authoritative_contract() {
    let expected = families().into_iter().next().unwrap().1;
    let source = fs::read_to_string(root().join("src/flags.rs")).unwrap();
    assert!(source.contains(&format!("\"{expected}\".to_owned()")));
    // When the separate plural-org command is integrated, its SDK fallback must
    // be updated too. Do not silently ignore it once its command is declared.
    if contract()["commands"].get("orgs").is_some() {
        let source = fs::read_to_string(root().join("src/orgs/flags.rs")).unwrap();
        assert!(source.contains(&format!("\"{expected}\".to_owned()")));
    }
}

#[test]
fn native_parser_supplies_the_pair_for_listing_and_explicit_creation() {
    for binary in ["oresc", "ores-cli"] {
        for action in ["list-missing-repos", "create-missing-repos"] {
            let mut args = vec![binary, "org", "--name=example-org", action];
            if action == "create-missing-repos" {
                args.push("--all");
            }
            let (parsed, values) = parse(&args);
            assert_eq!(parsed.command, format!("org {action}"));
            assert_eq!(parsed.subcommands, vec!["org", action]);
            assert_eq!(values["ORES_CLI_ORG_NAME"], "example-org");
            assert_eq!(values["ORES_CLI_ORG_STANDARD_FAMILY"], true);
            if action == "create-missing-repos" {
                assert_eq!(values["ORES_CLI_ORG_VISIBILITY"], "private");
                assert_eq!(values["ORES_CLI_ORG_CREATE_ALL"], true);
            }
            let set = members(values["ORES_CLI_ORG_FAMILY_MEMBERS"].as_str().unwrap());
            assert!(set.contains("mcp-server.rs"));
            assert!(set.contains("admin-mcp-server.rs"));
        }
    }
}

#[test]
fn native_parser_supplies_visibility_mutation_contract() {
    let (parsed, values) = parse(&[
        "oresc",
        "org",
        "--name=example-org",
        "set-repo-visibility",
        "--all",
        "--visibility=private",
        "--exclude=example-org.github.io",
        "--dry-run",
    ]);
    assert_eq!(parsed.command, "org set-repo-visibility");
    assert_eq!(parsed.subcommands, vec!["org", "set-repo-visibility"]);
    assert_eq!(values["ORES_CLI_ORG_NAME"], "example-org");
    assert_eq!(values["ORES_CLI_ORG_SET_VISIBILITY_ALL"], true);
    assert_eq!(values["ORES_CLI_ORG_SET_VISIBILITY"], "private");
    assert_eq!(
        values["ORES_CLI_ORG_SET_VISIBILITY_EXCLUDE"],
        "example-org.github.io"
    );
    assert_eq!(values["ORES_CLI_ORG_SET_VISIBILITY_DRY_RUN"], true);
    assert_eq!(
        values["ORES_CLI_ORG_SET_VISIBILITY_ACCEPT_CONSEQUENCES"],
        false
    );

    let (_, values) = parse(&[
        "ores-cli",
        "org",
        "--name=example-org",
        "set-repo-visibility",
        "--all",
        "--visibility=private",
        "--accept-visibility-change-consequences",
    ]);
    assert_eq!(
        values["ORES_CLI_ORG_SET_VISIBILITY_ACCEPT_CONSEQUENCES"],
        true
    );
}

#[test]
fn native_parser_supplies_workspace_sync_contract() {
    for (action, all_key, dir_key, non_interactive_key) in [
        (
            "clone",
            "ORES_CLI_ORG_CLONE_ALL",
            "ORES_CLI_ORG_CLONE_DIR",
            "ORES_CLI_ORG_CLONE_NON_INTERACTIVE",
        ),
        (
            "pull",
            "ORES_CLI_ORG_PULL_ALL",
            "ORES_CLI_ORG_PULL_DIR",
            "ORES_CLI_ORG_PULL_NON_INTERACTIVE",
        ),
        (
            "sync",
            "ORES_CLI_ORG_SYNC_ALL",
            "ORES_CLI_ORG_SYNC_DIR",
            "ORES_CLI_ORG_SYNC_NON_INTERACTIVE",
        ),
    ] {
        let (parsed, values) = parse(&[
            "oresc",
            "org",
            "--name=example-org",
            action,
            "--all",
            "--dir=/tmp/codes",
            "--non-interactive",
        ]);
        assert_eq!(parsed.command, format!("org {action}"));
        assert_eq!(parsed.subcommands, vec!["org", action]);
        assert_eq!(values["ORES_CLI_ORG_NAME"], "example-org");
        assert_eq!(values[all_key], true);
        assert_eq!(values[dir_key], "/tmp/codes");
        assert_eq!(values[non_interactive_key], true);
    }
}

#[test]
fn native_audit_parser_uses_the_same_pair_and_explicit_prefix() {
    let (_, values) = parse(&[
        "oresc",
        "audit",
        "org",
        "--org=hhaus-org",
        "--family-prefix=hhaus",
    ]);
    assert_eq!(values["ORES_CLI_FAMILY_PREFIX"], "hhaus");
    let set = members(values["ORES_CLI_FAMILY_MEMBERS"].as_str().unwrap());
    assert!(set.contains("mcp-server.rs"));
    assert!(set.contains("admin-mcp-server.rs"));
}

#[test]
fn focused_test_orgs_can_still_disable_the_standard_family() {
    let (_, values) = parse(&[
        "oresc",
        "org",
        "--name=example-test",
        "--no-standard-family",
        "--expected-repos=.github,security-boundary-tests",
        "list-missing-repos",
    ]);
    assert_eq!(values["ORES_CLI_ORG_STANDARD_FAMILY"], false);
    assert_eq!(
        values["ORES_CLI_ORG_EXPECTED_REPOS"],
        ".github,security-boundary-tests"
    );
}

#[test]
fn explicit_mcp_pair_selection_and_prefix_survive_native_parsing() {
    let (_, values) = parse(&[
        "oresc",
        "org",
        "--name=hhaus-org",
        "--family-prefix=hhaus",
        "--family-members=mcp-server.rs,admin-mcp-server.rs",
        "list-missing-repos",
    ]);
    assert_eq!(values["ORES_CLI_ORG_FAMILY_PREFIX"], "hhaus");
    assert_eq!(
        values["ORES_CLI_ORG_FAMILY_MEMBERS"],
        "mcp-server.rs,admin-mcp-server.rs"
    );
}

#[test]
fn admin_boundary_declaration_requires_production_db_vpc_and_verified_super_admins() {
    let policy: toml::Value = toml::from_str(
        &fs::read_to_string(root().join("governance/mcp-server-boundaries.toml")).unwrap(),
    )
    .unwrap();
    assert_eq!(policy["schema"].as_integer(), Some(1));
    assert_eq!(
        policy["evidence_kind"].as_str(),
        Some("required-policy-not-deployment-evidence")
    );
    let admin = &policy["admin"];
    assert_eq!(
        admin["repository_suffix"].as_str(),
        Some("admin-mcp-server.rs")
    );
    assert_eq!(admin["vpc_role"].as_str(), Some("production-database"));
    assert_eq!(admin["public_ingress"].as_bool(), Some(false));
    assert_eq!(admin["other_vpc_ingress"].as_bool(), Some(false));
    assert_eq!(admin["required_role"].as_str(), Some("super-admin"));
    assert_eq!(admin["authorize_every_request"].as_bool(), Some(true));
    assert_eq!(admin["require_verified_identity"].as_bool(), Some(true));
    assert_eq!(admin["require_org_scope"].as_bool(), Some(true));
    assert_eq!(admin["require_admin_audience"].as_bool(), Some(true));
    assert_eq!(
        admin["require_per_tool_authorization"].as_bool(),
        Some(true)
    );
}

#[test]
fn regular_boundary_forbids_destructive_tools_dispatch_and_delegation() {
    let policy: toml::Value = toml::from_str(
        &fs::read_to_string(root().join("governance/mcp-server-boundaries.toml")).unwrap(),
    )
    .unwrap();
    let regular = &policy["regular"];
    assert_eq!(regular["repository_suffix"].as_str(), Some("mcp-server.rs"));
    assert_eq!(regular["default_access_mode"].as_str(), Some("read-only"));
    for key in [
        "destructive_tool_discovery",
        "destructive_tool_dispatch",
        "admin_proxy",
        "admin_credentials",
        "unclassified_tool_dispatch",
    ] {
        assert_eq!(
            regular[key].as_bool(),
            Some(false),
            "unsafe regular-server policy: {key}"
        );
    }
    for kind in ["regular", "admin"] {
        assert_eq!(
            policy[kind]["template_repository"].as_str(),
            Some("ORESoftware/org-mcp-server-template.rs")
        );
    }
    assert_eq!(
        policy["separation"]["distinct_service_identities"].as_bool(),
        Some(true)
    );
    assert_eq!(
        policy["separation"]["distinct_database_roles"].as_bool(),
        Some(true)
    );
    assert_eq!(
        policy["separation"]["client_asserted_roles_trusted"].as_bool(),
        Some(false)
    );
}

#[test]
fn native_multi_org_parser_preserves_pair_and_explicit_dry_run() {
    for binary in ["oresc", "ores-cli"] {
        for action in ["list-missing-repos", "create-missing-repos"] {
            let mut args = vec![binary, "orgs", "--names=alpha,beta", action];
            if action == "create-missing-repos" {
                args.extend(["--all", "--dry-run"]);
            }
            let (parsed, values) = parse(&args);
            assert_eq!(parsed.command, format!("orgs {action}"));
            assert_eq!(values["ORES_CLI_ORGS_NAMES"], "alpha,beta");
            assert_eq!(values["ORES_CLI_ORGS_STANDARD_FAMILY"], true);
            let set = members(values["ORES_CLI_ORGS_FAMILY_MEMBERS"].as_str().unwrap());
            assert!(set.contains("mcp-server.rs"));
            assert!(set.contains("admin-mcp-server.rs"));
            if action == "create-missing-repos" {
                assert_eq!(values["ORES_CLI_ORGS_CREATE_ALL"], true);
                assert_eq!(values["ORES_CLI_ORGS_DRY_RUN"], true);
                assert_eq!(values["ORES_CLI_ORGS_VISIBILITY"], "private");
            }
        }
    }
}
