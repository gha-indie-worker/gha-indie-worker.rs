use async_trait::async_trait;
use axum::{
    body::Bytes,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use litegraph_gpu_host::{BackendKind, DeviceDescriptor, GpuCommand, GpuHost, MockBackend};
use litegraph_modeld::{BatchExecutor, ModelDaemon, ModelError, ModelManifest};
use litegraph_node::{DeviceState, NodeState};
use litegraph_runtime::{
    GpuClient, GuestEngine, HostApi, InvocationRequest, InvocationResponse, InvocationRuntime,
    RuntimeError,
};
use std::{
    env,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};

const DEFAULT_MAX_BODY_BYTES: usize = 64 * 1024 * 1024;
const MAX_DEADLINE_MS: u64 = 300_000;

#[async_trait]
trait InvocationService: Send + Sync {
    async fn invoke(
        &self,
        function: &str,
        request: InvocationRequest,
    ) -> Result<InvocationResponse, RuntimeError>;
    fn mode(&self) -> &'static str;
}

#[derive(Clone)]
struct AppState {
    node: NodeState,
    service: Option<Arc<dyn InvocationService>>,
    node_token: Arc<str>,
}

struct HostBatchExecutor {
    host: Arc<GpuHost<MockBackend>>,
    device_id: String,
}

#[async_trait]
impl BatchExecutor for HostBatchExecutor {
    async fn execute_batch(
        &self,
        model: &ModelManifest,
        inputs: Vec<Vec<u8>>,
    ) -> Vec<Result<Vec<u8>, ModelError>> {
        let mut outputs = Vec::with_capacity(inputs.len());
        for input in inputs {
            let result = self
                .host
                .execute(
                    &self.device_id,
                    model.workspace_bytes,
                    GpuCommand {
                        executable: model.digest.clone(),
                        output_capacity: input.len(),
                        inputs: vec![input],
                        deadline_unix_ms: None,
                    },
                )
                .await
                .map(|receipt| receipt.output)
                .map_err(|error| ModelError::Execution(error.to_string()));
            outputs.push(result);
        }
        outputs
    }
}

struct ModelGpuClient {
    daemon: Arc<ModelDaemon<HostBatchExecutor>>,
    invocation_counter: AtomicU64,
}

#[async_trait]
impl GpuClient for ModelGpuClient {
    async fn open_model(&self, _tenant_id: &str, model: &str) -> Result<String, RuntimeError> {
        if model != "default" {
            return Err(RuntimeError::Accelerator("unknown model".into()));
        }
        let key = "default:1".to_string();
        self.daemon
            .get(&key)
            .await
            .map_err(|error| RuntimeError::Accelerator(error.to_string()))?;
        Ok(key)
    }

    async fn infer(
        &self,
        tenant_id: &str,
        model_key: &str,
        input: Vec<u8>,
    ) -> Result<Vec<u8>, RuntimeError> {
        let model = self
            .daemon
            .get(model_key)
            .await
            .map_err(|error| RuntimeError::Accelerator(error.to_string()))?;
        let invocation_id = format!(
            "node-{}",
            self.invocation_counter.fetch_add(1, Ordering::Relaxed)
        );
        let batch_key = format!("bytes-{}", input.len());
        model
            .infer_batched(invocation_id, tenant_id, batch_key, input)
            .await
            .map_err(|error| RuntimeError::Accelerator(error.to_string()))
    }
}

#[derive(Default)]
struct NodeGuest;

#[async_trait]
impl<G: GpuClient> GuestEngine<G> for NodeGuest {
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

struct MockExecutionService {
    runtime: InvocationRuntime<ModelGpuClient, NodeGuest>,
}

impl MockExecutionService {
    async fn build(vram_bytes: u64) -> Result<(Self, String), String> {
        let host = Arc::new(
            GpuHost::new(MockBackend {
                descriptors: vec![DeviceDescriptor {
                    device_id: "mock0".into(),
                    backend: BackendKind::Mock,
                    total_vram_bytes: vram_bytes,
                    lane_count: 8,
                    supports_partitioning: false,
                    healthy: true,
                }],
            })
            .map_err(|error| error.to_string())?,
        );
        let executor = HostBatchExecutor {
            host,
            device_id: "mock0".into(),
        };
        let daemon = Arc::new(ModelDaemon::new(executor));
        let digest = format!("sha256:{}", "0".repeat(64));
        daemon
            .load(ModelManifest {
                name: "default".into(),
                version: "1".into(),
                digest: digest.clone(),
                weight_bytes: 1024,
                workspace_bytes: 1024,
                max_batch: 32,
                max_delay_us: 500,
                max_input_bytes: DEFAULT_MAX_BODY_BYTES,
                queue_capacity: 1024,
                allow_cross_tenant_batching: false,
            })
            .await
            .map_err(|error| error.to_string())?;
        let gpu = ModelGpuClient {
            daemon,
            invocation_counter: AtomicU64::new(1),
        };
        Ok((
            Self {
                runtime: InvocationRuntime::new(gpu, NodeGuest),
            },
            digest,
        ))
    }
}

#[async_trait]
impl InvocationService for MockExecutionService {
    async fn invoke(
        &self,
        function: &str,
        request: InvocationRequest,
    ) -> Result<InvocationResponse, RuntimeError> {
        if function != "default" {
            return Err(RuntimeError::Guest("unknown mock function".into()));
        }
        self.runtime.invoke(request).await
    }

    fn mode(&self) -> &'static str {
        "runtime-stack/mock"
    }
}

#[tokio::main]
async fn main() {
    let node_id = env::var("LITEGRAPH_NODE_ID").unwrap_or_else(|_| "local-node".into());
    let region = env::var("LITEGRAPH_REGION").unwrap_or_else(|_| "local".into());
    let bind = env::var("LITEGRAPH_NODE_ADDR").unwrap_or_else(|_| "127.0.0.1:0".into());
    let node_token: Arc<str> = env::var("LITEGRAPH_NODE_TOKEN")
        .expect("LITEGRAPH_NODE_TOKEN must be configured")
        .into();
    if node_token.len() < 32 {
        panic!("LITEGRAPH_NODE_TOKEN must be at least 32 bytes");
    }

    let node = NodeState::new(node_id, region).expect("valid node identity");
    let backend = env::var("LITEGRAPH_EXECUTION_BACKEND").unwrap_or_else(|_| "disabled".into());
    let service: Option<Arc<dyn InvocationService>> = match backend.as_str() {
        "disabled" => None,
        "mock" => {
            let vram = env::var("LITEGRAPH_MOCK_GPU_VRAM_BYTES")
                .ok()
                .and_then(|value| value.parse::<u64>().ok())
                .filter(|value| *value >= 1024 * 1024)
                .unwrap_or(24 * 1024 * 1024 * 1024);
            let (service, digest) = MockExecutionService::build(vram)
                .await
                .expect("initialize explicit mock execution stack");
            node.set_devices(vec![DeviceState {
                device_id: "mock0".into(),
                backend: "mock".into(),
                architecture: "mock".into(),
                supported_isolation: vec!["shared".into(), "sandbox".into()],
                total_vram_bytes: vram,
                free_vram_bytes: vram,
                available_lanes: 8,
                queue_depth: 0,
                healthy: true,
            }])
            .await
            .expect("valid mock device state");
            node.mark_resident(digest)
                .await
                .expect("valid mock resident digest");
            Some(Arc::new(service))
        }
        other => panic!("unsupported LITEGRAPH_EXECUTION_BACKEND={other:?}"),
    };

    let state = Arc::new(AppState {
        node,
        service,
        node_token,
    });
    let max_body = env::var("LITEGRAPH_NODE_MAX_BODY_BYTES")
        .ok()
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|value| (1..=1024 * 1024 * 1024).contains(value))
        .unwrap_or(DEFAULT_MAX_BODY_BYTES);

    let app = Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/v1/node", get(snapshot))
        .route("/v1/node/drain", post(drain))
        .route("/v1/node/resume", post(resume))
        .route("/v1/invoke/{function}", post(invoke))
        .layer(DefaultBodyLimit::max(max_body))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(&bind)
        .await
        .expect("bind litegraph-node");
    let addr: SocketAddr = listener.local_addr().expect("local addr");
    eprintln!("litegraph-node listening on {addr}; execution_backend={backend}");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await
        .expect("serve litegraph-node");
}

