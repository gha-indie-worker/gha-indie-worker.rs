//! Fail-closed protocol between the workflow planner and fixed-profile workers.
//!
//! Workflow YAML may describe bounded matrices and dependency order, but it
//! never becomes executable shell or arbitrary marketplace actions here.
//! Every concrete job is bound to an exact repository commit and one reviewed,
//! digest-pinned worker profile before it can enter durable assignment.

mod bind;
mod digest;
mod model;
mod validate;

pub use bind::bind_plan;
pub use digest::{profile_catalog_digest, workflow_plan_digest};
pub use model::{
    BindingDocument, DispatchBatch, DispatchRequest, JobBinding, PlannedJob, PlannedStep,
    ProfileCatalog, ProfileRecord, ProtocolError, RunnerTarget, WorkflowPlan,
};
pub use validate::{
    validate_commit_sha, validate_context_dir, validate_digest, validate_repository_url,
};

pub const PLAN_SCHEMA: &str = "gha-indie-worker.plan.v2";
pub const PROFILE_CATALOG_SCHEMA: &str = "gha-indie-worker.profile-catalog.v2";
pub const BINDINGS_SCHEMA: &str = "gha-indie-worker.bindings.v1";
pub const DISPATCH_BATCH_SCHEMA: &str = "gha-indie-worker.dispatch-batch.v2";
pub const DISPATCH_SCHEMA: &str = "gha-indie-worker.dispatch.v2";

pub const MAX_PLAN_JOBS: usize = 1_024;
pub const MAX_BASE_JOBS: usize = 256;
pub const MAX_PROFILES: usize = 256;
pub const MAX_PROFILE_CAPABILITIES: usize = 32;
pub const MAX_DEPENDENCIES: usize = 1_024;
pub const MAX_MATRIX_KEYS: usize = 32;
pub const MAX_MATRIX_JSON_BYTES: usize = 16 * 1_024;
pub const MAX_IDENTIFIER_BYTES: usize = 128;
pub const MAX_PROFILE_NAME_BYTES: usize = 64;
pub const MAX_CAPABILITY_NAME_BYTES: usize = 64;
pub const MAX_CONTEXT_DIR_BYTES: usize = 240;
pub const MAX_REPOSITORY_URL_BYTES: usize = 2_048;

pub(crate) const ALLOWED_RUNNER_LABELS: &[&str] = &[
    "self-hosted",
    "gha-indie-worker",
    "linux",
    "macos",
    "windows",
    "x64",
    "arm64",
];
pub(crate) const ALLOWED_RUNNER_PLATFORMS: &[&str] = &["linux", "macos", "windows"];
pub(crate) const ALLOWED_RUNNER_ARCHITECTURES: &[&str] = &["x64", "arm64"];

#[cfg(test)]
mod tests;
