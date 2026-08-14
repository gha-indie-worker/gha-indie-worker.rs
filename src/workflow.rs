//! Parsing and deterministic expansion of a useful GitHub Actions workflow subset.
//!
//! The planner deliberately does not evaluate `${{ ... }}` expressions or run
//! commands. It validates the dependency graph, expands static matrices, and
//! preserves a bounded whole-expression matrix for the scheduler to resolve
//! after direct dependencies have produced their declared outputs.

use std::collections::{BTreeMap, BTreeSet};
use std::error::Error;
use std::fmt::{Display, Formatter};

use serde::de::Error as _;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

/// Maximum number of concrete jobs produced by one matrix definition.
pub const MAX_MATRIX_JOBS: usize = 256;

/// A validated, topologically ordered workflow execution plan.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WorkflowPlan {
    /// Stable schema identifier for API and persistence consumers.
    pub schema_version: &'static str,
    /// Optional workflow display name.
    pub name: Option<String>,
    /// Base job identifiers in deterministic dependency order.
    pub job_order: Vec<String>,
    /// Concrete jobs after static matrix expansion.
    pub jobs: Vec<PlannedJob>,
}

/// One concrete job instance produced by the planner.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlannedJob {
    /// Stable instance identifier. Matrix jobs use a one-based suffix.
    pub id: String,
    /// Job identifier from the workflow YAML before matrix expansion.
    pub base_job_id: String,
    /// Human-readable job name.
    pub name: String,
    /// Base job dependencies declared with `needs`.
    pub needs: Vec<String>,
    /// Concrete dependency instances that must finish before this job starts.
    pub needs_instances: Vec<String>,
    /// Runner labels from `runs-on`.
    pub runs_on: Vec<String>,
    /// Reusable workflow reference for a job-level `uses`, when present.
    pub reusable_workflow: Option<String>,
    /// Unevaluated job condition.
    pub condition: Option<String>,
    /// Static matrix values assigned to this instance.
    pub matrix: BTreeMap<String, Value>,
    /// Whole expression deferred until direct dependency outputs are available.
    pub matrix_expression: Option<String>,
    /// Job-level environment values.
    pub env: BTreeMap<String, Value>,
    /// Ordered execution steps.
    pub steps: Vec<PlannedStep>,
    /// Matrix fail-fast setting.
    pub fail_fast: bool,
    /// Optional matrix concurrency ceiling.
    pub max_parallel: Option<usize>,
    /// Optional job timeout.
    pub timeout_minutes: Option<u64>,
    /// Unevaluated `continue-on-error` value.
    pub continue_on_error: Option<Value>,
    /// Declared job outputs evaluated from the final step context.
    pub outputs: BTreeMap<String, String>,
}

/// One validated workflow step.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlannedStep {
    /// Zero-based position within the job.
    pub index: usize,
    /// Optional stable step identifier.
    pub id: Option<String>,
    /// Optional display name.
    pub name: Option<String>,
    /// Unevaluated step condition.
    pub condition: Option<String>,
    /// Action reference for a `uses` step.
    pub uses: Option<String>,
    /// Shell source for a `run` step.
    pub run: Option<String>,
    /// Optional shell override.
    pub shell: Option<String>,
    /// Optional working directory.
    pub working_directory: Option<String>,
    /// Action inputs.
    pub with: BTreeMap<String, Value>,
    /// Step-level environment values.
    pub env: BTreeMap<String, Value>,
    /// Unevaluated `continue-on-error` value.
    pub continue_on_error: Option<Value>,
    /// Optional step timeout.
    pub timeout_minutes: Option<u64>,
}

/// Fail-closed workflow parsing or planning error.
#[derive(Debug, Clone, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WorkflowError {
    /// Stable machine-readable error class.
    pub code: &'static str,
    /// Human-readable diagnostic without workflow secrets.
    pub message: String,
}

impl WorkflowError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Display for WorkflowError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for WorkflowError {}