async fn snapshot(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<litegraph_node::NodeSnapshot>, StatusCode> {
    authorize(&headers, &state.node_token)?;
    Ok(Json(state.node.snapshot().await))
}

async fn drain(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<&'static str, StatusCode> {
    authorize(&headers, &state.node_token)?;
    state.node.drain();
    Ok("draining")
}

async fn resume(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<&'static str, StatusCode> {
    authorize(&headers, &state.node_token)?;
    state.node.resume();
    Ok("accepting")
}

async fn invoke(
    Path(function): Path<String>,
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if authorize(&headers, &state.node_token).is_err() {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    if !valid_function(&function) {
        return (StatusCode::BAD_REQUEST, "invalid function id").into_response();
    }
    let Some(tenant_id) =
        header_str(&headers, "x-litegraph-tenant-id").filter(|value| valid_id(value))
    else {
        return (StatusCode::BAD_REQUEST, "invalid tenant id").into_response();
    };
    let Some(invocation_id) =
        header_str(&headers, "x-litegraph-invocation-id").filter(|value| valid_id(value))
    else {
        return (StatusCode::BAD_REQUEST, "invalid invocation id").into_response();
    };
    let deadline_ms = match header_str(&headers, "x-litegraph-deadline-ms") {
        Some(value) => match value.parse::<u64>() {
            Ok(value) if (1..=MAX_DEADLINE_MS).contains(&value) => value,
            _ => return (StatusCode::BAD_REQUEST, "invalid deadline").into_response(),
        },
        None => 30_000,
    };

    let Some(service) = state.service.as_ref() else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            "execution backend not configured",
        )
            .into_response();
    };
    let Some(_guard) = state.node.begin_invocation() else {
        return (StatusCode::SERVICE_UNAVAILABLE, "node draining").into_response();
    };

    let result = service
        .invoke(
            &function,
            InvocationRequest {
                invocation_id: invocation_id.to_owned(),
                tenant_id: tenant_id.to_owned(),
                payload: body.to_vec(),
                deadline_ms_from_now: Some(deadline_ms),
            },
        )
        .await;

    match result {
        Ok(response) => {
            let mut out_headers = HeaderMap::new();
            out_headers.insert("x-litegraph-queue-depth", HeaderValue::from_static("0"));
            out_headers.insert(
                "x-litegraph-execution",
                HeaderValue::from_static(service.mode()),
            );
            (out_headers, Bytes::from(response.payload)).into_response()
        }
        Err(error) => runtime_error_response(error),
    }
}

