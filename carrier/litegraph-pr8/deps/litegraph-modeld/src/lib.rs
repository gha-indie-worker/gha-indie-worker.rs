use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, VecDeque},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::{
    sync::{mpsc, oneshot, RwLock},
    time,
};

const MAX_MODEL_BYTES: u64 = 512 * 1024 * 1024 * 1024;
const MAX_BATCH: usize = 4096;
const MAX_QUEUE: usize = 65_536;
const MAX_INPUT_BYTES: usize = 256 * 1024 * 1024;
const MAX_BATCH_DELAY_US: u64 = 1_000_000;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ModelManifest {
    pub name: String,
    pub version: String,
    pub digest: String,
    pub weight_bytes: u64,
    pub workspace_bytes: u64,
    pub max_batch: usize,
    pub max_delay_us: u64,
    pub max_input_bytes: usize,
    pub queue_capacity: usize,
    pub allow_cross_tenant_batching: bool,
}

impl ModelManifest {
    pub fn key(&self) -> String {
        format!("{}:{}", self.name, self.version)
    }

    pub fn validate(&self) -> Result<(), ModelError> {
        validate_token(&self.name, "name")?;
        validate_token(&self.version, "version")?;
        validate_digest(&self.digest)?;
        if self.weight_bytes == 0 || self.weight_bytes > MAX_MODEL_BYTES {
            return Err(ModelError::InvalidManifest(
                "weight_bytes out of range".into(),
            ));
        }
        if self.workspace_bytes > MAX_MODEL_BYTES
            || self.weight_bytes.saturating_add(self.workspace_bytes) > MAX_MODEL_BYTES
        {
            return Err(ModelError::InvalidManifest(
                "model + workspace exceed maximum resident bytes".into(),
            ));
        }
        if !(1..=MAX_BATCH).contains(&self.max_batch) {
            return Err(ModelError::InvalidManifest("max_batch out of range".into()));
        }
        if self.max_delay_us > MAX_BATCH_DELAY_US {
            return Err(ModelError::InvalidManifest(
                "max_delay_us out of range".into(),
            ));
        }
        if !(1..=MAX_INPUT_BYTES).contains(&self.max_input_bytes) {
            return Err(ModelError::InvalidManifest(
                "max_input_bytes out of range".into(),
            ));
        }
        if !(1..=MAX_QUEUE).contains(&self.queue_capacity) {
            return Err(ModelError::InvalidManifest(
                "queue_capacity out of range".into(),
            ));
        }
        Ok(())
    }
}

fn validate_token(value: &str, field: &str) -> Result<(), ModelError> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    {
        return Err(ModelError::InvalidManifest(format!(
            "invalid {field}: {value:?}"
        )));
    }
    Ok(())
}

fn validate_digest(value: &str) -> Result<(), ModelError> {
    if value.len() != 71
        || !value.starts_with("sha256:")
        || !value[7..]
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(ModelError::InvalidManifest(
            "digest must be lowercase sha256:<64 hex>".into(),
        ));
    }
    Ok(())
}

#[derive(Debug, thiserror::Error, Clone)]
pub enum ModelError {
    #[error("invalid model manifest: {0}")]
    InvalidManifest(String),
    #[error("model version conflicts with already resident digest: {0}")]
    Conflict(String),
    #[error("model not found: {0}")]
    NotFound(String),
    #[error("model handle revoked")]
    Revoked,
    #[error("model actor stopped")]
    Stopped,
    #[error("input exceeds model limit: max={max} actual={actual}")]
    InputTooLarge { max: usize, actual: usize },
    #[error("invalid batch key")]
    InvalidBatchKey,
    #[error("execution failed: {0}")]
    Execution(String),
}

#[async_trait]
pub trait BatchExecutor: Send + Sync + 'static {
    async fn execute_batch(
        &self,
        model: &ModelManifest,
        inputs: Vec<Vec<u8>>,
    ) -> Vec<Result<Vec<u8>, ModelError>>;
}

struct Request {
    invocation_id: String,
    tenant_id: String,
    batch_key: String,
    input: Vec<u8>,
    reply: oneshot::Sender<Result<Vec<u8>, ModelError>>,
}

#[derive(Clone)]
pub struct ModelHandle {
    manifest: ModelManifest,
    tx: mpsc::Sender<Request>,
    revoked: Arc<AtomicBool>,
}

impl ModelHandle {
    pub fn manifest(&self) -> &ModelManifest {
        &self.manifest
    }

