use std::collections::BTreeSet;
use std::env;
use std::io::IsTerminal;

use flags2env::BundledFlags2Env;
use ores_cli::error::RuntimeError;
use ores_cli::flags::active_flag_contract_path;
use serde_json::{Map, Value as JsonValue, json};
use toml::Value as TomlValue;

const EMBEDDED_FLAG_CONTRACT: &str = include_str!("../../.cli-flags.toml");
const MAX_DIAGNOSTIC_OPTIONS: usize = 512;
const MAX_EXPECTED_ITEMS: usize = 16;
const MAX_OFFENDING_ITEMS: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ErrorFormat {
    Text,
    Json,
    Yaml,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct InvocationContext {
    command_path: Vec<String>,
    unknown_options: Vec<String>,
    unknown_subcommand: Option<String>,
    expected: Vec<String>,
    example: Option<&'static str>,
    hint: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Diagnostic {
    kind: &'static str,
    message: String,
    command: Option<String>,
    offending: Vec<String>,
    expected: Vec<String>,
    example: Option<&'static str>,
    hint: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct OptionSpec {
    name: String,
    boolean: bool,
    takes_separate_value: bool,
}

pub(super) fn emit_runtime_error(error: &RuntimeError, argv: &[String]) {
    let diagnostic = diagnostic_for_error(error, argv);
    match requested_format(argv) {
        ErrorFormat::Json => println!("{}", render_json(&diagnostic)),
        ErrorFormat::Yaml => print!("{}", render_yaml(&diagnostic)),
        ErrorFormat::Text => eprint!("{}", render_text(&diagnostic, colors_enabled(argv))),
    }
}

fn diagnostic_for_error(error: &RuntimeError, argv: &[String]) -> Diagnostic {
    let RuntimeError::Usage(message) = error else {
        return Diagnostic {
            kind: "runtime-error",
            message: error.to_string(),
            command: None,
            offending: Vec::new(),
            expected: Vec::new(),
            example: None,
            hint: None,
        };
    };

    let context = invocation_context(argv);
    build_usage_diagnostic(message, argv.len() <= 1, context.as_ref())
}

fn build_usage_diagnostic(
    raw_message: &str,
    has_no_user_args: bool,
    context: Option<&InvocationContext>,
) -> Diagnostic {
    if let Some(context) = context {
        let command = command_label(&context.command_path);
        if !context.unknown_options.is_empty() {
            let noun = if context.unknown_options.len() == 1 {
                "option"
            } else {
                "options"
            };
            let location = command.as_ref().map_or_else(
                || "the top-level command".to_owned(),
                |value| format!("`{value}`"),
            );
            let message = if raw_message == "flag contract audit failed" {
                if context.unknown_options.len() == 1 {
                    format!(
                        "invalid invocation: flag contract audit failed; the flag '{}' was not recognized",
                        context.unknown_options[0]
                    )
                } else {
                    format!(
                        "invalid invocation: flag contract audit failed; these flags were not recognized: {}",
                        context.unknown_options.join(", ")
                    )
                }
            } else {
                format!(
                    "invalid invocation: unknown {noun} for {location}: {}",
                    context.unknown_options.join(", ")
                )
            };
            return Diagnostic {
                kind: "unknown-option",
                message,
                command,
                offending: context.unknown_options.clone(),
                expected: context.expected.clone(),
                example: context.example,
                hint: context.hint.clone(),
            };
        }

        if let Some(unknown_subcommand) = &context.unknown_subcommand {
            let parent = command.as_ref().map_or_else(
                || "the top level".to_owned(),
                |value| format!("`{value}`"),
            );
            return Diagnostic {
                kind: if context.command_path.is_empty() {
                    "unknown-command"
                } else {
                    "unknown-subcommand"
                },
                message: format!(
                    "invalid invocation: unknown {} `{unknown_subcommand}` after {parent}",
                    if context.command_path.is_empty() {
                        "command"
                    } else {
                        "subcommand"
                    }
                ),
                command,
                offending: vec![unknown_subcommand.clone()],
                expected: context.expected.clone(),
                example: context.example,
                hint: context.hint.clone(),
            };
        }
    }

    let message = if has_no_user_args && raw_message.starts_with("unknown command:") {
        format!(
            "invalid invocation: {}",
            raw_message.replacen("unknown command:", "missing command:", 1)
        )
    } else {
        format!("invalid invocation: {raw_message}")
    };
    let (command, expected, example, hint) = context.map_or_else(
        || (None, Vec::new(), None, None),
        |context| {
            (
                command_label(&context.command_path),
                context.expected.clone(),
                context.example,
                context.hint.clone(),
            )
        },
    );
    Diagnostic {
        kind: if has_no_user_args {
            "missing-command"
        } else {
            "invalid-invocation"
        },
        message,
        command,
        offending: Vec::new(),
        expected,
        example,
        hint,
    }
}

fn invocation_context(argv: &[String]) -> Option<InvocationContext> {
    let contract = embedded_contract()?;

    if let Ok(contract_path) = active_flag_contract_path() {
        let parser = BundledFlags2Env::new();
        if let Ok(structured) = parser.parse_structured(argv, Some(contract_path.as_str())) {
            let mut unknown_options = structured
                .unknown_options
                .iter()
                .filter_map(|option| sanitized_option_name(option))
                .collect::<Vec<_>>();
            unknown_options.sort();
            unknown_options.dedup();
            unknown_options.truncate(MAX_OFFENDING_ITEMS);
            let parser_path = parser
                .resolve_commands(argv, Some(contract_path.as_str()))
                .ok()
                .map(|commands| commands.path)
                .unwrap_or_default();
            let fallback_path = fallback_command_path(argv, &contract);
            let use_fallback = parser_path.is_empty()
                || (!unknown_options.is_empty() && fallback_path.len() > parser_path.len());
            let command_path = if use_fallback {
                fallback_path
            } else {
                parser_path
            };
            let children = child_commands(&contract, &command_path);
            let unknown_subcommand = if children.is_empty() {
                None
            } else {
                structured
                    .extras
                    .first()
                    .and_then(|value| sanitized_command_name(value))
            };
            let expected = if !unknown_options.is_empty() {
                expected_for_unknown_options(&contract, &command_path, &unknown_options)
            } else {
                children
            };
            let hint =
                diagnostic_hint(&command_path, &unknown_options, unknown_subcommand.as_deref());
            let example = invocation_example(&command_path);
            return Some(InvocationContext {
                command_path,
                unknown_options,
                unknown_subcommand,
                expected,
                example,
                hint,
            });
        }
    }

    fallback_invocation_context(argv, &contract)
}

fn embedded_contract() -> Option<TomlValue> {
    toml::from_str(EMBEDDED_FLAG_CONTRACT).ok()
}

fn command_node<'a>(
    contract: &'a TomlValue,
    command_path: &[String],
) -> Option<&'a toml::map::Map<String, TomlValue>> {
    let mut node = contract.as_table()?;
    for segment in command_path {
        node = node
            .get("commands")?
            .as_table()?
            .get(segment)?
            .as_table()?;
    }
    Some(node)
}

fn child_commands(contract: &TomlValue, command_path: &[String]) -> Vec<String> {
    let Some(commands) = command_node(contract, command_path)
        .and_then(|node| node.get("commands"))
        .and_then(TomlValue::as_table)
    else {
        return Vec::new();
    };
    let mut names = commands.keys().cloned().collect::<Vec<_>>();
    names.sort();
    names.truncate(MAX_EXPECTED_ITEMS);
    names
}

fn flag_specs_from_node(node: &toml::map::Map<String, TomlValue>) -> Vec<OptionSpec> {
    let Some(flags) = node.get("flags").and_then(TomlValue::as_table) else {
        return Vec::new();
    };
    let mut specs = Vec::new();
    let mut seen = BTreeSet::new();

    for (flag_name, value) in flags {
        let Some(flag) = value.as_table() else {
            continue;
        };
        let boolean = flag.get("type").and_then(TomlValue::as_str) == Some("bool");
        let mut names = vec![format!("--{flag_name}")];
        if let Some(aliases) = flag.get("aliases").and_then(TomlValue::as_array) {
            names.extend(
                aliases
                    .iter()
                    .filter_map(TomlValue::as_str)
                    .map(|alias| format!("--{alias}")),
            );
        }
        if let Some(short) = flag.get("short").and_then(TomlValue::as_str) {
            names.push(format!("-{short}"));
        }

        for name in names {
            if specs.len() >= MAX_DIAGNOSTIC_OPTIONS {
                return specs;
            }
            if sanitized_option_name(&name).as_deref() != Some(name.as_str()) {
                continue;
            }
            if seen.insert(name.clone()) {
                specs.push(OptionSpec {
                    name,
                    boolean,
                    takes_separate_value: !boolean,
                });
            }
        }
    }
    specs
}

fn append_unique_specs(
    specs: &mut Vec<OptionSpec>,
    seen: &mut BTreeSet<String>,
    items: Vec<OptionSpec>,
) {
    for item in items {
        if specs.len() >= MAX_DIAGNOSTIC_OPTIONS {
            return;
        }
        if seen.insert(item.name.clone()) {
            specs.push(item);
        }
    }
}

fn known_option_specs(contract: &TomlValue, command_path: &[String]) -> Vec<OptionSpec> {
    let mut specs = Vec::new();
    let mut seen = BTreeSet::new();

    if let Some(root) = contract.as_table() {
        append_unique_specs(&mut specs, &mut seen, flag_specs_from_node(root));
    }
    for length in 1..=command_path.len() {
        if let Some(node) = command_node(contract, &command_path[..length]) {
            append_unique_specs(&mut specs, &mut seen, flag_specs_from_node(node));
        }
    }
    specs
}

fn local_long_options(contract: &TomlValue, command_path: &[String]) -> Vec<String> {
    let Some(node) = command_node(contract, command_path) else {
        return Vec::new();
    };
    let mut options = flag_specs_from_node(node)
        .into_iter()
        .filter(|spec| spec.name.starts_with("--"))
        .map(|spec| spec.name)
        .collect::<Vec<_>>();
    options.sort();
    options.dedup();
    options.truncate(MAX_EXPECTED_ITEMS);
    options
}

fn option_spec<'a>(option: &str, specs: &'a [OptionSpec]) -> Option<&'a OptionSpec> {
    if let Some(spec) = specs.iter().find(|spec| spec.name == option) {
        return Some(spec);
    }
    let positive = option.strip_prefix("--no-").map(|name| format!("--{name}"))?;
    specs
        .iter()
        .find(|spec| spec.boolean && spec.name == positive)
}

