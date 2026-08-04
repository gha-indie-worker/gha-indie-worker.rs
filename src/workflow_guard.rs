//! Pre-parse and pre-expansion admission limits for workflow planning.
//!
//! The YAML reader has its own structural limits. This guard closes two
//! additional denial-of-service boundaries before recursive parsing or matrix
//! expansion: deeply nested flow collections and plans whose repeated jobs,
//! steps, or serialized configuration would grow far beyond the source size.

use serde_json::{Map, Value};

/// Maximum accepted workflow source size for both CLI and HTTP callers.
pub const MAX_WORKFLOW_SOURCE_BYTES: usize = 1024 * 1024;
/// Maximum nesting of inline `[]` and `{}` YAML flow collections.
pub const MAX_FLOW_COLLECTION_DEPTH: usize = 64;
/// Maximum number of base jobs before matrix expansion.
pub const MAX_BASE_JOBS: usize = 256;
/// Maximum conservative number of jobs after matrix expansion.
pub const MAX_PLANNED_JOBS: usize = 1024;
/// Maximum number of steps declared by one base job.
pub const MAX_STEPS_PER_JOB: usize = 256;
/// Maximum number of step copies produced across all concrete jobs.
pub const MAX_PLANNED_STEP_CLONES: usize = 16_384;
/// Maximum conservative serialized size after repeating job configurations.
pub const MAX_EXPANDED_PLAN_BYTES: usize = 32 * 1024 * 1024;
/// Maximum job dependencies accepted from one `needs` value.
pub const MAX_DEPENDENCIES_PER_JOB: usize = 64;
/// Maximum labels in one `runs-on` sequence.
pub const MAX_RUNNER_LABELS: usize = 16;
/// Maximum entries in any supported job or step `env`/`with` mapping.
pub const MAX_PARAMETER_ENTRIES: usize = 256;

const ROOT_FIELDS: &[&str] = &["name", "run-name", "on", "jobs"];
const JOB_FIELDS: &[&str] = &[
    "name",
    "needs",
    "runs-on",
    "uses",
    "if",
    "strategy",
    "env",
    "steps",
    "timeout-minutes",
    "continue-on-error",
];
const STRATEGY_FIELDS: &[&str] = &["fail-fast", "max-parallel", "matrix"];
const STEP_FIELDS: &[&str] = &[
    "id",
    "name",
    "if",
    "uses",
    "run",
    "shell",
    "working-directory",
    "with",
    "env",
    "continue-on-error",
    "timeout-minutes",
];

/// Rejects source shapes that could recurse through the inline-flow parser.
pub(crate) fn validate_source(input: &str) -> Result<(), String> {
    if input.len() > MAX_WORKFLOW_SOURCE_BYTES {
        return Err(format!(
            "workflow exceeds the {MAX_WORKFLOW_SOURCE_BYTES}-byte admission limit"
        ));
    }

    let mut flow_stack = Vec::new();
    let mut block_scalar_parent_indent = None;

    for (line_index, raw_line) in input.split('\n').enumerate() {
        let line_number = line_index + 1;
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        let indent = leading_spaces(line);

        if let Some(parent_indent) = block_scalar_parent_indent {
            if line.trim().is_empty() || indent > parent_indent {
                continue;
            }
            block_scalar_parent_indent = None;
        }

        let visible = strip_comment(line);
        if begins_block_scalar(visible) {
            block_scalar_parent_indent = Some(indent);
        }
        scan_flow_line(visible, line_number, &mut flow_stack)?;
    }

    if let Some((opening, line_number)) = flow_stack.last().copied() {
        return Err(format!(
            "line {line_number}: unterminated YAML flow collection opened with {opening:?}"
        ));
    }
    Ok(())
}

