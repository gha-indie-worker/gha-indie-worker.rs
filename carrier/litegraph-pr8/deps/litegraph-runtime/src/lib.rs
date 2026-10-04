use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        atomic::{compiler_fence, AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    time::Duration,
};
use tokio::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct CapabilityHandle(pub u64);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Capability {
    Model { key: String },
    Tensor { bytes: Vec<u8> },
    Buffer { bytes: Vec<u8> },
    OutputStream,
}

impl Capability {
    fn resident_bytes(&self) -> usize {
        match self {
            Self::Model { key } => key.len(),
            Self::Tensor { bytes } | Self::Buffer { bytes } => bytes.len(),
            Self::OutputStream => 0,
        }
    }

    fn secure_clear(&mut self) {
        match self {
            Self::Tensor { bytes } | Self::Buffer { bytes } => secure_zero(bytes),
            Self::Model { key } => {
                // Model keys are not secrets, but clearing avoids retaining tenant-scoped handles.
                unsafe { key.as_bytes_mut() }.fill(0);
            }
            Self::OutputStream => {}
        }
    }
}

fn secure_zero(bytes: &mut [u8]) {
    for byte in bytes {
        // SAFETY: `byte` is uniquely borrowed and valid for a volatile u8 write.
        unsafe { std::ptr::write_volatile(byte, 0) };
    }
    compiler_fence(Ordering::SeqCst);
}

#[derive(Debug, Clone)]
pub struct RuntimeLimits {
    pub max_payload_bytes: usize,
    pub max_response_bytes: usize,
    pub max_accelerator_input_bytes: usize,
    pub max_capabilities: usize,
    pub max_capability_bytes: usize,
    pub default_deadline_ms: u64,
    pub max_deadline_ms: u64,
    pub max_model_name_len: usize,
}

impl Default for RuntimeLimits {
    fn default() -> Self {
        Self {
            max_payload_bytes: 64 * 1024 * 1024,
            max_response_bytes: 64 * 1024 * 1024,
            max_accelerator_input_bytes: 64 * 1024 * 1024,
            max_capabilities: 1024,
            max_capability_bytes: 128 * 1024 * 1024,
            default_deadline_ms: 30_000,
            max_deadline_ms: 300_000,
            max_model_name_len: 256,
        }
    }
}

struct CapabilityState {
    entries: HashMap<CapabilityHandle, Capability>,
    resident_bytes: usize,
}

pub struct CapabilityTable {
    next: AtomicU64,
    state: Mutex<CapabilityState>,
    max_entries: usize,
    max_bytes: usize,
}

impl CapabilityTable {
    fn new(max_entries: usize, max_bytes: usize) -> Self {
        Self {
            next: AtomicU64::new(1),
            state: Mutex::new(CapabilityState {
                entries: HashMap::new(),
                resident_bytes: 0,
            }),
            max_entries,
            max_bytes,
        }
    }

    pub async fn insert(&self, capability: Capability) -> Result<CapabilityHandle, RuntimeError> {
        let bytes = capability.resident_bytes();
        let mut state = self.state.lock().await;
        if state.entries.len() >= self.max_entries {
            return Err(RuntimeError::CapabilityLimitExceeded);
        }
        let Some(next_bytes) = state.resident_bytes.checked_add(bytes) else {
            return Err(RuntimeError::CapabilityMemoryExceeded);
        };
        if next_bytes > self.max_bytes {
            return Err(RuntimeError::CapabilityMemoryExceeded);
        }
        let raw = self.next.fetch_add(1, Ordering::Relaxed);
        if raw == u64::MAX {
            return Err(RuntimeError::CapabilityLimitExceeded);
        }
        let handle = CapabilityHandle(raw);
        state.resident_bytes = next_bytes;
        state.entries.insert(handle, capability);
        Ok(handle)
    }

    pub async fn model_key(&self, handle: CapabilityHandle) -> Option<String> {
        let state = self.state.lock().await;
        match state.entries.get(&handle) {
            Some(Capability::Model { key }) => Some(key.clone()),
            _ => None,
        }
    }