fn nearest_option_spec<'a>(option: &str, specs: &'a [OptionSpec]) -> Option<&'a OptionSpec> {
    let normalized = option
        .strip_prefix("--no-")
        .map_or_else(|| option.to_owned(), |name| format!("--{name}"));
    let mut best = None;
    let mut best_distance = 3_usize;
    let mut tied = false;

    for spec in specs.iter().filter(|spec| spec.name.starts_with("--")) {
        let distance = edit_distance(&normalized, &spec.name);
        if distance < best_distance {
            best = Some(spec);
            best_distance = distance;
            tied = false;
        } else if distance == best_distance {
            tied = true;
        }
    }

    if best_distance <= 2 && !tied {
        return best;
    }
    None
}

fn expected_for_unknown_options(
    contract: &TomlValue,
    command_path: &[String],
    unknown_options: &[String],
) -> Vec<String> {
    let specs = known_option_specs(contract, command_path);
    let mut suggestions = unknown_options
        .iter()
        .filter_map(|option| nearest_option_spec(option, &specs))
        .map(|spec| spec.name.clone())
        .collect::<Vec<_>>();
    suggestions.sort();
    suggestions.dedup();
    if suggestions.is_empty() {
        return local_long_options(contract, command_path);
    }
    suggestions.truncate(MAX_EXPECTED_ITEMS);
    suggestions
}