/// Rejects unsupported semantics and expansion shapes before typed planning.
pub(crate) fn validate_document(value: &Value) -> Result<(), String> {
    let root = require_object(value, "workflow root")?;
    reject_unknown_fields(root, ROOT_FIELDS, "workflow root")?;

    let Some(jobs_value) = root.get("jobs") else {
        return Ok(());
    };
    let jobs = require_object(jobs_value, "workflow jobs")?;
    if jobs.len() > MAX_BASE_JOBS {
        return Err(format!(
            "workflow defines {} base jobs; maximum is {MAX_BASE_JOBS}",
            jobs.len()
        ));
    }

    let mut planned_jobs = 0_usize;
    let mut planned_step_clones = 0_usize;
    let mut expanded_plan_bytes = 0_usize;

    for (job_id, job_value) in jobs {
        let context = format!("job {job_id:?}");
        let job = require_object(job_value, &context)?;
        reject_unknown_fields(job, JOB_FIELDS, &context)?;
        validate_parameter_map(job.get("env"), &format!("{context} env"))?;
        validate_string_or_list(
            job.get("needs"),
            MAX_DEPENDENCIES_PER_JOB,
            &format!("{context} needs"),
        )?;
        validate_string_or_list(
            job.get("runs-on"),
            MAX_RUNNER_LABELS,
            &format!("{context} runs-on"),
        )?;

        let steps = match job.get("steps") {
            None | Some(Value::Null) => None,
            Some(Value::Array(steps)) => Some(steps),
            Some(_) => return Err(format!("{context} steps must be a sequence")),
        };
        let step_count = steps.map_or(0, Vec::len);
        if step_count > MAX_STEPS_PER_JOB {
            return Err(format!(
                "{context} defines {step_count} steps; maximum is {MAX_STEPS_PER_JOB}"
            ));
        }
        if let Some(steps) = steps {
            for (index, step_value) in steps.iter().enumerate() {
                let step_context = format!("step {index} in {context}");
                let step = require_object(step_value, &step_context)?;
                reject_unknown_fields(step, STEP_FIELDS, &step_context)?;
                validate_parameter_map(step.get("env"), &format!("{step_context} env"))?;
                validate_parameter_map(step.get("with"), &format!("{step_context} with"))?;
            }
        }

        let concrete_jobs = estimate_concrete_jobs(job.get("strategy"), &context)?;
        planned_jobs = checked_add(planned_jobs, concrete_jobs, "planned job count")?;
        if planned_jobs > MAX_PLANNED_JOBS {
            return Err(format!(
                "workflow conservatively expands to {planned_jobs} jobs; maximum is {MAX_PLANNED_JOBS}"
            ));
        }

        let cloned_steps = checked_mul(step_count, concrete_jobs, "planned step count")?;
        planned_step_clones =
            checked_add(planned_step_clones, cloned_steps, "planned step count")?;
        if planned_step_clones > MAX_PLANNED_STEP_CLONES {
            return Err(format!(
                "workflow conservatively expands to {planned_step_clones} step copies; maximum is {MAX_PLANNED_STEP_CLONES}"
            ));
        }

        let encoded_job_bytes = serde_json::to_vec(job_value)
            .map_err(|error| format!("failed to size {context}: {error}"))?
            .len();
        let repeated_job_bytes = checked_mul(
            encoded_job_bytes,
            concrete_jobs,
            "expanded plan byte estimate",
        )?;
        expanded_plan_bytes = checked_add(
            expanded_plan_bytes,
            repeated_job_bytes,
            "expanded plan byte estimate",
        )?;
        if expanded_plan_bytes > MAX_EXPANDED_PLAN_BYTES {
            return Err(format!(
                "workflow expanded-plan estimate is {expanded_plan_bytes} bytes; maximum is {MAX_EXPANDED_PLAN_BYTES}"
            ));
        }
    }

    Ok(())
}

fn estimate_concrete_jobs(strategy: Option<&Value>, context: &str) -> Result<usize, String> {
    let Some(strategy) = strategy else {
        return Ok(1);
    };
    if strategy.is_null() {
        return Ok(1);
    }
    let strategy = require_object(strategy, &format!("{context} strategy"))?;
    reject_unknown_fields(strategy, STRATEGY_FIELDS, &format!("{context} strategy"))?;

    let Some(matrix) = strategy.get("matrix") else {
        return Ok(1);
    };
    if matrix.is_null() {
        return Ok(1);
    }
    let matrix = require_object(matrix, &format!("{context} matrix"))?;

    let include_count = validate_matrix_object_list(matrix.get("include"), context, "include")?;
    let _exclude_count = validate_matrix_object_list(matrix.get("exclude"), context, "exclude")?;
    let mut axis_count = 0_usize;
    let mut product = 1_usize;

    for (axis, values) in matrix {
        if matches!(axis.as_str(), "include" | "exclude") {
            continue;
        }
        let Value::Array(values) = values else {
            return Err(format!(
                "{context} matrix axis {axis:?} must be a static sequence"
            ));
        };
        if values.is_empty() {
            return Err(format!("{context} matrix axis {axis:?} cannot be empty"));
        }
        axis_count += 1;
        product = checked_mul(product, values.len(), "matrix expansion estimate")?;
        if product > MAX_PLANNED_JOBS {
            return Err(format!(
                "{context} matrix has at least {product} combinations; workflow maximum is {MAX_PLANNED_JOBS}"
            ));
        }
    }

    if axis_count == 0 {
        return Ok(include_count.max(1));
    }
    checked_add(product, include_count, "matrix expansion estimate")
}