#[derive(Debug, Clone, Deserialize)]
struct RawWorkflow {
    name: Option<String>,
    #[serde(default)]
    env: BTreeMap<String, Value>,
    defaults: Option<RawDefaults>,
    #[serde(default)]
    jobs: BTreeMap<String, RawJob>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
struct RawJob {
    name: Option<String>,
    needs: Option<StringOrList>,
    runs_on: Option<StringOrList>,
    uses: Option<String>,
    #[serde(rename = "if")]
    condition: Option<ScalarString>,
    strategy: Option<RawStrategy>,
    #[serde(default)]
    env: BTreeMap<String, Value>,
    defaults: Option<RawDefaults>,
    #[serde(default)]
    steps: Vec<RawStep>,
    timeout_minutes: Option<u64>,
    continue_on_error: Option<Value>,
    #[serde(default)]
    outputs: BTreeMap<String, ScalarString>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
struct RawStep {
    id: Option<String>,
    name: Option<String>,
    #[serde(rename = "if")]
    condition: Option<ScalarString>,
    uses: Option<String>,
    run: Option<ScalarString>,
    shell: Option<String>,
    working_directory: Option<String>,
    #[serde(default)]
    with: BTreeMap<String, Value>,
    #[serde(default)]
    env: BTreeMap<String, Value>,
    continue_on_error: Option<Value>,
    timeout_minutes: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawDefaults {
    run: Option<RawRunDefaults>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
struct RawRunDefaults {
    shell: Option<String>,
    working_directory: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "kebab-case")]
struct RawStrategy {
    #[serde(default = "default_true")]
    fail_fast: bool,
    max_parallel: Option<usize>,
    matrix: Option<RawMatrix>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum RawMatrix {
    Static(BTreeMap<String, Value>),
    Dynamic(String),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum StringOrList {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone)]
struct ScalarString(String);

impl<'de> Deserialize<'de> for ScalarString {
    fn deserialize<DeserializerType>(
        deserializer: DeserializerType,
    ) -> Result<Self, DeserializerType::Error>
    where
        DeserializerType: Deserializer<'de>,
    {
        match Value::deserialize(deserializer)? {
            Value::String(value) => Ok(Self(value)),
            Value::Bool(value) => Ok(Self(value.to_string())),
            Value::Number(value) => Ok(Self(value.to_string())),
            Value::Null | Value::Array(_) | Value::Object(_) => Err(
                DeserializerType::Error::custom("expected a scalar string, boolean, or number"),
            ),
        }
    }
}

impl StringOrList {
    fn into_vec(self) -> Vec<String> {
        match self {
            Self::One(value) => vec![value],
            Self::Many(values) => values,
        }
    }
}

/// Parses workflow YAML, validates its static graph, and expands static job matrices.
///
/// Expressions remain opaque strings. A whole-expression dynamic matrix is
/// represented as one deferred job and can only be resolved by the workflow
/// scheduler after its direct `needs` jobs reach a terminal state.
///
/// # Errors
///
/// Returns [`WorkflowError`] for malformed YAML, invalid job/step shapes,
/// unknown dependencies, cycles, or expansion beyond [`MAX_MATRIX_JOBS`].
pub fn plan_workflow(yaml: &str) -> Result<WorkflowPlan, WorkflowError> {
    let workflow: RawWorkflow = serde_yaml::from_str(yaml).map_err(|error| {
        WorkflowError::new("invalid_yaml", format!("workflow YAML is invalid: {error}"))
    })?;

    if workflow.jobs.is_empty() {
        return Err(WorkflowError::new(
            "missing_jobs",
            "workflow must define at least one job",
        ));
    }

    let needs_by_job = validate_jobs(&workflow.jobs)?;
    let job_order = topological_order(&needs_by_job)?;
    let mut instances_by_job: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut jobs = Vec::new();

    for job_id in &job_order {
        let Some(raw_job) = workflow.jobs.get(job_id) else {
            return Err(WorkflowError::new(
                "internal_planner_error",
                format!("validated job {job_id:?} disappeared during planning"),
            ));
        };
        let matrix_values = expand_matrix(job_id, raw_job.strategy.as_ref())?;
        let matrix_expression = deferred_matrix_expression(job_id, raw_job.strategy.as_ref())?;
        let mut job_environment = workflow.env.clone();
        job_environment.extend(raw_job.env.clone());
        let workflow_run_defaults = workflow
            .defaults
            .as_ref()
            .and_then(|defaults| defaults.run.as_ref());
        let job_run_defaults = raw_job
            .defaults
            .as_ref()
            .and_then(|defaults| defaults.run.as_ref());
        let instance_ids = matrix_values
            .iter()
            .enumerate()
            .map(|(index, matrix)| instance_id(job_id, index, matrix_values.len(), matrix))
            .collect::<Vec<_>>();
        let needs = needs_by_job.get(job_id).cloned().unwrap_or_default();
        let needs_instances = needs
            .iter()
            .flat_map(|dependency| {
                instances_by_job
                    .get(dependency)
                    .into_iter()
                    .flatten()
                    .cloned()
            })
            .collect::<Vec<_>>();

        for (index, matrix) in matrix_values.into_iter().enumerate() {
            jobs.push(PlannedJob {
                id: instance_ids[index].clone(),
                base_job_id: job_id.clone(),
                name: instance_name(raw_job.name.as_deref().unwrap_or(job_id), &matrix),
                needs: needs.clone(),
                needs_instances: needs_instances.clone(),
                runs_on: raw_job
                    .runs_on
                    .clone()
                    .map(StringOrList::into_vec)
                    .unwrap_or_default(),
                reusable_workflow: raw_job.uses.clone(),
                condition: raw_job
                    .condition
                    .as_ref()
                    .map(|condition| condition.0.clone()),
                matrix,
                matrix_expression: matrix_expression.clone(),
                env: job_environment.clone(),
                steps: raw_job
                    .steps
                    .iter()
                    .enumerate()
                    .map(|(step_index, step)| PlannedStep {
                        index: step_index,
                        id: step.id.clone(),
                        name: step.name.clone(),
                        condition: step.condition.as_ref().map(|condition| condition.0.clone()),
                        uses: step.uses.clone(),
                        run: step.run.as_ref().map(|run| run.0.clone()),
                        shell: step
                            .shell
                            .clone()
                            .or_else(|| {
                                job_run_defaults.and_then(|defaults| defaults.shell.clone())
                            })
                            .or_else(|| {
                                workflow_run_defaults.and_then(|defaults| defaults.shell.clone())
                            }),
                        working_directory: step
                            .working_directory
                            .clone()
                            .or_else(|| {
                                job_run_defaults
                                    .and_then(|defaults| defaults.working_directory.clone())
                            })
                            .or_else(|| {
                                workflow_run_defaults
                                    .and_then(|defaults| defaults.working_directory.clone())
                            }),
                        with: step.with.clone(),
                        env: step.env.clone(),
                        continue_on_error: step.continue_on_error.clone(),
                        timeout_minutes: step.timeout_minutes,
                    })
                    .collect(),
                fail_fast: raw_job
                    .strategy
                    .as_ref()
                    .is_none_or(|strategy| strategy.fail_fast),
                max_parallel: raw_job
                    .strategy
                    .as_ref()
                    .and_then(|strategy| strategy.max_parallel),
                timeout_minutes: raw_job.timeout_minutes,
                continue_on_error: raw_job.continue_on_error.clone(),
                outputs: raw_job
                    .outputs
                    .iter()
                    .map(|(name, value)| (name.clone(), value.0.clone()))
                    .collect(),
            });
        }

        instances_by_job.insert(job_id.clone(), instance_ids);
    }

    Ok(WorkflowPlan {
        schema_version: "gha-indie-worker.plan.v2",
        name: workflow.name,
        job_order,
        jobs,
    })
}

fn validate_jobs(
    jobs: &BTreeMap<String, RawJob>,
) -> Result<BTreeMap<String, Vec<String>>, WorkflowError> {
    let mut needs_by_job = BTreeMap::new();

    for (job_id, job) in jobs {
        if !valid_identifier(job_id) {
            return Err(WorkflowError::new(
                "invalid_job_id",
                format!("job identifier {job_id:?} is not GitHub Actions compatible"),
            ));
        }

        let reusable = job.uses.is_some();
        if reusable {
            if job.runs_on.is_some() || !job.steps.is_empty() {
                return Err(WorkflowError::new(
                    "invalid_reusable_job",
                    format!(
                        "job {job_id:?} uses a reusable workflow and cannot also define runs-on or steps"
                    ),
                ));
            }
        } else {
            let runners = job
                .runs_on
                .clone()
                .map(StringOrList::into_vec)
                .unwrap_or_default();
            if runners.is_empty() || runners.iter().any(|runner| runner.trim().is_empty()) {
                return Err(WorkflowError::new(
                    "missing_runner",
                    format!("job {job_id:?} must define a non-empty runs-on value"),
                ));
            }
            if job.steps.is_empty() {
                return Err(WorkflowError::new(
                    "missing_steps",
                    format!("job {job_id:?} must define at least one step"),
                ));
            }
        }

        for (index, step) in job.steps.iter().enumerate() {
            if step.id.as_deref().is_some_and(|id| !valid_identifier(id)) {
                return Err(WorkflowError::new(
                    "invalid_step_id",
                    format!("step {index} in job {job_id:?} has an invalid id"),
                ));
            }
            if step.run.is_some() == step.uses.is_some() {
                return Err(WorkflowError::new(
                    "invalid_step",
                    format!(
                        "step {index} in job {job_id:?} must define exactly one of run or uses"
                    ),
                ));
            }
        }

        for output_name in job.outputs.keys() {
            if !valid_identifier(output_name) {
                return Err(WorkflowError::new(
                    "invalid_job_output",
                    format!("job {job_id:?} has invalid output name {output_name:?}"),
                ));
            }
        }

        let raw_needs = job
            .needs
            .clone()
            .map(StringOrList::into_vec)
            .unwrap_or_default();
        let mut seen = BTreeSet::new();
        let mut needs = Vec::new();
        for dependency in raw_needs {
            if !jobs.contains_key(&dependency) {
                return Err(WorkflowError::new(
                    "unknown_dependency",
                    format!("job {job_id:?} needs unknown job {dependency:?}"),
                ));
            }
            if seen.insert(dependency.clone()) {
                needs.push(dependency);
            }
        }
        needs_by_job.insert(job_id.clone(), needs);
    }

    Ok(needs_by_job)
}

fn topological_order(
    needs_by_job: &BTreeMap<String, Vec<String>>,
) -> Result<Vec<String>, WorkflowError> {
    let mut indegree = needs_by_job
        .iter()
        .map(|(job, needs)| (job.clone(), needs.len()))
        .collect::<BTreeMap<_, _>>();
    let mut dependents: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for (job, needs) in needs_by_job {
        for dependency in needs {
            dependents
                .entry(dependency.clone())
                .or_default()
                .push(job.clone());
        }
    }
    for values in dependents.values_mut() {
        values.sort();
        values.dedup();
    }

    let mut ready = indegree
        .iter()
        .filter_map(|(job, count)| (*count == 0).then_some(job.clone()))
        .collect::<BTreeSet<_>>();
    let mut order = Vec::with_capacity(needs_by_job.len());

    while let Some(job) = ready.iter().next().cloned() {
        ready.remove(&job);
        order.push(job.clone());
        if let Some(children) = dependents.get(&job) {
            for child in children {
                let Some(count) = indegree.get_mut(child) else {
                    continue;
                };
                *count = count.saturating_sub(1);
                if *count == 0 {
                    ready.insert(child.clone());
                }
            }
        }
    }

    if order.len() != needs_by_job.len() {
        let cycle_jobs = indegree
            .into_iter()
            .filter_map(|(job, count)| (count > 0).then_some(job))
            .collect::<Vec<_>>();
        return Err(WorkflowError::new(
            "dependency_cycle",
            format!(
                "workflow job dependency cycle includes: {}",
                cycle_jobs.join(", ")
            ),
        ));
    }

    Ok(order)
}

fn expand_matrix(
    job_id: &str,
    strategy: Option<&RawStrategy>,
) -> Result<Vec<BTreeMap<String, Value>>, WorkflowError> {
    let Some(strategy) = strategy else {
        return Ok(vec![BTreeMap::new()]);
    };
    if strategy.max_parallel == Some(0) {
        return Err(matrix_error(
            job_id,
            "max-parallel must be greater than zero",
        ));
    }
    let Some(matrix) = strategy.matrix.as_ref() else {
        return Ok(vec![BTreeMap::new()]);
    };
    let RawMatrix::Static(matrix) = matrix else {
        return Ok(vec![BTreeMap::new()]);
    };
    expand_matrix_mapping(job_id, matrix)
}

fn deferred_matrix_expression(
    job_id: &str,
    strategy: Option<&RawStrategy>,
) -> Result<Option<String>, WorkflowError> {
    let Some(RawMatrix::Dynamic(source)) = strategy.and_then(|value| value.matrix.as_ref()) else {
        return Ok(None);
    };
    let trimmed = source.trim();
    if !trimmed.starts_with("${{") || !trimmed.ends_with("}}") {
        return Err(matrix_error(
            job_id,
            "dynamic matrix must be one whole expression",
        ));
    }
    Ok(Some(source.clone()))
}

#[cfg(feature = "linux-runner")]
pub(crate) fn expand_dynamic_matrix(
    job_id: &str,
    value: &Value,
) -> Result<Vec<BTreeMap<String, Value>>, WorkflowError> {
    let Some(object) = value.as_object() else {
        return Err(matrix_error(
            job_id,
            "dynamic matrix expression must resolve to an object",
        ));
    };
    let matrix = object
        .iter()
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect::<BTreeMap<_, _>>();
    expand_matrix_mapping(job_id, &matrix)
}

#[cfg(feature = "linux-runner")]
pub(crate) fn materialize_dynamic_jobs(
    template: &PlannedJob,
    matrices: Vec<BTreeMap<String, Value>>,
) -> Vec<PlannedJob> {
    let total = matrices.len();
    matrices
        .into_iter()
        .enumerate()
        .map(|(index, matrix)| {
            let mut job = template.clone();
            job.id = instance_id(&template.base_job_id, index, total, &matrix);
            job.name = instance_name(&template.name, &matrix);
            job.matrix = matrix;
            job.matrix_expression = None;
            job
        })
        .collect()
}

fn expand_matrix_mapping(
    job_id: &str,
    matrix: &BTreeMap<String, Value>,
) -> Result<Vec<BTreeMap<String, Value>>, WorkflowError> {
    if matrix.is_empty() {
        return Ok(vec![BTreeMap::new()]);
    }
    let includes = matrix_object_list(job_id, "include", matrix.get("include"))?;
    let excludes = matrix_object_list(job_id, "exclude", matrix.get("exclude"))?;
    let axes = matrix
        .iter()
        .filter(|(key, _)| key.as_str() != "include" && key.as_str() != "exclude")
        .map(|(key, value)| {
            let Some(values) = value.as_array() else {
                return Err(matrix_error(
                    job_id,
                    format!(
                        "matrix axis {key:?} must be a static YAML sequence; dynamic expressions are not evaluated"
                    ),
                ));
            };
            if values.is_empty() {
                return Err(matrix_error(
                    job_id,
                    format!("matrix axis {key:?} cannot be empty"),
                ));
            }
            Ok((key.clone(), values.clone()))
        })
        .collect::<Result<Vec<_>, WorkflowError>>()?;

    if axes.is_empty() {
        if includes.is_empty() {
            return Err(matrix_error(
                job_id,
                "matrix must define at least one axis or include entry",
            ));
        }
        if includes.len() > MAX_MATRIX_JOBS {
            return Err(matrix_too_large(job_id, includes.len()));
        }
        return Ok(includes);
    }

    let mut combinations = vec![BTreeMap::new()];
    for (axis, values) in &axes {
        let projected = combinations
            .len()
            .checked_mul(values.len())
            .ok_or_else(|| matrix_too_large(job_id, usize::MAX))?;
        if projected > MAX_MATRIX_JOBS {
            return Err(matrix_too_large(job_id, projected));
        }
        let mut next = Vec::with_capacity(projected);
        for combination in &combinations {
            for value in values {
                let mut expanded = combination.clone();
                expanded.insert(axis.clone(), value.clone());
                next.push(expanded);
            }
        }
        combinations = next;
    }

    combinations.retain(|combination| {
        !excludes
            .iter()
            .any(|exclusion| object_is_subset(exclusion, combination))
    });
    let originals = combinations.clone();

    for inclusion in includes {
        let mut applied = false;
        for (index, original) in originals.iter().enumerate() {
            if include_is_compatible(&inclusion, original, &axes) {
                for (key, value) in &inclusion {
                    combinations[index].insert(key.clone(), value.clone());
                }
                applied = true;
            }
        }
        if !applied && !combinations.contains(&inclusion) {
            combinations.push(inclusion);
        }
        if combinations.len() > MAX_MATRIX_JOBS {
            return Err(matrix_too_large(job_id, combinations.len()));
        }
    }

    if combinations.is_empty() {
        return Err(matrix_error(
            job_id,
            "matrix exclusion removed every concrete job",
        ));
    }
    Ok(combinations)
}

fn matrix_object_list(
    job_id: &str,
    key: &str,
    value: Option<&Value>,
) -> Result<Vec<BTreeMap<String, Value>>, WorkflowError> {
    let Some(value) = value else {
        return Ok(Vec::new());
    };
    let Some(entries) = value.as_array() else {
        return Err(matrix_error(
            job_id,
            format!("matrix {key} must be a YAML sequence of objects"),
        ));
    };

    entries
        .iter()
        .enumerate()
        .map(|(index, entry)| {
            let Some(object) = entry.as_object() else {
                return Err(matrix_error(
                    job_id,
                    format!("matrix {key} entry {index} must be an object"),
                ));
            };
            Ok(object
                .iter()
                .map(|(name, value)| (name.clone(), value.clone()))
                .collect())
        })
        .collect()
}

fn include_is_compatible(
    inclusion: &BTreeMap<String, Value>,
    original: &BTreeMap<String, Value>,
    axes: &[(String, Vec<Value>)],
) -> bool {
    axes.iter().all(|(axis, _)| {
        inclusion
            .get(axis)
            .is_none_or(|included| original.get(axis) == Some(included))
    })
}

fn object_is_subset(expected: &BTreeMap<String, Value>, actual: &BTreeMap<String, Value>) -> bool {
    expected
        .iter()
        .all(|(key, value)| actual.get(key) == Some(value))
}

fn matrix_error(job_id: &str, message: impl Into<String>) -> WorkflowError {
    WorkflowError::new(
        "invalid_matrix",
        format!("job {job_id:?}: {}", message.into()),
    )
}

fn matrix_too_large(job_id: &str, expanded: usize) -> WorkflowError {
    WorkflowError::new(
        "matrix_too_large",
        format!("job {job_id:?} expands to {expanded} instances; maximum is {MAX_MATRIX_JOBS}"),
    )
}

fn instance_id(
    job_id: &str,
    index: usize,
    total: usize,
    matrix: &BTreeMap<String, Value>,
) -> String {
    if total == 1 && matrix.is_empty() {
        job_id.to_owned()
    } else {
        format!("{job_id}[{}]", index + 1)
    }
}

fn instance_name(base_name: &str, matrix: &BTreeMap<String, Value>) -> String {
    if matrix.is_empty() {
        return base_name.to_owned();
    }
    let values = matrix
        .iter()
        .map(|(key, value)| format!("{key}={}", compact_value(value)))
        .collect::<Vec<_>>()
        .join(", ");
    format!("{base_name} ({values})")
}

fn compact_value(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        _ => serde_json::to_string(value).unwrap_or_else(|_| "<value>".to_owned()),
    }
}

fn valid_identifier(value: &str) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    (first.is_ascii_alphabetic() || first == '_')
        && characters
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-'))
}

const fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn orders_dependencies_and_expands_static_matrix() {
        let yaml = r#"
name: CI
jobs:
  build:
    name: Build
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      max-parallel: 2
      matrix:
        os: [ubuntu-latest, windows-latest]
        rust: [stable, beta]
        exclude:
          - os: windows-latest
            rust: beta
        include:
          - os: ubuntu-latest
            experimental: true
          - os: macos-latest
            rust: stable
    steps:
      - uses: actions/checkout@v4
      - name: Test
        run: cargo test
  report:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - run: echo done
"#;

        let plan = plan_workflow(yaml).unwrap_or_else(|error| panic!("plan failed: {error}"));
        assert_eq!(plan.job_order, vec!["build", "report"]);
        let builds = plan
            .jobs
            .iter()
            .filter(|job| job.base_job_id == "build")
            .collect::<Vec<_>>();
        assert_eq!(builds.len(), 4);
        assert!(builds.iter().all(|job| !job.fail_fast));
        assert!(builds.iter().all(|job| job.max_parallel == Some(2)));
        assert_eq!(
            builds
                .iter()
                .filter(|job| job.matrix.get("experimental") == Some(&Value::Bool(true)))
                .count(),
            2
        );
        let report = plan
            .jobs
            .iter()
            .find(|job| job.base_job_id == "report")
            .unwrap_or_else(|| panic!("report job missing"));
        assert_eq!(report.needs_instances.len(), 4);
    }