fn fallback_invocation_context(
    argv: &[String],
    contract: &TomlValue,
) -> Option<InvocationContext> {
    let command_path = fallback_command_path(argv, contract);
    let specs = known_option_specs(contract, &command_path);
    let mut unknown_options = Vec::new();
    let mut suggestions = Vec::new();
    let mut index = 1_usize;

    while index < argv.len() {
        let raw = &argv[index];
        if raw == "--" {
            break;
        }
        if !raw.starts_with('-') {
            index += 1;
            continue;
        }

        let Some(option) = sanitized_option_name(raw) else {
            index += 1;
            continue;
        };
        let exact = option_spec(&option, &specs);
        let suggestion = if exact.is_none() {
            nearest_option_spec(&option, &specs)
        } else {
            None
        };
        if exact.is_none() {
            if let Some(suggestion) = suggestion {
                suggestions.push(suggestion.name.clone());
            }
            unknown_options.push(option);
        }

        let takes_separate_value = exact
            .or(suggestion)
            .is_some_and(|spec| spec.takes_separate_value);
        let next_is_value = index + 1 < argv.len() && !argv[index + 1].starts_with('-');
        if !raw.contains('=') && takes_separate_value && next_is_value {
            index += 2;
        } else {
            index += 1;
        }
    }

    if unknown_options.is_empty() {
        return None;
    }

    unknown_options.sort();
    unknown_options.dedup();
    unknown_options.truncate(MAX_OFFENDING_ITEMS);
    suggestions.sort();
    suggestions.dedup();
    suggestions.truncate(MAX_EXPECTED_ITEMS);
    let hint = if unknown_options.len() == 1 && suggestions.len() == 1 {
        Some(format!("did you mean `{}`?", suggestions[0]))
    } else {
        None
    };
    let expected = if suggestions.is_empty() {
        local_long_options(contract, &command_path)
    } else {
        suggestions
    };
    let example = invocation_example(&command_path);
    Some(InvocationContext {
        command_path,
        unknown_options,
        unknown_subcommand: None,
        expected,
        example,
        hint,
    })
}