fn validate_matrix_object_list(
    value: Option<&Value>,
    context: &str,
    field: &str,
) -> Result<usize, String> {
    let Some(value) = value else {
        return Ok(0);
    };
    let Value::Array(entries) = value else {
        return Err(format!("{context} matrix {field} must be a sequence"));
    };
    for (index, entry) in entries.iter().enumerate() {
        if !entry.is_object() {
            return Err(format!(
                "{context} matrix {field} entry {index} must be an object"
            ));
        }
    }
    Ok(entries.len())
}

fn validate_parameter_map(value: Option<&Value>, context: &str) -> Result<(), String> {
    let Some(value) = value else {
        return Ok(());
    };
    if value.is_null() {
        return Ok(());
    }
    let mapping = require_object(value, context)?;
    if mapping.len() > MAX_PARAMETER_ENTRIES {
        return Err(format!(
            "{context} has {} entries; maximum is {MAX_PARAMETER_ENTRIES}",
            mapping.len()
        ));
    }
    Ok(())
}

fn validate_string_or_list(
    value: Option<&Value>,
    maximum: usize,
    context: &str,
) -> Result<(), String> {
    let Some(value) = value else {
        return Ok(());
    };
    let count = match value {
        Value::Null => 0,
        Value::String(_) => 1,
        Value::Array(values) => values.len(),
        _ => return Err(format!("{context} must be a string or sequence")),
    };
    if count > maximum {
        return Err(format!("{context} has {count} entries; maximum is {maximum}"));
    }
    Ok(())
}

fn require_object<'a>(value: &'a Value, context: &str) -> Result<&'a Map<String, Value>, String> {
    value
        .as_object()
        .ok_or_else(|| format!("{context} must be a mapping"))
}

fn reject_unknown_fields(
    mapping: &Map<String, Value>,
    allowed: &[&str],
    context: &str,
) -> Result<(), String> {
    for field in mapping.keys() {
        if !allowed.contains(&field.as_str()) {
            return Err(format!(
                "{context} contains unsupported field {field:?}; unsupported execution semantics are rejected"
            ));
        }
    }
    Ok(())
}

fn checked_add(left: usize, right: usize, context: &str) -> Result<usize, String> {
    left.checked_add(right)
        .ok_or_else(|| format!("{context} overflow"))
}

fn checked_mul(left: usize, right: usize, context: &str) -> Result<usize, String> {
    left.checked_mul(right)
        .ok_or_else(|| format!("{context} overflow"))
}

fn scan_flow_line(
    line: &str,
    line_number: usize,
    stack: &mut Vec<(char, usize)>,
) -> Result<(), String> {
    let mut index = 0_usize;
    let mut quote = None;
    let mut escaped = false;
    let mut expression_depth = 0_usize;

    while index < line.len() {
        let remaining = &line[index..];
        let character = remaining
            .chars()
            .next()
            .ok_or_else(|| format!("line {line_number}: invalid UTF-8 boundary"))?;
        let width = character.len_utf8();

        if let Some(active_quote) = quote {
            if active_quote == '"' && escaped {
                escaped = false;
                index += width;
                continue;
            }
            if active_quote == '"' && character == '\\' {
                escaped = true;
                index += width;
                continue;
            }
            if character == active_quote {
                if active_quote == '\'' && line[index + width..].starts_with('\'') {
                    index += width + 1;
                    continue;
                }
                quote = None;
            }
            index += width;
            continue;
        }

        if expression_depth > 0 {
            match character {
                '{' => expression_depth = expression_depth.saturating_add(1),
                '}' => expression_depth = expression_depth.saturating_sub(1),
                _ => {}
            }
            index += width;
            continue;
        }

        if remaining.starts_with("${{") {
            expression_depth = 2;
            index += 3;
            continue;
        }

        match character {
            '\'' | '"' => quote = Some(character),
            '[' | '{' => {
                if stack.len() >= MAX_FLOW_COLLECTION_DEPTH {
                    return Err(format!(
                        "line {line_number}: YAML flow collection depth exceeds {MAX_FLOW_COLLECTION_DEPTH}"
                    ));
                }
                stack.push((character, line_number));
            }
            ']' | '}' => {
                let expected = if character == ']' { '[' } else { '{' };
                let Some((opening, opening_line)) = stack.pop() else {
                    return Err(format!(
                        "line {line_number}: unmatched YAML flow delimiter {character:?}"
                    ));
                };
                if opening != expected {
                    return Err(format!(
                        "line {line_number}: YAML flow delimiter {character:?} does not match {opening:?} opened on line {opening_line}"
                    ));
                }
            }
            _ => {}
        }
        index += width;
    }

    if quote.is_some() {
        return Err(format!(
            "line {line_number}: multiline quoted YAML scalars are not supported"
        ));
    }
    if expression_depth != 0 {
        return Err(format!(
            "line {line_number}: unterminated GitHub expression in YAML scalar"
        ));
    }
    Ok(())
}

