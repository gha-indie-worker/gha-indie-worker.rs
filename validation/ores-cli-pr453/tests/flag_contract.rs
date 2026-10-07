use std::collections::HashMap;
use std::path::PathBuf;

use flags2env::BundledFlags2Env;
use serde_json::Value;

fn contract() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../mirror/.cli-flags.toml")
}

fn parse(args: &[&str]) -> (flags2env::StructuredParse, Value) {
    let parser = BundledFlags2Env::new();
    let path = contract();
    let path = path.to_str().unwrap();
    parser.audit_config(Some(path)).unwrap();
    let args = args.iter().map(|arg| (*arg).to_owned()).collect::<Vec<_>>();
    let parsed = parser.parse_structured(&args, Some(path)).unwrap();
    assert!(parsed.errors.is_empty(), "{:?}", parsed.errors);
    assert!(parsed.unknown_options.is_empty(), "{:?}", parsed.unknown_options);
    let mut values = HashMap::<String, String>::new();
    values.extend(parsed.dotenv.clone());
    values.extend(parsed.dotenv_overrides.clone());
    values.extend(parsed.provided_flags.clone());
    let coerced = parser.coerce(&values, Some(path)).unwrap();
    (parsed, coerced)
}

#[test]
fn clone_pull_sync_flags_parse_exactly() {
    for (action, all_key, dir_key, ni_key) in [
        ("clone", "ORES_CLI_ORG_CLONE_ALL", "ORES_CLI_ORG_CLONE_DIR", "ORES_CLI_ORG_CLONE_NON_INTERACTIVE"),
        ("pull", "ORES_CLI_ORG_PULL_ALL", "ORES_CLI_ORG_PULL_DIR", "ORES_CLI_ORG_PULL_NON_INTERACTIVE"),
        ("sync", "ORES_CLI_ORG_SYNC_ALL", "ORES_CLI_ORG_SYNC_DIR", "ORES_CLI_ORG_SYNC_NON_INTERACTIVE"),
    ] {
        let (parsed, values) = parse(&[
            "oresc", "org", "--name=example-org", action,
            "--all", "--dir=/tmp/codes", "--non-interactive",
        ]);
        assert_eq!(parsed.command, format!("org {action}"));
        assert_eq!(values["ORES_CLI_ORG_NAME"], "example-org");
        assert_eq!(values[all_key], true);
        assert_eq!(values[dir_key], "/tmp/codes");
        assert_eq!(values[ni_key], true);
    }
}