    /// Safe default: each invocation gets a unique batch key, so independent calls are
    /// not coalesced unless the caller opts into a compatibility class explicitly.
    pub async fn infer(
        &self,
        invocation_id: impl Into<String>,
        tenant_id: impl Into<String>,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, ModelError> {
        let invocation_id = invocation_id.into();
        let tenant_id = tenant_id.into();
        let batch_key = format!("invocation-{invocation_id}");
        self.infer_batched(invocation_id, tenant_id, batch_key, input)
            .await
    }

    pub async fn infer_batched(
        &self,
        invocation_id: impl Into<String>,
        tenant_id: impl Into<String>,
        batch_key: impl Into<String>,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, ModelError> {
        if self.revoked.load(Ordering::Acquire) {
            return Err(ModelError::Revoked);
        }
        if input.len() > self.manifest.max_input_bytes {
            return Err(ModelError::InputTooLarge {
                max: self.manifest.max_input_bytes,
                actual: input.len(),
            });
        }
        let invocation_id = invocation_id.into();
        let tenant_id = tenant_id.into();
        let batch_key = batch_key.into();
        if invocation_id.is_empty()
            || invocation_id.len() > 256
            || invocation_id.bytes().any(|b| b.is_ascii_control())
            || tenant_id.is_empty()
            || tenant_id.len() > 256
            || tenant_id.bytes().any(|b| b.is_ascii_control())
            || batch_key.is_empty()
            || batch_key.len() > 256
            || batch_key.bytes().any(|b| b.is_ascii_control())
        {
            return Err(ModelError::InvalidBatchKey);
        }

        let (reply, rx) = oneshot::channel();
        self.tx
            .send(Request {
                invocation_id,
                tenant_id,
                batch_key,
                input,
                reply,
            })
            .await
            .map_err(|_| ModelError::Stopped)?;
        rx.await.map_err(|_| ModelError::Stopped)?
    }
}

pub struct ModelDaemon<E: BatchExecutor> {
    executor: Arc<E>,
    models: Arc<RwLock<HashMap<String, ModelHandle>>>,
}

impl<E: BatchExecutor> ModelDaemon<E> {
    pub fn new(executor: E) -> Self {
        Self {
            executor: Arc::new(executor),
            models: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn load(&self, manifest: ModelManifest) -> Result<ModelHandle, ModelError> {
        manifest.validate()?;
        let key = manifest.key();
        let mut models = self.models.write().await;
        if let Some(existing) = models.get(&key) {
            if existing.manifest == manifest {
                return Ok(existing.clone());
            }
            return Err(ModelError::Conflict(key));
        }

        let (tx, rx) = mpsc::channel(manifest.queue_capacity);
        let revoked = Arc::new(AtomicBool::new(false));
        let handle = ModelHandle {
            manifest: manifest.clone(),
            tx,
            revoked: revoked.clone(),
        };
        models.insert(key, handle.clone());
        tokio::spawn(run_model_actor(
            manifest,
            self.executor.clone(),
            rx,
            revoked,
        ));
        Ok(handle)
    }

    pub async fn get(&self, key: &str) -> Result<ModelHandle, ModelError> {
        self.models
            .read()
            .await
            .get(key)
            .cloned()
            .ok_or_else(|| ModelError::NotFound(key.into()))
    }

    pub async fn unload(&self, key: &str) -> bool {
        let removed = self.models.write().await.remove(key);
        if let Some(handle) = removed {
            handle.revoked.store(true, Ordering::Release);
            true
        } else {
            false
        }
    }

    pub async fn resident_models(&self) -> Vec<ModelManifest> {
        let mut models: Vec<_> = self
            .models
            .read()
            .await
            .values()
            .map(|handle| handle.manifest.clone())
            .collect();
        models.sort_by_key(ModelManifest::key);
        models
    }
}

fn compatible(manifest: &ModelManifest, first: &Request, next: &Request) -> bool {
    first.batch_key == next.batch_key
        && (manifest.allow_cross_tenant_batching || first.tenant_id == next.tenant_id)
}

async fn run_model_actor<E: BatchExecutor>(
    manifest: ModelManifest,
    executor: Arc<E>,
    mut rx: mpsc::Receiver<Request>,
    revoked: Arc<AtomicBool>,
) {
    let mut backlog = VecDeque::new();

    loop {
        backlog.retain(|request: &Request| !request.reply.is_closed());

        let first = if let Some(request) = backlog.pop_front() {
            request
        } else {
            match rx.recv().await {
                Some(request) => request,
                None => break,
            }
        };

        if first.reply.is_closed() {
            continue;
        }

        if revoked.load(Ordering::Acquire) {
            let _ = first.reply.send(Err(ModelError::Revoked));
            while let Some(request) = backlog.pop_front() {
                let _ = request.reply.send(Err(ModelError::Revoked));
            }
            while let Some(request) = rx.recv().await {
                let _ = request.reply.send(Err(ModelError::Revoked));
            }
            break;
        }

        let mut batch = vec![first];
        let deadline = time::Instant::now() + Duration::from_micros(manifest.max_delay_us);

        while batch.len() < manifest.max_batch {
            if let Some(position) = backlog
                .iter()
                .position(|request| compatible(&manifest, &batch[0], request))
            {
                if let Some(request) = backlog.remove(position) {
                    batch.push(request);
                    continue;
                }
            }

            if time::Instant::now() >= deadline || backlog.len() >= manifest.queue_capacity {
                break;
            }

            match time::timeout_at(deadline, rx.recv()).await {
                Ok(Some(request)) if request.reply.is_closed() => {}
                Ok(Some(request)) if compatible(&manifest, &batch[0], &request) => {
                    batch.push(request)
                }
                Ok(Some(request)) => backlog.push_back(request),
                _ => break,
            }
        }

        batch.retain(|request| !request.reply.is_closed());
        if batch.is_empty() {
            continue;
        }

        let inputs = batch
            .iter_mut()
            .map(|request| std::mem::take(&mut request.input))
            .collect();
        let outputs = executor.execute_batch(&manifest, inputs).await;
        if outputs.len() != batch.len() {
            let error = ModelError::Execution(format!(
                "executor result cardinality mismatch: expected {} got {}",
                batch.len(),
                outputs.len()
            ));
            for request in batch {
                let _ = request.reply.send(Err(error.clone()));
            }
            continue;
        }

        for (request, result) in batch.into_iter().zip(outputs) {
            let _ownership = (&request.invocation_id, &request.tenant_id);
            let _ = request.reply.send(result);
        }
    }
}

#[derive(Default)]
pub struct EchoExecutor;

#[async_trait]
impl BatchExecutor for EchoExecutor {
    async fn execute_batch(
        &self,
        _model: &ModelManifest,
        inputs: Vec<Vec<u8>>,
    ) -> Vec<Result<Vec<u8>, ModelError>> {
        inputs.into_iter().map(Ok).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest() -> ModelManifest {
        ModelManifest {
            name: "embed".into(),
            version: "1".into(),
            digest: format!("sha256:{}", "a".repeat(64)),
            weight_bytes: 100,
            workspace_bytes: 10,
            max_batch: 8,
            max_delay_us: 100,
            max_input_bytes: 1024,
            queue_capacity: 32,
            allow_cross_tenant_batching: false,
        }
    }

    #[tokio::test]
    async fn model_actor_routes_results_to_callers() {
        let daemon = ModelDaemon::new(EchoExecutor);
        let model = daemon.load(manifest()).await.unwrap();
        let output = model
            .infer("inv-1", "tenant-a", b"hello".to_vec())
            .await
            .unwrap();
        assert_eq!(output, b"hello");
    }

    #[tokio::test]
    async fn rejects_control_characters_in_actor_identifiers() {
        let daemon = ModelDaemon::new(EchoExecutor);
        let model = daemon.load(manifest()).await.unwrap();
        assert!(matches!(
            model.infer("inv\n1", "tenant", vec![1]).await,
            Err(ModelError::InvalidBatchKey)
        ));
        assert!(matches!(
            model.infer("inv", "tenant\n1", vec![1]).await,
            Err(ModelError::InvalidBatchKey)
        ));
    }

    #[tokio::test]
    async fn same_name_version_cannot_change_digest() {
        let daemon = ModelDaemon::new(EchoExecutor);
        daemon.load(manifest()).await.unwrap();
        let mut conflicting = manifest();
        conflicting.digest = format!("sha256:{}", "b".repeat(64));
        assert!(matches!(
            daemon.load(conflicting).await,
            Err(ModelError::Conflict(_))
        ));
    }

    struct CountingExecutor {
        calls: Arc<std::sync::atomic::AtomicUsize>,
    }

    #[async_trait]
    impl BatchExecutor for CountingExecutor {
        async fn execute_batch(
            &self,
            _model: &ModelManifest,
            inputs: Vec<Vec<u8>>,
        ) -> Vec<Result<Vec<u8>, ModelError>> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            inputs.into_iter().map(Ok).collect()
        }
    }

    #[tokio::test]
    async fn cancelled_callers_are_not_executed_when_observed_before_batch() {
        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let mut delayed = manifest();
        delayed.max_delay_us = 50_000;
        let daemon = ModelDaemon::new(CountingExecutor {
            calls: calls.clone(),
        });
        let model = daemon.load(delayed).await.unwrap();

        let task = tokio::spawn({
            let model = model.clone();
            async move {
                model
                    .infer_batched("inv-cancel", "tenant-a", "same", vec![1])
                    .await
            }
        });
        task.abort();

        time::sleep(Duration::from_millis(75)).await;
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn unload_revokes_existing_handles() {
        let daemon = ModelDaemon::new(EchoExecutor);
        let model = daemon.load(manifest()).await.unwrap();
        assert!(daemon.unload("embed:1").await);
        assert!(matches!(
            model.infer("inv", "tenant", vec![1]).await,
            Err(ModelError::Revoked)
        ));
    }
}