fn begins_block_scalar(line: &str) -> bool {
    let trimmed = line.trim_end();
    let candidate = trimmed
        .rsplit_once(':')
        .map(|(_, remainder)| remainder.trim())
        .or_else(|| trimmed.strip_prefix('-').map(str::trim));
    candidate.is_some_and(|value| matches!(value, "|" | "|-" | "|+" | ">" | ">-" | ">+"))
}

fn leading_spaces(value: &str) -> usize {
    value.bytes().take_while(|byte| *byte == b' ').count()
}

fn strip_comment(value: &str) -> &str {
    let mut quote = None;
    let mut escaped = false;
    for (index, character) in value.char_indices() {
        if let Some(active_quote) = quote {
            if active_quote == '"' && escaped {
                escaped = false;
                continue;
            }
            if active_quote == '"' && character == '\\' {
                escaped = true;
                continue;
            }
            if character == active_quote {
                quote = None;
            }
            continue;
        }
        match character {
            '\'' | '"' => quote = Some(character),
            '#' if index == 0 || value[..index].ends_with(char::is_whitespace) => {
                return &value[..index]
            }
            _ => {}
        }
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Map};

    #[test]
    fn bounds_inline_flow_depth_before_recursive_parsing() {
        let accepted = format!(
            "value: {}0{}\n",
            "[".repeat(MAX_FLOW_COLLECTION_DEPTH),
            "]".repeat(MAX_FLOW_COLLECTION_DEPTH)
        );
        assert!(validate_source(&accepted).is_ok());

        let rejected = format!(
            "value: {}0{}\n",
            "[".repeat(MAX_FLOW_COLLECTION_DEPTH + 1),
            "]".repeat(MAX_FLOW_COLLECTION_DEPTH + 1)
        );
        let error = validate_source(&rejected).expect_err("deep flow input must fail");
        assert!(error.contains("depth exceeds"));
    }

    #[test]
    fn ignores_flow_like_text_inside_block_scalars() {
        let script = "[".repeat(MAX_FLOW_COLLECTION_DEPTH + 20);
        let yaml = format!(
            "jobs:\n  build:\n    runs-on: linux\n    steps:\n      - run: |\n          echo {script}\n"
        );
        assert!(validate_source(&yaml).is_ok());
    }

    #[test]
    fn rejects_unsupported_execution_semantics() {
        let document = json!({
            "on": "push",
            "jobs": {
                "build": {
                    "runs-on": "ubuntu-latest",
                    "container": "ubuntu:latest",
                    "steps": [{"run": "true"}]
                }
            }
        });
        let error = validate_document(&document).expect_err("container must be rejected");
        assert!(error.contains("unsupported field \"container\""));
    }

    #[test]
    fn bounds_total_matrix_and_step_expansion() {
        let axis = (0..256).map(|value| json!(value)).collect::<Vec<_>>();
        let steps = (0..65)
            .map(|_| json!({"run": "true"}))
            .collect::<Vec<_>>();
        let document = json!({
            "jobs": {
                "build": {
                    "runs-on": "ubuntu-latest",
                    "strategy": {"matrix": {"slot": axis}},
                    "steps": steps
                }
            }
        });
        let error = validate_document(&document).expect_err("step expansion must fail");
        assert!(error.contains("step copies"));
    }

    #[test]
    fn bounds_total_concrete_jobs_across_base_jobs() {
        let axis = (0..256).map(|value| json!(value)).collect::<Vec<_>>();
        let mut jobs = Map::new();
        for index in 0..5 {
            jobs.insert(
                format!("build{index}"),
                json!({
                    "runs-on": "ubuntu-latest",
                    "strategy": {"matrix": {"slot": axis.clone()}},
                    "steps": [{"run": "true"}]
                }),
            );
        }
        let document = json!({"jobs": Value::Object(jobs)});
        let error = validate_document(&document).expect_err("job expansion must fail");
        assert!(error.contains("expands to"));
    }

    #[test]
    fn accepts_supported_bounded_workflow_shape() {
        let document = json!({
            "name": "CI",
            "run-name": "bounded",
            "on": {"push": null},
            "jobs": {
                "build": {
                    "runs-on": ["self-hosted", "linux"],
                    "strategy": {"matrix": {"rust": ["stable", "beta"]}},
                    "env": {"RUST_BACKTRACE": 1},
                    "steps": [
                        {"uses": "actions/checkout@immutable", "with": {"persist-credentials": false}},
                        {"run": "cargo test --locked", "timeout-minutes": 10}
                    ]
                }
            }
        });
        validate_document(&document).unwrap_or_else(|error| panic!("guard failed: {error}"));
    }
}
