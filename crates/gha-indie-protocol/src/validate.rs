use std::collections::{BTreeMap, BTreeSet};
use std::path::{Component, Path};

use serde_json::Value;

use crate::model::{BindingDocument, PlannedJob, ProfileCatalog, ProtocolError, WorkflowPlan};
use crate::{
    ALLOWED_RUNNER_LABELS, BINDINGS_SCHEMA, MAX_BASE_JOBS, MAX_CONTEXT_DIR_BYTES,
    MAX_DEPENDENCIES, MAX_IDENTIFIER_BYTES, MAX_MATRIX_JSON_BYTES, MAX_MATRIX_KEYS,
    MAX_PLAN_JOBS, MAX_PROFILE_NAME_BYTES, MAX_PROFILES, MAX_REPOSITORY_URL_BYTES, PLAN_SCHEMA,
    PROFILE_CATALOG_SCHEMA,
};

pub(crate) fn validate_plan(plan: &WorkflowPlan) -> Result<(), ProtocolError> {
    if plan.schema_version != PLAN_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_plan_schema",
            format!(
                "plan schema must be {PLAN_SCHEMA:?}, got {:?}",
                plan.schema_version
            ),
        ));
    }
    if plan.jobs.is_empty() {
        return Err(ProtocolError::new(
            "missing_plan_jobs",
            "plan must contain at least one concrete job",
        ));
    }
    if plan.jobs.len() > MAX_PLAN_JOBS {
        return Err(ProtocolError::new(
            "too_many_plan_jobs",
            format!(
                "plan contains {} jobs; maximum is {MAX_PLAN_JOBS}",
                plan.jobs.len()
            ),
        ));
    }

    let mut job_ids = BTreeSet::new();
    let mut base_job_ids = BTreeSet::new();
    for job in &plan.jobs {
        validate_instance_id("job id", &job.id)?;
        validate_base_job_id(&job.base_job_id)?;
        if !job_ids.insert(job.id.clone()) {
            return Err(ProtocolError::new(
                "duplicate_job_instance",
                format!("plan contains duplicate concrete job id {:?}", job.id),
            ));
        }
        base_job_ids.insert(job.base_job_id.clone());
        validate_profile_only_job(job)?;
    }
    if base_job_ids.len() > MAX_BASE_JOBS {
        return Err(ProtocolError::new(
            "too_many_base_jobs",
            format!(
                "plan contains {} base jobs; maximum is {MAX_BASE_JOBS}",
                base_job_ids.len()
            ),
        ));
    }

    let order_set = plan.job_order.iter().cloned().collect::<BTreeSet<_>>();
    if order_set.len() != plan.job_order.len() || order_set != base_job_ids {
        return Err(ProtocolError::new(
            "invalid_job_order",
            "jobOrder must contain every base job exactly once",
        ));
    }
    let order_index = plan
        .job_order
        .iter()
        .enumerate()
        .map(|(index, base_job_id)| (base_job_id.as_str(), index))
        .collect::<BTreeMap<_, _>>();
    let jobs_by_id = plan
        .jobs
        .iter()
        .map(|job| (job.id.as_str(), job))
        .collect::<BTreeMap<_, _>>();

    for job in &plan.jobs {
        validate_dependencies(
            job,
            plan,
            &base_job_ids,
            &jobs_by_id,
            &order_index,
        )?;
    }
    Ok(())
}