    #[test]
    fn accepts_reusable_workflow_job() {
        let yaml = r#"
jobs:
  delegate:
    uses: owner/repository/.github/workflows/reusable.yml@main
"#;
        let plan = plan_workflow(yaml).unwrap_or_else(|error| panic!("plan failed: {error}"));
        assert_eq!(plan.jobs.len(), 1);
        assert_eq!(
            plan.jobs[0].reusable_workflow.as_deref(),
            Some("owner/repository/.github/workflows/reusable.yml@main")
        );
        assert!(plan.jobs[0].runs_on.is_empty());
        assert!(plan.jobs[0].steps.is_empty());
    }

    #[test]
    fn applies_workflow_and_job_environment_and_run_defaults() {
        let yaml = r#"
env:
  SHARED: workflow
  WORKFLOW_ONLY: present
defaults:
  run:
    shell: sh
    working-directory: workflow-dir
jobs:
  test:
    runs-on: ubuntu-latest
    env:
      SHARED: job
      JOB_ONLY: present
    defaults:
      run:
        working-directory: job-dir
    steps:
      - run: echo inherited
      - shell: bash
        working-directory: step-dir
        run: echo explicit
"#;

        let plan = plan_workflow(yaml).unwrap_or_else(|error| panic!("plan failed: {error}"));
        let job = &plan.jobs[0];
        assert_eq!(
            job.env.get("SHARED"),
            Some(&Value::String("job".to_string()))
        );
        assert_eq!(
            job.env.get("WORKFLOW_ONLY"),
            Some(&Value::String("present".to_string()))
        );
        assert_eq!(
            job.env.get("JOB_ONLY"),
            Some(&Value::String("present".to_string()))
        );
        assert_eq!(job.steps[0].shell.as_deref(), Some("sh"));
        assert_eq!(job.steps[0].working_directory.as_deref(), Some("job-dir"));
        assert_eq!(job.steps[1].shell.as_deref(), Some("bash"));
        assert_eq!(job.steps[1].working_directory.as_deref(), Some("step-dir"));
    }