fn fallback_command_path(argv: &[String], contract: &TomlValue) -> Vec<String> {
    let mut command_path = Vec::new();
    let mut index = 1_usize;

    while index < argv.len() {
        let value = &argv[index];
        if value == "--" {
            break;
        }
        if value.starts_with('-') {
            if let Some(option) = sanitized_option_name(value) {
                let specs = known_option_specs(contract, &command_path);
                let spec = option_spec(&option, &specs)
                    .or_else(|| nearest_option_spec(&option, &specs));
                let next_is_value = index + 1 < argv.len() && !argv[index + 1].starts_with('-');
                if !value.contains('=')
                    && spec.is_some_and(|spec| spec.takes_separate_value)
                    && next_is_value
                {
                    index += 2;
                    continue;
                }
            }
            index += 1;
            continue;
        }

        let children = child_commands(contract, &command_path);
        if children.iter().any(|child| child == value) {
            command_path.push(value.clone());
        }
        index += 1;
    }
    command_path
}

fn edit_distance(left: &str, right: &str) -> usize {
    let right = right.as_bytes();
    let mut previous = (0..=right.len()).collect::<Vec<_>>();

    for (left_index, left_byte) in left.bytes().enumerate() {
        let mut current = Vec::with_capacity(right.len() + 1);
        current.push(left_index + 1);
        for (right_index, right_byte) in right.iter().copied().enumerate() {
            let deletion = previous[right_index + 1] + 1;
            let insertion = current[right_index] + 1;
            let substitution = previous[right_index]
                + if left_byte == right_byte { 0 } else { 1 };
            current.push(deletion.min(insertion).min(substitution));
        }
        previous = current;
    }
    previous[right.len()]
}

fn requested_format(argv: &[String]) -> ErrorFormat {
    if explicit_bool_preference(argv, "yaml") == Some(true) {
        return ErrorFormat::Yaml;
    }
    if explicit_bool_preference(argv, "json") == Some(true) {
        return ErrorFormat::Json;
    }
    ErrorFormat::Text
}

fn explicit_bool_preference(argv: &[String], name: &str) -> Option<bool> {
    let positive = format!("--{name}");
    let negative = format!("--no-{name}");
    let inline = format!("--{name}=");
    let mut preference = None;

    for value in argv.iter().skip(1) {
        if value == "--" {
            break;
        }
        if value == &positive {
            preference = Some(true);
        } else if value == &negative {
            preference = Some(false);
        } else if let Some(raw) = value.strip_prefix(&inline) {
            if let Some(parsed) = parse_contract_bool(raw) {
                preference = Some(parsed);
            }
        }
    }
    preference
}

fn parse_contract_bool(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "t" | "1" | "yes" => Some(true),
        "false" | "f" | "0" | "no" => Some(false),
        _ => None,
    }
}

fn colors_enabled(argv: &[String]) -> bool {
    let environment_color = env::var("ORES_CLI_COLORS").ok();
    colors_enabled_with_context(
        argv,
        std::io::stderr().is_terminal(),
        env::var_os("NO_COLOR").is_some(),
        environment_color.as_deref(),
    )
}

fn colors_enabled_with_context(
    argv: &[String],
    is_terminal: bool,
    no_color_present: bool,
    environment_color: Option<&str>,
) -> bool {
    if !is_terminal {
        return false;
    }
    if let Some(explicit) = explicit_bool_preference(argv, "colors") {
        return explicit;
    }
    if no_color_present {
        return false;
    }
    environment_color.and_then(parse_contract_bool).unwrap_or(true)
}

