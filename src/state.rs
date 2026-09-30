use std::{
    collections::{HashMap, HashSet},
    sync::{atomic::AtomicU64, Arc},
};

use tokio::sync::{RwLock, Semaphore};

use crate::config::Config;
use crate::types::BuildJobRecord;

pub(crate) const SERVICE_NAME: &str = "dd-build-server";
pub(crate) const DEFAULT_PORT: u16 = 8100;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct InstallationCacheEntry {
    pub(crate) installation_id: u64,
    pub(crate) expires_at_ms: u64,
}

#[derive(Clone)]
pub(crate) struct AppState {
    pub(crate) config: Arc<Config>,
    pub(crate) http: reqwest::Client,
    pub(crate) jobs: Arc<RwLock<HashMap<String, BuildJobRecord>>>,
    pub(crate) semaphore: Arc<Semaphore>,
    pub(crate) counters: Arc<Counters>,
    /// Optional Postgres persistence (own database `dd_build_server` on RDS).
    pub(crate) db: Option<sea_orm::DatabaseConnection>,
    /// Optional NATS client for lifecycle events and request intake.
    pub(crate) nats: Option<async_nats::Client>,
    /// Stable per-process holder identity for fiducia locks/leases.
    pub(crate) holder: String,
    /// Local dedupe of NATS/webhook requestIds (fiducia + JetStream Nats-Msg-Id
    /// are the distributed guards; this catches quick same-process redelivery).
    pub(crate) recent_request_ids: Arc<RwLock<HashSet<String>>>,
    /// Bounded-TTL metadata cache for a GitHub App's repository installation.
    /// This contains only numeric installation ids; installation access tokens,
    /// JWTs and private-key material are deliberately never retained here.
    pub(crate) installation_cache: Arc<RwLock<HashMap<String, InstallationCacheEntry>>>,
}

#[derive(Default)]
pub(crate) struct Counters {
    pub(crate) submitted: AtomicU64,
    pub(crate) running: AtomicU64,
    pub(crate) succeeded: AtomicU64,
    pub(crate) failed: AtomicU64,
    pub(crate) rejected: AtomicU64,
    pub(crate) command_failures: AtomicU64,
    pub(crate) ecr_logins: AtomicU64,
    pub(crate) ecr_login_failures: AtomicU64,
    pub(crate) locks_acquired: AtomicU64,
    pub(crate) lock_failures: AtomicU64,
    pub(crate) webhooks_received: AtomicU64,
    pub(crate) webhooks_rejected: AtomicU64,
    pub(crate) nats_published: AtomicU64,
    pub(crate) nats_publish_failures: AtomicU64,
    pub(crate) gh_secrets_synced: AtomicU64,
    pub(crate) gh_secret_sync_failures: AtomicU64,
    /// Number of authoritative report intents that have not reached a proven
    /// terminal App Check Run state. A non-zero value makes authoritative
    /// readiness fail closed.
    pub(crate) unresolved_report_intents: AtomicU64,
}