fn validate_dependencies<'a>(
    job: &'a PlannedJob,
    plan: &'a WorkflowPlan,
    base_job_ids: &BTreeSet<String>,
    jobs_by_id: &BTreeMap<&'a str, &'a PlannedJob>,
    order_index: &BTreeMap<&'a str, usize>,
) -> Result<(), ProtocolError> {
    if job.needs.len() > MAX_BASE_JOBS || job.needs_instances.len() > MAX_DEPENDENCIES {
        return Err(ProtocolError::new(
            "too_many_dependencies",
            format!(
                "job {:?} has {} base dependencies and {} concrete dependencies; maximums are {MAX_BASE_JOBS} and {MAX_DEPENDENCIES}",
                job.id,
                job.needs.len(),
                job.needs_instances.len()
            ),
        ));
    }

    let mut declared_bases = BTreeSet::new();
    for dependency in &job.needs {
        validate_base_job_id(dependency)?;
        if dependency == &job.base_job_id {
            return Err(ProtocolError::new(
                "self_dependency",
                format!("job {:?} depends on its own base job", job.id),
            ));
        }
        if !base_job_ids.contains(dependency.as_str()) {
            return Err(ProtocolError::new(
                "unknown_base_dependency",
                format!(
                    "job {:?} depends on unknown base job {dependency:?}",
                    job.id
                ),
            ));
        }
        if !declared_bases.insert(dependency.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_base_dependency",
                format!("job {:?} repeats base dependency {dependency:?}", job.id),
            ));
        }
    }

    let current_order = *order_index.get(job.base_job_id.as_str()).ok_or_else(|| {
        ProtocolError::new(
            "invalid_job_order",
            format!("base job {:?} is missing from jobOrder", job.base_job_id),
        )
    })?;
    let mut concrete_ids = BTreeSet::new();
    let mut concrete_bases = BTreeSet::new();
    for dependency in &job.needs_instances {
        validate_instance_id("dependency id", dependency)?;
        if dependency == &job.id {
            return Err(ProtocolError::new(
                "self_dependency",
                format!("job {:?} depends on itself", job.id),
            ));
        }
        let Some(dependency_job) = jobs_by_id.get(dependency.as_str()) else {
            return Err(ProtocolError::new(
                "unknown_dependency_instance",
                format!(
                    "job {:?} depends on unknown concrete job {dependency:?}",
                    job.id
                ),
            ));
        };
        if !concrete_ids.insert(dependency.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_dependency_instance",
                format!(
                    "job {:?} repeats concrete dependency {dependency:?}",
                    job.id
                ),
            ));
        }
        let dependency_order = *order_index
            .get(dependency_job.base_job_id.as_str())
            .ok_or_else(|| {
                ProtocolError::new(
                    "invalid_job_order",
                    format!(
                        "dependency base job {:?} is missing from jobOrder",
                        dependency_job.base_job_id
                    ),
                )
            })?;
        if dependency_order >= current_order {
            return Err(ProtocolError::new(
                "non_topological_dependency",
                format!(
                    "job {:?} depends on {:?}, which is not earlier in jobOrder",
                    job.id, dependency_job.id
                ),
            ));
        }
        concrete_bases.insert(dependency_job.base_job_id.as_str());
    }

    if declared_bases != concrete_bases {
        return Err(ProtocolError::new(
            "dependency_shape_mismatch",
            format!(
                "job {:?} base dependencies do not match its concrete dependency instances",
                job.id
            ),
        ));
    }
    let expected_ids = plan
        .jobs
        .iter()
        .filter(|candidate| declared_bases.contains(candidate.base_job_id.as_str()))
        .map(|candidate| candidate.id.as_str())
        .collect::<BTreeSet<_>>();
    if concrete_ids != expected_ids {
        return Err(ProtocolError::new(
            "dependency_instance_mismatch",
            format!(
                "job {:?} must depend on every concrete instance of each declared base dependency",
                job.id
            ),
        ));
    }
    Ok(())
}

fn validate_profile_only_job(job: &PlannedJob) -> Result<(), ProtocolError> {
    if !job.steps.is_empty() {
        return Err(ProtocolError::new(
            "caller_steps_not_executable",
            format!(
                "job {:?} contains caller-supplied steps; dispatch accepts reviewed profiles only",
                job.id
            ),
        ));
    }
    if job.reusable_workflow.is_some() {
        return Err(ProtocolError::new(
            "reusable_workflow_not_executable",
            format!("job {:?} contains a reusable workflow reference", job.id),
        ));
    }
    if job.condition.is_some() {
        return Err(ProtocolError::new(
            "condition_not_executable",
            format!("job {:?} contains an unevaluated condition", job.id),
        ));
    }
    if !job.env.is_empty() {
        return Err(ProtocolError::new(
            "caller_environment_not_executable",
            format!("job {:?} contains caller-controlled environment", job.id),
        ));
    }
    if job.timeout_minutes.is_some() {
        return Err(ProtocolError::new(
            "job_timeout_not_executable",
            format!("job {:?} contains a workflow-controlled timeout", job.id),
        ));
    }
    if job.continue_on_error.is_some() {
        return Err(ProtocolError::new(
            "continue_on_error_not_executable",
            format!("job {:?} contains continue-on-error", job.id),
        ));
    }
    if matches!(job.max_parallel, Some(0))
        || job.max_parallel.is_some_and(|value| value > MAX_PLAN_JOBS)
    {
        return Err(ProtocolError::new(
            "invalid_max_parallel",
            format!("job {:?} contains an invalid maxParallel", job.id),
        ));
    }
    if job.runs_on.is_empty() {
        return Err(ProtocolError::new(
            "missing_runner_labels",
            format!("job {:?} has no runner labels", job.id),
        ));
    }
    let mut labels = BTreeSet::new();
    for label in &job.runs_on {
        if !labels.insert(label.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_runner_label",
                format!("job {:?} repeats runner label {label:?}", job.id),
            ));
        }
        if !ALLOWED_RUNNER_LABELS.contains(&label.as_str()) {
            return Err(ProtocolError::new(
                "unsupported_runner_label",
                format!(
                    "job {:?} requests runner label {label:?}; allowed labels are {ALLOWED_RUNNER_LABELS:?}",
                    job.id
                ),
            ));
        }
    }
    if !job
        .runs_on
        .iter()
        .any(|label| matches!(label.as_str(), "linux" | "gha-indie-worker"))
    {
        return Err(ProtocolError::new(
            "non_linux_runner",
            format!("job {:?} must explicitly target Linux", job.id),
        ));
    }
    validate_matrix(&job.id, &job.matrix)
}