    pub async fn revoke_all(&self) {
        let mut state = self.state.lock().await;
        for capability in state.entries.values_mut() {
            capability.secure_clear();
        }
        state.entries.clear();
        state.resident_bytes = 0;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InvocationRequest {
    pub invocation_id: String,
    pub tenant_id: String,
    pub payload: Vec<u8>,
    pub deadline_ms_from_now: Option<u64>,
}

impl Drop for InvocationRequest {
    fn drop(&mut self) {
        secure_zero(&mut self.payload);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvocationResponse {
    pub payload: Vec<u8>,
}

impl Drop for InvocationResponse {
    fn drop(&mut self) {
        secure_zero(&mut self.payload);
    }
}

#[derive(Debug, thiserror::Error)]
pub enum RuntimeError {
    #[error("invalid invocation: {0}")]
    InvalidInvocation(String),
    #[error("invocation payload too large: max={max} actual={actual}")]
    PayloadTooLarge { max: usize, actual: usize },
    #[error("invocation response too large: max={max} actual={actual}")]
    ResponseTooLarge { max: usize, actual: usize },
    #[error("accelerator input too large: max={max} actual={actual}")]
    AcceleratorInputTooLarge { max: usize, actual: usize },
    #[error("capability count limit exceeded")]
    CapabilityLimitExceeded,
    #[error("capability memory limit exceeded")]
    CapabilityMemoryExceeded,
    #[error("invocation cancelled")]
    Cancelled,
    #[error("deadline exceeded")]
    DeadlineExceeded,
    #[error("invalid capability {0:?}")]
    InvalidCapability(CapabilityHandle),
    #[error("accelerator error: {0}")]
    Accelerator(String),
    #[error("guest error: {0}")]
    Guest(String),
}

#[async_trait]
pub trait GpuClient: Send + Sync + 'static {
    async fn open_model(&self, tenant_id: &str, model: &str) -> Result<String, RuntimeError>;
    async fn infer(
        &self,
        tenant_id: &str,
        model_key: &str,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, RuntimeError>;
}

pub struct HostApi<G: GpuClient> {
    tenant_id: String,
    gpu: Arc<G>,
    capabilities: Arc<CapabilityTable>,
    cancelled: Arc<AtomicBool>,
    limits: Arc<RuntimeLimits>,
}

impl<G: GpuClient> HostApi<G> {
    pub async fn open_model(&self, name: &str) -> Result<CapabilityHandle, RuntimeError> {
        self.check_cancelled()?;
        if name.is_empty()
            || name.len() > self.limits.max_model_name_len
            || name.bytes().any(|b| b.is_ascii_control())
        {
            return Err(RuntimeError::InvalidInvocation("invalid model name".into()));
        }
        let key = self.gpu.open_model(&self.tenant_id, name).await?;
        self.check_cancelled()?;
        self.capabilities.insert(Capability::Model { key }).await
    }

    pub async fn infer(
        &self,
        model: CapabilityHandle,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, RuntimeError> {
        self.check_cancelled()?;
        if input.len() > self.limits.max_accelerator_input_bytes {
            return Err(RuntimeError::AcceleratorInputTooLarge {
                max: self.limits.max_accelerator_input_bytes,
                actual: input.len(),
            });
        }
        let key = self
            .capabilities
            .model_key(model)
            .await
            .ok_or(RuntimeError::InvalidCapability(model))?;
        let output = self.gpu.infer(&self.tenant_id, &key, input).await?;
        self.check_cancelled()?;
        if output.len() > self.limits.max_response_bytes {
            return Err(RuntimeError::ResponseTooLarge {
                max: self.limits.max_response_bytes,
                actual: output.len(),
            });
        }
        Ok(output)
    }

    fn check_cancelled(&self) -> Result<(), RuntimeError> {
        if self.cancelled.load(Ordering::Acquire) {
            Err(RuntimeError::Cancelled)
        } else {
            Ok(())
        }
    }
}

#[async_trait]
pub trait GuestEngine<G: GpuClient>: Send + Sync + 'static {
    async fn execute(
        &self,
        request: &InvocationRequest,
        host: &HostApi<G>,
    ) -> Result<InvocationResponse, RuntimeError>;
}

pub struct InvocationRuntime<G: GpuClient, E: GuestEngine<G>> {
    gpu: Arc<G>,
    engine: Arc<E>,
    limits: Arc<RuntimeLimits>,
}

impl<G: GpuClient, E: GuestEngine<G>> InvocationRuntime<G, E> {
    pub fn new(gpu: G, engine: E) -> Self {
        Self::with_limits(gpu, engine, RuntimeLimits::default())
    }

    pub fn with_limits(gpu: G, engine: E, limits: RuntimeLimits) -> Self {
        Self {
            gpu: Arc::new(gpu),
            engine: Arc::new(engine),
            limits: Arc::new(limits),
        }
    }

    fn validate_request(&self, request: &InvocationRequest) -> Result<u64, RuntimeError> {
        if request.invocation_id.is_empty()
            || request.invocation_id.len() > 256
            || request.tenant_id.is_empty()
            || request.tenant_id.len() > 256
            || request
                .invocation_id
                .bytes()
                .chain(request.tenant_id.bytes())
                .any(|b| b.is_ascii_control())
        {
            return Err(RuntimeError::InvalidInvocation(
                "invalid invocation_id or tenant_id".into(),
            ));
        }
        if request.payload.len() > self.limits.max_payload_bytes {
            return Err(RuntimeError::PayloadTooLarge {
                max: self.limits.max_payload_bytes,
                actual: request.payload.len(),
            });
        }
        let deadline = request
            .deadline_ms_from_now
            .unwrap_or(self.limits.default_deadline_ms);
        if deadline == 0 || deadline > self.limits.max_deadline_ms {
            return Err(RuntimeError::InvalidInvocation(format!(
                "deadline must be within 1..={}ms",
                self.limits.max_deadline_ms
            )));
        }
        Ok(deadline)
    }

    pub async fn invoke(
        &self,
        request: InvocationRequest,
    ) -> Result<InvocationResponse, RuntimeError> {
        let deadline_ms = self.validate_request(&request)?;
        let capabilities = Arc::new(CapabilityTable::new(
            self.limits.max_capabilities,
            self.limits.max_capability_bytes,
        ));
        let cancelled = Arc::new(AtomicBool::new(false));
        let host = HostApi {
            tenant_id: request.tenant_id.clone(),
            gpu: self.gpu.clone(),
            capabilities: capabilities.clone(),
            cancelled: cancelled.clone(),
            limits: self.limits.clone(),
        };
        let run = self.engine.execute(&request, &host);

        let result = match tokio::time::timeout(Duration::from_millis(deadline_ms), run).await {
            Ok(result) => result,
            Err(_) => {
                cancelled.store(true, Ordering::Release);
                Err(RuntimeError::DeadlineExceeded)
            }
        };

        let result = match result {
            Ok(response) if response.payload.len() > self.limits.max_response_bytes => {
                Err(RuntimeError::ResponseTooLarge {
                    max: self.limits.max_response_bytes,
                    actual: response.payload.len(),
                })
            }
            other => other,
        };

        capabilities.revoke_all().await;
        result
    }
}

#[cfg(any(test, feature = "test-utils"))]
#[derive(Default)]
pub struct EchoGpu;

#[cfg(any(test, feature = "test-utils"))]
#[async_trait]
impl GpuClient for EchoGpu {
    async fn open_model(&self, _tenant_id: &str, model: &str) -> Result<String, RuntimeError> {
        Ok(model.to_owned())
    }

    async fn infer(
        &self,
        _tenant_id: &str,
        _model_key: &str,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, RuntimeError> {
        Ok(input)
    }
}

#[cfg(any(test, feature = "test-utils"))]
#[derive(Default)]
pub struct ExampleGuest;

#[cfg(any(test, feature = "test-utils"))]
#[async_trait]
impl<G: GpuClient> GuestEngine<G> for ExampleGuest {
    async fn execute(
        &self,
        request: &InvocationRequest,
        host: &HostApi<G>,
    ) -> Result<InvocationResponse, RuntimeError> {
        let model = host.open_model("default").await?;
        let payload = host.infer(model, request.payload.clone()).await?;
        Ok(InvocationResponse { payload })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn capabilities_are_request_scoped() {
        let runtime = InvocationRuntime::new(EchoGpu, ExampleGuest);
        let response = runtime
            .invoke(InvocationRequest {
                invocation_id: "inv-1".into(),
                tenant_id: "tenant-a".into(),
                payload: b"abc".to_vec(),
                deadline_ms_from_now: Some(1000),
            })
            .await
            .unwrap();
        assert_eq!(response.payload, b"abc");
    }

    #[test]
    fn secure_zero_clears_request_bytes() {
        let mut bytes = b"sensitive-request".to_vec();
        secure_zero(&mut bytes);
        assert!(bytes.iter().all(|byte| *byte == 0));
    }

    #[tokio::test]
    async fn rejects_oversized_payloads_before_guest_execution() {
        let limits = RuntimeLimits {
            max_payload_bytes: 2,
            ..RuntimeLimits::default()
        };
        let runtime = InvocationRuntime::with_limits(EchoGpu, ExampleGuest, limits);
        let error = runtime
            .invoke(InvocationRequest {
                invocation_id: "inv-1".into(),
                tenant_id: "tenant-a".into(),
                payload: vec![1, 2, 3],
                deadline_ms_from_now: Some(1000),
            })
            .await
            .unwrap_err();
        assert!(matches!(error, RuntimeError::PayloadTooLarge { .. }));
    }
}