fn runtime_error_response(error: RuntimeError) -> Response {
    let (status, public_message) = runtime_error_parts(&error);
    eprintln!(
        "litegraph-node invocation failed: class={}",
        runtime_error_class(&error)
    );
    (status, public_message).into_response()
}

fn runtime_error_parts(error: &RuntimeError) -> (StatusCode, &'static str) {
    match error {
        RuntimeError::PayloadTooLarge { .. } => {
            (StatusCode::PAYLOAD_TOO_LARGE, "payload too large")
        }
        RuntimeError::AcceleratorInputTooLarge { .. } => {
            (StatusCode::PAYLOAD_TOO_LARGE, "accelerator input too large")
        }
        RuntimeError::InvalidInvocation(_) => (StatusCode::BAD_REQUEST, "invalid invocation"),
        RuntimeError::InvalidCapability(_) => (StatusCode::BAD_REQUEST, "invalid capability"),
        RuntimeError::DeadlineExceeded => (StatusCode::GATEWAY_TIMEOUT, "deadline exceeded"),
        RuntimeError::Cancelled => (StatusCode::REQUEST_TIMEOUT, "request cancelled"),
        RuntimeError::Accelerator(_) => {
            (StatusCode::BAD_GATEWAY, "accelerator execution failed")
        }
        RuntimeError::ResponseTooLarge { .. } => {
            (StatusCode::INTERNAL_SERVER_ERROR, "response too large")
        }
        RuntimeError::CapabilityLimitExceeded => {
            (StatusCode::INTERNAL_SERVER_ERROR, "capability limit exceeded")
        }
        RuntimeError::CapabilityMemoryExceeded => {
            (StatusCode::INTERNAL_SERVER_ERROR, "capability memory limit exceeded")
        }
        RuntimeError::Guest(_) => (StatusCode::INTERNAL_SERVER_ERROR, "guest execution failed"),
    }
}

fn runtime_error_class(error: &RuntimeError) -> &'static str {
    match error {
        RuntimeError::PayloadTooLarge { .. } => "payload_too_large",
        RuntimeError::ResponseTooLarge { .. } => "response_too_large",
        RuntimeError::AcceleratorInputTooLarge { .. } => "accelerator_input_too_large",
        RuntimeError::CapabilityLimitExceeded => "capability_limit",
        RuntimeError::CapabilityMemoryExceeded => "capability_memory",
        RuntimeError::Cancelled => "cancelled",
        RuntimeError::DeadlineExceeded => "deadline",
        RuntimeError::InvalidInvocation(_) => "invalid_invocation",
        RuntimeError::InvalidCapability(_) => "invalid_capability",
        RuntimeError::Accelerator(_) => "accelerator",
        RuntimeError::Guest(_) => "guest",
    }
}

fn authorize(headers: &HeaderMap, expected: &str) -> Result<(), StatusCode> {
    let value = header_str(headers, "authorization").ok_or(StatusCode::UNAUTHORIZED)?;
    let token = value
        .strip_prefix("Bearer ")
        .ok_or(StatusCode::UNAUTHORIZED)?;
    if constant_time_eq(token.as_bytes(), expected.as_bytes()) {
        Ok(())
    } else {
        Err(StatusCode::UNAUTHORIZED)
    }
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name)?.to_str().ok()
}

fn valid_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 256 && !value.bytes().any(|b| b.is_ascii_control())
}

fn valid_function(value: &str) -> bool {
    valid_id(value)
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut difference = 0u8;
    for (&a, &b) in left.iter().zip(right) {
        difference |= a ^ b;
    }
    difference == 0
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}