fn sanitized_option_name(raw: &str) -> Option<String> {
    let option = raw.split_once('=').map_or(raw, |(name, _)| name);
    if option.len() < 2 || option.len() > 128 || !option.starts_with('-') {
        return None;
    }
    if option == "--" {
        return None;
    }
    if !option
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        return None;
    }
    Some(option.to_owned())
}

fn sanitized_command_name(raw: &str) -> Option<String> {
    if raw.is_empty() || raw.len() > 96 || raw.starts_with('-') {
        return None;
    }
    if !raw
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        return None;
    }
    Some(raw.to_owned())
}

fn command_label(path: &[String]) -> Option<String> {
    if path.is_empty() {
        return None;
    }
    Some(path.join(" "))
}

fn diagnostic_hint(
    command_path: &[String],
    unknown_options: &[String],
    unknown_subcommand: Option<&str>,
) -> Option<String> {
    if command_path == ["org", "list-missing-repos"]
        && unknown_options.iter().any(|value| value == "--all")
    {
        return Some(
            "`--all` belongs to `org create-missing-repos`; `org list-missing-repos` is already read-only and lists the full missing set unless repository selectors are supplied"
                .to_owned(),
        );
    }
    if command_path == ["org"] && unknown_subcommand == Some("create-repo") {
        return Some(
            "the repository-creation subcommand is named `create-missing-repos`, not `create-repo`"
                .to_owned(),
        );
    }
    None
}

fn invocation_example(command_path: &[String]) -> Option<&'static str> {
    match command_path {
        [] => Some("oresc doctor"),
        [doctor] if doctor == "doctor" => Some("oresc doctor"),
        [org] if org == "org" => Some("oresc org --name=<org>"),
        [org, list] if org == "org" && list == "list-missing-repos" => {
            Some("oresc org --name=<org> --prefix=<prefix> list-missing-repos")
        }
        [org, create] if org == "org" && create == "create-missing-repos" => {
            Some("oresc org --name=<org> --prefix=<prefix> create-missing-repos --all")
        }
        [org, set] if org == "org" && set == "set-repo-visibility" => Some(
            "oresc org --name=<org> set-repo-visibility --visibility=private --dry-run",
        ),
        [org, action]
            if org == "org" && matches!(action.as_str(), "clone" | "pull" | "sync") =>
        {
            Some("oresc org --name=<org> sync --all")
        }
        [orgs] if orgs == "orgs" => Some("oresc orgs list"),
        [audit] if audit == "audit" => Some("oresc audit repo --path=."),
        [audit, org] if audit == "audit" && org == "org" => Some("oresc audit org --org=<org>"),
        [audit, repo] if audit == "audit" && repo == "repo" => Some("oresc audit repo --path=."),
        [audit, env] if audit == "audit" && env == "env" => Some("oresc audit env --path=."),
        [audit, package] if audit == "audit" && package == "package" => {
            Some("oresc audit package --path=.")
        }
        [audit, contract] if audit == "audit" && contract == "contract" => Some(
            "oresc audit contract --typespec=<main.tsp> --schema=<schema.json>",
        ),
        [codespace, edge] if codespace == "codespace" && edge == "edge" => {
            Some("oresc codespace edge status")
        }
        [mcp] if mcp == "mcp" => Some("oresc mcp probe --server=<executable>"),
        [ai] if ai == "ai" => Some("oresc ai auth"),
        [ai, threads] if ai == "ai" && threads == "threads" => Some("oresc ai threads list"),
        _ => None,
    }
}

fn render_text(diagnostic: &Diagnostic, colors: bool) -> String {
    let error_label = label("ores-cli runtime error:", "1;31", colors);
    let hint_label = label("hint:", "1;33", colors);
    let expected_label = label("expected:", "1;36", colors);
    let example_label = label("example:", "1;32", colors);
    let mut output = format!("{error_label} {}\n", diagnostic.message);
    if !diagnostic.expected.is_empty() {
        output.push_str(&format!(
            "{expected_label} {}\n",
            diagnostic.expected.join(", ")
        ));
    }
    if let Some(hint) = &diagnostic.hint {
        output.push_str(&format!("{hint_label} {hint}\n"));
    }
    if let Some(example) = diagnostic.example {
        output.push_str(&format!("{example_label} {example}\n"));
    }
    output
}

fn label(value: &str, ansi: &str, colors: bool) -> String {
    if colors {
        return format!("\x1b[{ansi}m{value}\x1b[0m");
    }
    value.to_owned()
}