fn validate_matrix(
    job_id: &str,
    matrix: &BTreeMap<String, Value>,
) -> Result<(), ProtocolError> {
    if matrix.len() > MAX_MATRIX_KEYS {
        return Err(ProtocolError::new(
            "too_many_matrix_keys",
            format!(
                "job {job_id:?} has {} matrix keys; maximum is {MAX_MATRIX_KEYS}",
                matrix.len()
            ),
        ));
    }
    for (key, value) in matrix {
        validate_base_job_id(key).map_err(|_| {
            ProtocolError::new(
                "invalid_matrix_key",
                format!("job {job_id:?} has invalid matrix key {key:?}"),
            )
        })?;
        match value {
            Value::Null | Value::Bool(_) | Value::Number(_) => {}
            Value::String(value) => {
                if value.len() > 512 || value.chars().any(char::is_control) {
                    return Err(ProtocolError::new(
                        "invalid_matrix_value",
                        format!(
                            "job {job_id:?} matrix value for {key:?} must be printable and 512 bytes or fewer"
                        ),
                    ));
                }
            }
            Value::Array(_) | Value::Object(_) => {
                return Err(ProtocolError::new(
                    "complex_matrix_value_not_supported",
                    format!("job {job_id:?} matrix value for {key:?} must be a scalar"),
                ));
            }
        }
    }
    let encoded = serde_json::to_vec(matrix).map_err(|error| {
        ProtocolError::new(
            "matrix_serialization",
            format!("failed to size matrix for job {job_id:?}: {error}"),
        )
    })?;
    if encoded.len() > MAX_MATRIX_JSON_BYTES {
        return Err(ProtocolError::new(
            "matrix_metadata_too_large",
            format!(
                "job {job_id:?} matrix metadata is {} bytes; maximum is {MAX_MATRIX_JSON_BYTES}",
                encoded.len()
            ),
        ));
    }
    Ok(())
}

pub(crate) fn validate_catalog(catalog: &ProfileCatalog) -> Result<(), ProtocolError> {
    if catalog.schema_version != PROFILE_CATALOG_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_profile_catalog_schema",
            format!(
                "profile catalog schema must be {PROFILE_CATALOG_SCHEMA:?}, got {:?}",
                catalog.schema_version
            ),
        ));
    }
    if catalog.profiles.is_empty() || catalog.profiles.len() > MAX_PROFILES {
        return Err(ProtocolError::new(
            "invalid_profile_count",
            format!("profile catalog must contain 1-{MAX_PROFILES} profiles"),
        ));
    }
    let mut names = BTreeSet::new();
    for profile in &catalog.profiles {
        validate_profile_name(&profile.name)?;
        validate_digest("profile digest", &profile.digest)?;
        if profile.platform != "linux" {
            return Err(ProtocolError::new(
                "unsupported_profile_platform",
                format!("profile {:?} must target Linux", profile.name),
            ));
        }
        if !names.insert(profile.name.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_profile",
                format!("profile catalog repeats {:?}", profile.name),
            ));
        }
    }
    Ok(())
}

pub(crate) fn validate_bindings_shape(
    bindings: &BindingDocument,
) -> Result<(), ProtocolError> {
    if bindings.schema_version != BINDINGS_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_bindings_schema",
            format!(
                "bindings schema must be {BINDINGS_SCHEMA:?}, got {:?}",
                bindings.schema_version
            ),
        ));
    }
    validate_repository_url(&bindings.repository_url)?;
    validate_commit_sha(&bindings.commit_sha)?;
    validate_digest("profileCatalogDigest", &bindings.profile_catalog_digest)?;
    if bindings.jobs.is_empty() || bindings.jobs.len() > MAX_BASE_JOBS {
        return Err(ProtocolError::new(
            "invalid_binding_count",
            format!("bindings must contain 1-{MAX_BASE_JOBS} base jobs"),
        ));
    }
    for base_job_id in bindings.jobs.keys() {
        validate_base_job_id(base_job_id)?;
    }
    Ok(())
}