    #[test]
    fn normalizes_boolean_and_numeric_condition_and_run_scalars() {
        let yaml = r#"
jobs:
  test:
    if: true
    runs-on: ubuntu-latest
    steps:
      - if: false
        run: false
      - if: 1
        run: 42
"#;
        let plan = plan_workflow(yaml).unwrap_or_else(|error| panic!("plan failed: {error}"));
        let job = &plan.jobs[0];
        assert_eq!(job.condition.as_deref(), Some("true"));
        assert_eq!(job.steps[0].condition.as_deref(), Some("false"));
        assert_eq!(job.steps[0].run.as_deref(), Some("false"));
        assert_eq!(job.steps[1].condition.as_deref(), Some("1"));
        assert_eq!(job.steps[1].run.as_deref(), Some("42"));
    }

    #[test]
    fn rejects_unknown_dependencies_and_cycles() {
        let unknown = r#"
jobs:
  test:
    needs: missing
    runs-on: ubuntu-latest
    steps:
      - run: "true"
"#;
        assert_eq!(
            plan_workflow(unknown).map_err(|error| error.code),
            Err("unknown_dependency")
        );

        let cycle = r#"
jobs:
  first:
    needs: second
    runs-on: ubuntu-latest
    steps:
      - run: "true"
  second:
    needs: first
    runs-on: ubuntu-latest
    steps:
      - run: "true"
"#;
        assert_eq!(
            plan_workflow(cycle).map_err(|error| error.code),
            Err("dependency_cycle")
        );
    }

