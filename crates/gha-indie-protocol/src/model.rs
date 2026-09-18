use std::collections::BTreeMap;
use std::error::Error;
use std::fmt::{Display, Formatter};

use serde::{Deserialize, Serialize};
use serde_json::Value;

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
    pub(crate) fn new(code: &'static str, message: impl Into<String>) -> Self {
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
