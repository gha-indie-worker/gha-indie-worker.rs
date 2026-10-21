use sea_orm::entity::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, DeriveEntityModel, Serialize, Deserialize)]
#[sea_orm(table_name = "gha_recovery_runs")]
pub struct Model {
    #[sea_orm(primary_key)]
    pub id: i64,
    pub provider: String,
    pub repo: String,
    pub run_id: i64,
    pub run_attempt: i64,
    pub head_sha: String,
    pub classification: String,
    pub confidence: String,
    pub fallback_allowed: bool,
    pub jobs_observed: i32,
    pub steps_observed: i32,
    pub runner_assigned: bool,
    pub verified_diagnostic_kind: Option<String>,
    pub verified_evidence_ref: Option<String>,
    pub verified_at: Option<DateTimeWithTimeZone>,
    pub reason: Option<String>,
    pub state: String,
    pub claim_owner: Option<String>,
    pub fencing_token: Option<i64>,
    pub claimed_at: Option<DateTimeWithTimeZone>,
    pub created_at: DateTimeWithTimeZone,
    pub updated_at: DateTimeWithTimeZone,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {}

impl ActiveModelBehavior for ActiveModel {}