pub fn validate_commit_sha(value: &str) -> Result<(), ProtocolError> {
    if value.len() != 40
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ProtocolError::new(
            "invalid_commit_sha",
            "commitSha must be exactly 40 lowercase hexadecimal characters",
        ));
    }
    Ok(())
}

pub fn validate_digest(name: &str, value: &str) -> Result<(), ProtocolError> {
    let Some(hex) = value.strip_prefix("sha256:") else {
        return Err(ProtocolError::new(
            "invalid_digest",
            format!("{name} must use sha256:<64 lowercase hex>"),
        ));
    };
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ProtocolError::new(
            "invalid_digest",
            format!("{name} must use sha256:<64 lowercase hex>"),
        ));
    }
    Ok(())
}

pub fn validate_repository_url(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_REPOSITORY_URL_BYTES
        || value.trim() != value
        || value
            .chars()
            .any(|character| character.is_control() || character.is_whitespace())
    {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            format!(
                "repositoryUrl must be printable and 1-{MAX_REPOSITORY_URL_BYTES} bytes"
            ),
        ));
    }
    let Some(rest) = value.strip_prefix("https://") else {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must use https://",
        ));
    };
    let Some((authority, path)) = rest.split_once('/') else {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must include a DNS host and repository path",
        ));
    };
    let valid_authority = !authority.is_empty()
        && !authority.contains('@')
        && !authority.contains(':')
        && authority.split('.').all(|label| {
            !label.is_empty()
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
                && label
                    .as_bytes()
                    .first()
                    .is_some_and(|byte| byte.is_ascii_alphanumeric())
                && label
                    .as_bytes()
                    .last()
                    .is_some_and(|byte| byte.is_ascii_alphanumeric())
        });
    if !valid_authority {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl host must be lowercase DNS without credentials or a port",
        ));
    }
    let segments = path.split('/').collect::<Vec<_>>();
    if segments.len() < 2
        || segments.iter().any(|segment| {
            segment.is_empty()
                || matches!(*segment, "." | "..")
                || !segment.bytes().all(|byte| {
                    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-')
                })
        })
    {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl path must be clean ASCII without traversal, encoding, query, or fragment",
        ));
    }
    Ok(())
}

pub fn validate_context_dir(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_CONTEXT_DIR_BYTES
        || value.chars().any(char::is_control)
        || value.contains('\\')
    {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            format!("contextDir must be printable and 1-{MAX_CONTEXT_DIR_BYTES} bytes"),
        ));
    }
    let path = Path::new(value);
    if path.is_absolute() {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            "contextDir must be relative to the repository root",
        ));
    }
    let mut normal_components = 0_usize;
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::Normal(component) => {
                let part = component.to_str().ok_or_else(|| {
                    ProtocolError::new("invalid_context_dir", "contextDir must be UTF-8")
                })?;
                if !part.bytes().all(|byte| {
                    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-')
                }) {
                    return Err(ProtocolError::new(
                        "invalid_context_dir",
                        "contextDir contains characters unsafe for the worker boundary",
                    ));
                }
                normal_components += 1;
            }
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(ProtocolError::new(
                    "invalid_context_dir",
                    "contextDir must stay inside the repository root",
                ));
            }
        }
    }
    if normal_components == 0 && value != "." {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            "contextDir must resolve to the repository root or a child path",
        ));
    }
    Ok(())
}

pub(crate) fn validate_profile_name(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > MAX_PROFILE_NAME_BYTES {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            format!("profile names must be 1-{MAX_PROFILE_NAME_BYTES} bytes"),
        ));
    }
    let mut characters = value.chars();
    let first = characters.next().expect("non-empty profile name");
    if !first.is_ascii_lowercase() && !first.is_ascii_digit() {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            "profile name must start with a lowercase letter or digit",
        ));
    }
    if !characters.all(|character| {
        character.is_ascii_lowercase() || character.is_ascii_digit() || character == '-'
    }) {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            "profile name may contain lowercase letters, digits, and '-' only",
        ));
    }
    Ok(())
}

fn validate_base_job_id(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_IDENTIFIER_BYTES
        || !value
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-'))
    {
        return Err(ProtocolError::new(
            "invalid_base_job_id",
            format!("invalid base job identifier {value:?}"),
        ));
    }
    Ok(())
}

fn validate_instance_id(name: &str, value: &str) -> Result<(), ProtocolError> {
    if value.is_empty()
        || value.len() > MAX_IDENTIFIER_BYTES
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric()
                || matches!(character, '_' | '-' | '[' | ']' | '.' | ',')
        })
    {
        return Err(ProtocolError::new(
            "invalid_job_instance_id",
            format!("{name} {value:?} contains unsupported characters"),
        ));
    }
    Ok(())
}