fn diagnostic_json_value(diagnostic: &Diagnostic) -> JsonValue {
    let mut error = Map::new();
    error.insert("kind".to_owned(), json!(diagnostic.kind));
    error.insert("message".to_owned(), json!(&diagnostic.message));
    if let Some(command) = &diagnostic.command {
        error.insert("command".to_owned(), json!(command));
    }
    if !diagnostic.offending.is_empty() {
        error.insert("offending".to_owned(), json!(&diagnostic.offending));
    }
    if !diagnostic.expected.is_empty() {
        error.insert("expected".to_owned(), json!(&diagnostic.expected));
    }
    if let Some(hint) = &diagnostic.hint {
        error.insert("hint".to_owned(), json!(hint));
    }
    if let Some(example) = diagnostic.example {
        error.insert("example".to_owned(), json!(example));
    }
    json!({
        "schema": "ores-cli/error/v1",
        "ok": false,
        "error": error,
    })
}

fn render_json(diagnostic: &Diagnostic) -> String {
    serde_json::to_string(&diagnostic_json_value(diagnostic))
        .unwrap_or_else(|_| "{\"schema\":\"ores-cli/error/v1\",\"ok\":false}".to_owned())
}

fn render_yaml(diagnostic: &Diagnostic) -> String {
    let value = diagnostic_json_value(diagnostic);
    let error = value
        .get("error")
        .and_then(JsonValue::as_object)
        .expect("diagnostic error object is constructed above");
    let mut output = String::new();
    output.push_str("schema: \"ores-cli/error/v1\"\n");
    output.push_str("ok: false\n");
    output.push_str("error:\n");
    for key in ["kind", "message", "command", "hint", "example"] {
        if let Some(item) = error.get(key).and_then(JsonValue::as_str) {
            output.push_str("  ");
            output.push_str(key);
            output.push_str(": ");
            output.push_str(&yaml_string(item));
            output.push('\n');
        }
    }
    for key in ["offending", "expected"] {
        if let Some(items) = error.get(key).and_then(JsonValue::as_array) {
            output.push_str("  ");
            output.push_str(key);
            output.push_str(":\n");
            for item in items.iter().filter_map(JsonValue::as_str) {
                output.push_str("    - ");
                output.push_str(&yaml_string(item));
                output.push('\n');
            }
        }
    }
    output
}