    #[test]
    fn rejects_ambiguous_steps_and_dynamic_matrix_axes() {
        let ambiguous = r#"
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        run: echo unsafe
"#;
        assert_eq!(
            plan_workflow(ambiguous).map_err(|error| error.code),
            Err("invalid_step")
        );

        let dynamic = r#"
jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        shard: ${{ fromJSON(inputs.shards) }}
    steps:
      - run: echo test
"#;
        assert_eq!(
            plan_workflow(dynamic).map_err(|error| error.code),
            Err("invalid_matrix")
        );
    }

    #[test]
    fn preserves_job_outputs_and_whole_expression_dynamic_matrix() {
        let yaml = r#"
jobs:
  define:
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.values.outputs.matrix }}
    steps:
      - id: values
        run: |
          echo 'matrix={"color":["red","green"]}' >> "$GITHUB_OUTPUT"
  consume:
    needs: define
    runs-on: ubuntu-latest
    strategy:
      matrix: ${{ fromJSON(needs.define.outputs.matrix) }}
    steps:
      - run: echo "${{ matrix.color }}"
"#;
        let plan = plan_workflow(yaml).unwrap_or_else(|error| panic!("plan failed: {error}"));
        assert_eq!(plan.schema_version, "gha-indie-worker.plan.v2");
        assert_eq!(plan.jobs.len(), 2);
        assert_eq!(
            plan.jobs[0].outputs.get("matrix").map(String::as_str),
            Some("${{ steps.values.outputs.matrix }}")
        );
        assert_eq!(
            plan.jobs[1].matrix_expression.as_deref(),
            Some("${{ fromJSON(needs.define.outputs.matrix) }}")
        );
        assert!(plan.jobs[1].matrix.is_empty());
    }

    #[test]
    fn matrix_limit_is_enforced_before_allocation() {
        let values = (0..257)
            .map(|value| value.to_string())
            .collect::<Vec<_>>()
            .join(", ");
        let yaml = format!(
            "jobs:\n  test:\n    runs-on: ubuntu-latest\n    strategy:\n      matrix:\n        shard: [{values}]\n    steps:\n      - run: echo test\n"
        );
        assert_eq!(
            plan_workflow(&yaml).map_err(|error| error.code),
            Err("matrix_too_large")
        );
    }
}