fn yaml_string(value: &str) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "\"<unavailable>\"".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract() -> TomlValue {
        embedded_contract().expect("embedded CLI contract must parse")
    }

    #[test]
    fn embedded_contract_is_the_diagnostic_option_authority() {
        let contract = contract();
        let path = vec!["org".to_owned(), "list-missing-repos".to_owned()];
        let specs = known_option_specs(&contract, &path);
        assert!(specs.iter().any(|spec| spec.name == "--prefix"));
        assert!(specs.iter().any(|spec| spec.name == "--name"));
        assert!(specs.iter().any(|spec| spec.name == "--report"));
        assert!(specs.iter().any(|spec| spec.name == "--colors"));
    }

    #[test]
    fn sanitizes_unknown_option_without_echoing_inline_value() {
        assert_eq!(
            sanitized_option_name("--token=do-not-print-this"),
            Some("--token".to_owned())
        );
        assert_eq!(sanitized_option_name("--all"), Some("--all".to_owned()));
        assert_eq!(sanitized_option_name("secret positional"), None);
    }

    #[test]
    fn fallback_names_misspelled_flag_when_runtime_contract_is_unavailable() {
        let contract = contract();
        let argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name=litegraph".to_owned(),
            "--preix=ltgr".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        let context = fallback_invocation_context(&argv, &contract)
            .expect("near-miss flag should be diagnosed");
        assert_eq!(
            context.command_path,
            vec!["org".to_owned(), "list-missing-repos".to_owned()]
        );
        assert_eq!(context.unknown_options, vec!["--preix".to_owned()]);
        assert_eq!(context.expected, vec!["--prefix".to_owned()]);
        assert_eq!(context.hint.as_deref(), Some("did you mean `--prefix`?"));
    }

    #[test]
    fn fallback_names_arbitrary_unknown_flag_without_reflecting_value() {
        let contract = contract();
        let argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name=litegraph".to_owned(),
            "--definitely-not-a-real-flag=do-not-print-this".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        let context = fallback_invocation_context(&argv, &contract)
            .expect("arbitrary unknown flag should be diagnosed");
        assert_eq!(
            context.unknown_options,
            vec!["--definitely-not-a-real-flag".to_owned()]
        );
        assert!(!context.expected.iter().any(|item| item.contains("do-not-print-this")));
    }

    #[test]
    fn fallback_skips_plain_separate_values_for_near_miss_value_flags() {
        let contract = contract();
        let argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name".to_owned(),
            "litegraph".to_owned(),
            "--preix".to_owned(),
            "ltgr".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        assert_eq!(
            fallback_command_path(&argv, &contract),
            vec!["org".to_owned(), "list-missing-repos".to_owned()]
        );
    }

    #[test]
    fn fallback_reparses_option_shaped_tokens_and_stops_at_terminator() {
        let contract = contract();
        let option_argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name".to_owned(),
            "--literal-value".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        let context = fallback_invocation_context(&option_argv, &contract)
            .expect("option-shaped token should be diagnosed as an option");
        assert_eq!(context.unknown_options, vec!["--literal-value".to_owned()]);

        let terminator_argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name=litegraph".to_owned(),
            "list-missing-repos".to_owned(),
            "--".to_owned(),
            "--literal-not-a-flag".to_owned(),
        ];
        assert!(fallback_invocation_context(&terminator_argv, &contract).is_none());
    }

    #[test]
    fn fallback_bounds_offending_option_inventory() {
        let contract = contract();
        let mut argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name=litegraph".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        for index in 0..(MAX_OFFENDING_ITEMS + 8) {
            argv.push(format!("--unknown-{index}"));
        }
        let context = fallback_invocation_context(&argv, &contract)
            .expect("unknown flags should be diagnosed");
        assert_eq!(context.unknown_options.len(), MAX_OFFENDING_ITEMS);
    }

    #[test]
    fn parser_context_uses_longer_fallback_path_for_unknown_separate_value_option() {
        let contract = contract();
        let argv = vec![
            "oresc".to_owned(),
            "org".to_owned(),
            "--name".to_owned(),
            "litegraph".to_owned(),
            "--preix".to_owned(),
            "ltgr".to_owned(),
            "list-missing-repos".to_owned(),
        ];
        assert_eq!(
            fallback_command_path(&argv, &contract),
            vec!["org".to_owned(), "list-missing-repos".to_owned()]
        );
    }

    #[test]
    fn flag_contract_failure_keeps_the_offending_flag_in_the_primary_message() {
        let context = InvocationContext {
            command_path: vec!["org".to_owned(), "list-missing-repos".to_owned()],
            unknown_options: vec!["--preix".to_owned()],
            unknown_subcommand: None,
            expected: vec!["--prefix".to_owned()],
            example: invocation_example(&[
                "org".to_owned(),
                "list-missing-repos".to_owned(),
            ]),
            hint: Some("did you mean `--prefix`?".to_owned()),
        };
        let diagnostic = build_usage_diagnostic("flag contract audit failed", false, Some(&context));
        assert_eq!(diagnostic.kind, "unknown-option");
        assert_eq!(
            diagnostic.message,
            "invalid invocation: flag contract audit failed; the flag '--preix' was not recognized"
        );
    }

    #[test]
    fn unknown_subcommand_is_classified_without_positional_wording() {
        let context = InvocationContext {
            command_path: vec!["org".to_owned()],
            unknown_options: Vec::new(),
            unknown_subcommand: Some("create-repo".to_owned()),
            expected: child_commands(&contract(), &["org".to_owned()]),
            example: invocation_example(&["org".to_owned()]),
            hint: diagnostic_hint(&["org".to_owned()], &[], Some("create-repo")),
        };
        let diagnostic = build_usage_diagnostic(
            "`org` does not accept positional arguments (received 1)",
            false,
            Some(&context),
        );
        assert_eq!(diagnostic.kind, "unknown-subcommand");
        assert!(diagnostic.message.contains("create-repo"));
        assert!(diagnostic.expected.contains(&"create-missing-repos".to_owned()));
        assert!(!diagnostic.message.contains("positional"));
    }

    #[test]
    fn all_on_list_missing_names_the_option_and_correct_command() {
        let context = InvocationContext {
            command_path: vec!["org".to_owned(), "list-missing-repos".to_owned()],
            unknown_options: vec!["--all".to_owned()],
            unknown_subcommand: None,
            expected: local_long_options(
                &contract(),
                &["org".to_owned(), "list-missing-repos".to_owned()],
            ),
            example: invocation_example(&[
                "org".to_owned(),
                "list-missing-repos".to_owned(),
            ]),
            hint: diagnostic_hint(
                &["org".to_owned(), "list-missing-repos".to_owned()],
                &["--all".to_owned()],
                None,
            ),
        };
        let diagnostic = build_usage_diagnostic(
            "unknown options: 1 rejected argument(s)",
            false,
            Some(&context),
        );
        assert_eq!(diagnostic.kind, "unknown-option");
        assert!(diagnostic.message.contains("--all"));
        assert!(diagnostic.hint.expect("hint").contains("create-missing-repos"));
    }

    #[test]
    fn explicit_boolean_forms_control_structured_error_format() {
        assert_eq!(
            requested_format(&["oresc".to_owned(), "--json=true".to_owned()]),
            ErrorFormat::Json
        );
        assert_eq!(
            requested_format(&["oresc".to_owned(), "--json=false".to_owned()]),
            ErrorFormat::Text
        );
        assert_eq!(
            requested_format(&["oresc".to_owned(), "--yaml=yes".to_owned()]),
            ErrorFormat::Yaml
        );
        assert_eq!(
            requested_format(&[
                "oresc".to_owned(),
                "org".to_owned(),
                "--name".to_owned(),
                "--json".to_owned(),
                "list-missing-repos".to_owned(),
            ]),
            ErrorFormat::Json
        );
        assert_eq!(
            requested_format(&[
                "oresc".to_owned(),
                "org".to_owned(),
                "--name=litegraph".to_owned(),
                "list-missing-repos".to_owned(),
                "--".to_owned(),
                "--json".to_owned(),
            ]),
            ErrorFormat::Text
        );
    }

    #[test]
    fn color_controls_follow_explicit_then_environment_precedence() {
        assert!(colors_enabled_with_context(&["oresc".to_owned()], true, false, None));
        assert!(!colors_enabled_with_context(
            &["oresc".to_owned(), "--colors=false".to_owned()],
            true,
            false,
            None
        ));
        assert!(colors_enabled_with_context(
            &["oresc".to_owned(), "--colors=true".to_owned()],
            true,
            true,
            Some("false")
        ));
        assert!(!colors_enabled_with_context(
            &["oresc".to_owned()],
            true,
            true,
            Some("true")
        ));
        assert!(!colors_enabled_with_context(
            &["oresc".to_owned()],
            true,
            false,
            Some("false")
        ));
        assert!(!colors_enabled_with_context(
            &["oresc".to_owned(), "--colors=true".to_owned()],
            false,
            false,
            None
        ));
        assert!(colors_enabled_with_context(
            &[
                "oresc".to_owned(),
                "--".to_owned(),
                "--colors=false".to_owned(),
            ],
            true,
            false,
            None
        ));
    }

    #[test]
    fn human_text_renderer_emits_ansi_when_colors_are_enabled() {
        let diagnostic = Diagnostic {
            kind: "unknown-option",
            message: "invalid invocation: unknown option --all".to_owned(),
            command: Some("org list-missing-repos".to_owned()),
            offending: vec!["--all".to_owned()],
            expected: vec!["--report".to_owned()],
            example: Some("oresc org --name=<org> list-missing-repos"),
            hint: None,
        };
        let rendered = render_text(&diagnostic, true);
        assert!(rendered.contains("\u{1b}[1;31mores-cli runtime error:\u{1b}[0m"));
        assert!(rendered.contains("\u{1b}[1;36mexpected:\u{1b}[0m"));
    }

    #[test]
    fn json_and_yaml_are_structured_and_never_contain_ansi_color() {
        let diagnostic = Diagnostic {
            kind: "unknown-option",
            message: "invalid invocation: unknown option --all".to_owned(),
            command: Some("org list-missing-repos".to_owned()),
            offending: vec!["--all".to_owned()],
            expected: vec!["--report".to_owned()],
            example: Some("oresc org --name=<org> list-missing-repos"),
            hint: None,
        };
        let json = render_json(&diagnostic);
        let yaml = render_yaml(&diagnostic);
        assert!(json.contains("\"schema\":\"ores-cli/error/v1\""));
        assert!(yaml.starts_with("schema: \"ores-cli/error/v1\""));
        assert!(!json.contains("\u{1b}"));
        assert!(!yaml.contains("\u{1b}"));
    }
}
