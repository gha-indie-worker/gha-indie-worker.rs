use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    sync::{
        atomic::{compiler_fence, AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    sync::{oneshot, OwnedSemaphorePermit, Semaphore},
    time,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BackendKind {
    Cuda,
    Rocm,
    Metal,
    Vulkan,
    Mock,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceDescriptor {
    pub device_id: String,
    pub backend: BackendKind,
    pub total_vram_bytes: u64,
    pub lane_count: u32,
    pub supports_partitioning: bool,
    pub healthy: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GpuCommand {
    pub executable: String,
    pub inputs: Vec<Vec<u8>>,
    pub output_capacity: usize,
    pub deadline_unix_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionReceipt {
    pub device_id: String,
    pub lane_id: u64,
    pub output: Vec<u8>,
    pub scratch_bytes: u64,
}

#[derive(Debug, Clone)]
pub struct HostLimits {
    pub max_inputs: usize,
    pub max_input_bytes: usize,
    pub max_output_bytes: usize,
    pub max_scratch_bytes: u64,
    pub max_executable_len: usize,
}

impl Default for HostLimits {
    fn default() -> Self {
        Self {
            max_inputs: 64,
            max_input_bytes: 64 * 1024 * 1024,
            max_output_bytes: 64 * 1024 * 1024,
            max_scratch_bytes: 64 * 1024 * 1024 * 1024,
            max_executable_len: 256,
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum HostError {
    #[error("invalid host configuration: {0}")]
    InvalidConfiguration(String),
    #[error("invalid GPU command: {0}")]
    InvalidCommand(String),
    #[error("device not found: {0}")]
    DeviceNotFound(String),
    #[error("device unhealthy: {0}")]
    DeviceUnhealthy(String),
    #[error("vram admission failed: requested={requested} free={free}")]
    VramAdmission { requested: u64, free: u64 },
    #[error("output exceeded declared capacity: capacity={capacity} actual={actual}")]
    OutputTooLarge { capacity: usize, actual: usize },
    #[error("execution deadline exceeded")]
    DeadlineExceeded,
    #[error("backend execution failed: {0}")]
    Backend(String),
    #[error("lane pool closed")]
    LanePoolClosed,
}

/// Native backends must preserve resource safety if this future is dropped because a
/// caller deadline expires. In-flight device work must retain its buffers/context until
/// the backend observes completion; cancellation must never recycle memory early.
#[async_trait]
pub trait AcceleratorBackend: Send + Sync + 'static {
    fn devices(&self) -> Vec<DeviceDescriptor>;
    async fn execute(
        &self,
        device_id: &str,
        lane_id: u64,
        command: &GpuCommand,
    ) -> Result<Vec<u8>, HostError>;
}

struct DeviceState {
    descriptor: DeviceDescriptor,
    lanes: Arc<Semaphore>,
    next_lane: AtomicU64,
    reserved_vram: AtomicU64,
}

impl DeviceState {
    fn free_vram(&self) -> u64 {
        self.descriptor
            .total_vram_bytes
            .saturating_sub(self.reserved_vram.load(Ordering::Acquire))
    }

    fn reserve(self: &Arc<Self>, bytes: u64) -> Result<VramLease, HostError> {
        loop {
            let current = self.reserved_vram.load(Ordering::Acquire);
            let free = self.descriptor.total_vram_bytes.saturating_sub(current);
            if bytes > free {
                return Err(HostError::VramAdmission {
                    requested: bytes,
                    free,
                });
            }
            let Some(next) = current.checked_add(bytes) else {
                return Err(HostError::VramAdmission {
                    requested: bytes,
                    free,
                });
            };
            if self
                .reserved_vram
                .compare_exchange(current, next, Ordering::AcqRel, Ordering::Acquire)
                .is_ok()
            {
                return Ok(VramLease {
                    state: self.clone(),
                    bytes,
                });
            }
        }
    }
}

struct VramLease {
    state: Arc<DeviceState>,
    bytes: u64,
}

impl Drop for VramLease {
    fn drop(&mut self) {
        self.state
            .reserved_vram
            .fetch_sub(self.bytes, Ordering::AcqRel);
    }
}

pub struct ScratchBuffer {
    bytes: Vec<u8>,
    secure_clear: bool,
}

impl ScratchBuffer {
    pub fn new(size: usize, secure_clear: bool) -> Self {
        Self {
            bytes: vec![0; size],
            secure_clear,
        }
    }

    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        &mut self.bytes
    }
}

impl Drop for ScratchBuffer {
    fn drop(&mut self) {
        if self.secure_clear {
            // Volatile writes plus a compiler fence prevent the clear from being optimized
            // away. Native GPU backends must provide the equivalent guarantee for VRAM.
            for byte in &mut self.bytes {
                // SAFETY: `byte` is a valid uniquely borrowed u8 for the duration of the write.
                unsafe { std::ptr::write_volatile(byte, 0) };
            }
            compiler_fence(Ordering::SeqCst);
        }
    }
}

pub struct GpuHost<B: AcceleratorBackend> {
    backend: Arc<B>,
    devices: HashMap<String, Arc<DeviceState>>,
    limits: HostLimits,
}

impl<B: AcceleratorBackend> GpuHost<B> {
    pub fn new(backend: B) -> Result<Self, HostError> {
        Self::with_limits(backend, HostLimits::default())
    }

    pub fn with_limits(backend: B, limits: HostLimits) -> Result<Self, HostError> {
        if limits.max_inputs == 0
            || limits.max_input_bytes == 0
            || limits.max_output_bytes == 0
            || limits.max_scratch_bytes == 0
            || limits.max_executable_len == 0
        {
            return Err(HostError::InvalidConfiguration(
                "host limits must all be non-zero".into(),
            ));
        }

        let backend = Arc::new(backend);
        let descriptors = backend.devices();
        if descriptors.is_empty() {
            return Err(HostError::InvalidConfiguration(
                "backend reported no devices".into(),
            ));
        }

        let mut seen = HashSet::new();
        let mut devices = HashMap::new();
        for descriptor in descriptors {
            if descriptor.device_id.is_empty()
                || descriptor.device_id.len() > 256
                || descriptor.total_vram_bytes == 0
                || descriptor.lane_count == 0
            {
                return Err(HostError::InvalidConfiguration(format!(
                    "invalid device descriptor for {:?}",
                    descriptor.device_id
                )));
            }
            if !seen.insert(descriptor.device_id.clone()) {
                return Err(HostError::InvalidConfiguration(format!(
                    "duplicate device id {}",
                    descriptor.device_id
                )));
            }
            let id = descriptor.device_id.clone();
            devices.insert(
                id,
                Arc::new(DeviceState {
                    lanes: Arc::new(Semaphore::new(descriptor.lane_count as usize)),
                    descriptor,
                    next_lane: AtomicU64::new(0),
                    reserved_vram: AtomicU64::new(0),
                }),
            );
        }

        Ok(Self {
            backend,
            devices,
            limits,
        })
    }

    pub fn snapshots(&self) -> Vec<(DeviceDescriptor, u64, usize)> {
        let mut snapshots: Vec<_> = self
            .devices
            .values()
            .map(|device| {
                (
                    device.descriptor.clone(),
                    device.free_vram(),
                    device.lanes.available_permits(),
                )
            })
            .collect();
        snapshots.sort_by(|a, b| a.0.device_id.cmp(&b.0.device_id));
        snapshots
    }

    fn validate_command(&self, scratch_bytes: u64, command: &GpuCommand) -> Result<(), HostError> {
        if command.executable.is_empty()
            || command.executable.len() > self.limits.max_executable_len
            || command.executable == "."
            || command.executable == ".."
            || !command
                .executable
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
        {
            return Err(HostError::InvalidCommand(
                "invalid executable identifier".into(),
            ));
        }
        if command.inputs.len() > self.limits.max_inputs {
            return Err(HostError::InvalidCommand("too many input buffers".into()));
        }
        let total_input = command.inputs.iter().try_fold(0usize, |total, input| {
            total
                .checked_add(input.len())
                .ok_or_else(|| HostError::InvalidCommand("input byte count overflow".into()))
        })?;
        if total_input > self.limits.max_input_bytes {
            return Err(HostError::InvalidCommand(
                "input byte limit exceeded".into(),
            ));
        }
        if command.output_capacity > self.limits.max_output_bytes {
            return Err(HostError::InvalidCommand(
                "output capacity limit exceeded".into(),
            ));
        }
        if scratch_bytes > self.limits.max_scratch_bytes {
            return Err(HostError::InvalidCommand(
                "scratch byte limit exceeded".into(),
            ));
        }
        if let Some(deadline) = command.deadline_unix_ms {
            if deadline <= unix_ms() {
                return Err(HostError::DeadlineExceeded);
            }
        }
        Ok(())
    }

    pub async fn execute(
        &self,
        device_id: &str,
        scratch_bytes: u64,
        command: GpuCommand,
    ) -> Result<ExecutionReceipt, HostError> {
        self.validate_command(scratch_bytes, &command)?;
        let state = self
            .devices
            .get(device_id)
            .ok_or_else(|| HostError::DeviceNotFound(device_id.into()))?
            .clone();
        if !state.descriptor.healthy {
            return Err(HostError::DeviceUnhealthy(device_id.into()));
        }

        let vram = state.reserve(scratch_bytes)?;
        let lane_future = state.lanes.clone().acquire_owned();
        let lane: OwnedSemaphorePermit = match remaining(command.deadline_unix_ms)? {
            Some(duration) => time::timeout(duration, lane_future)
                .await
                .map_err(|_| HostError::DeadlineExceeded)?
                .map_err(|_| HostError::LanePoolClosed)?,
            None => lane_future.await.map_err(|_| HostError::LanePoolClosed)?,
        };

        let lane_id = state.next_lane.fetch_add(1, Ordering::Relaxed);
        let backend = self.backend.clone();
        let backend_device_id = device_id.to_owned();
        let output_capacity = command.output_capacity;
        let deadline_unix_ms = command.deadline_unix_ms;
        let (completion_tx, completion_rx) = oneshot::channel();

        // The caller deadline is not allowed to recycle host accounting while native
        // device work may still be in flight. The detached task owns both admission
        // leases until the backend future actually completes.
        tokio::spawn(async move {
            let _vram = vram;
            let _lane = lane;
            let result = backend.execute(&backend_device_id, lane_id, &command).await;
            let _ = completion_tx.send(result);
        });

        let output = match remaining(deadline_unix_ms)? {
            Some(duration) => time::timeout(duration, completion_rx)
                .await
                .map_err(|_| HostError::DeadlineExceeded)?
                .map_err(|_| HostError::Backend("backend completion task stopped".into()))??,
            None => completion_rx
                .await
                .map_err(|_| HostError::Backend("backend completion task stopped".into()))??,
        };

        if output.len() > output_capacity || output.len() > self.limits.max_output_bytes {
            return Err(HostError::OutputTooLarge {
                capacity: output_capacity.min(self.limits.max_output_bytes),
                actual: output.len(),
            });
        }

        Ok(ExecutionReceipt {
            device_id: device_id.into(),
            lane_id,
            output,
            scratch_bytes,
        })
    }
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn remaining(deadline_unix_ms: Option<u64>) -> Result<Option<Duration>, HostError> {
    let Some(deadline) = deadline_unix_ms else {
        return Ok(None);
    };
    let now = unix_ms();
    let millis = deadline
        .checked_sub(now)
        .ok_or(HostError::DeadlineExceeded)?;
    if millis == 0 {
        return Err(HostError::DeadlineExceeded);
    }
    Ok(Some(Duration::from_millis(millis)))
}

#[derive(Clone)]
pub struct MockBackend {
    pub descriptors: Vec<DeviceDescriptor>,
}

#[async_trait]
impl AcceleratorBackend for MockBackend {
    fn devices(&self) -> Vec<DeviceDescriptor> {
        self.descriptors.clone()
    }

    async fn execute(
        &self,
        _device_id: &str,
        _lane_id: u64,
        command: &GpuCommand,
    ) -> Result<Vec<u8>, HostError> {
        Ok(command.inputs.first().cloned().unwrap_or_default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host() -> GpuHost<MockBackend> {
        GpuHost::new(MockBackend {
            descriptors: vec![DeviceDescriptor {
                device_id: "mock0".into(),
                backend: BackendKind::Mock,
                total_vram_bytes: 1024,
                lane_count: 2,
                supports_partitioning: false,
                healthy: true,
            }],
        })
        .unwrap()
    }

    #[tokio::test]
    async fn executes_through_a_bounded_lane() {
        let receipt = host()
            .execute(
                "mock0",
                64,
                GpuCommand {
                    executable: "echo".into(),
                    inputs: vec![b"hello".to_vec()],
                    output_capacity: 64,
                    deadline_unix_ms: None,
                },
            )
            .await
            .unwrap();
        assert_eq!(receipt.output, b"hello");
    }

    #[tokio::test]
    async fn rejects_path_like_executable_identifiers() {
        let error = host()
            .execute(
                "mock0",
                1,
                GpuCommand {
                    executable: "../engine".into(),
                    inputs: vec![],
                    output_capacity: 0,
                    deadline_unix_ms: None,
                },
            )
            .await
            .unwrap_err();
        assert!(matches!(error, HostError::InvalidCommand(_)));
    }

    #[tokio::test]
    async fn rejects_vram_overcommit() {
        let error = host()
            .execute(
                "mock0",
                2048,
                GpuCommand {
                    executable: "echo".into(),
                    inputs: vec![],
                    output_capacity: 0,
                    deadline_unix_ms: None,
                },
            )
            .await
            .unwrap_err();
        assert!(matches!(error, HostError::VramAdmission { .. }));
    }

    #[derive(Clone)]
    struct SlowBackend {
        descriptor: DeviceDescriptor,
        delay: Duration,
    }

    #[async_trait]
    impl AcceleratorBackend for SlowBackend {
        fn devices(&self) -> Vec<DeviceDescriptor> {
            vec![self.descriptor.clone()]
        }

        async fn execute(
            &self,
            _device_id: &str,
            _lane_id: u64,
            command: &GpuCommand,
        ) -> Result<Vec<u8>, HostError> {
            time::sleep(self.delay).await;
            Ok(command.inputs.first().cloned().unwrap_or_default())
        }
    }

    #[tokio::test]
    async fn timeout_keeps_lane_and_vram_reserved_until_backend_finishes() {
        let host = GpuHost::new(SlowBackend {
            descriptor: DeviceDescriptor {
                device_id: "slow0".into(),
                backend: BackendKind::Mock,
                total_vram_bytes: 1024,
                lane_count: 1,
                supports_partitioning: false,
                healthy: true,
            },
            delay: Duration::from_millis(100),
        })
        .unwrap();

        let error = host
            .execute(
                "slow0",
                512,
                GpuCommand {
                    executable: "slow".into(),
                    inputs: vec![b"hello".to_vec()],
                    output_capacity: 64,
                    deadline_unix_ms: Some(unix_ms() + 20),
                },
            )
            .await
            .unwrap_err();
        assert!(matches!(error, HostError::DeadlineExceeded));

        let snapshots = host.snapshots();
        assert_eq!(snapshots[0].1, 512);
        assert_eq!(snapshots[0].2, 0);

        time::sleep(Duration::from_millis(120)).await;
        let snapshots = host.snapshots();
        assert_eq!(snapshots[0].1, 1024);
        assert_eq!(snapshots[0].2, 1);
    }

    #[tokio::test]
    async fn rejects_oversized_output_capacity_before_backend_execution() {
        let limits = HostLimits {
            max_output_bytes: 4,
            ..HostLimits::default()
        };
        let host = GpuHost::with_limits(
            MockBackend {
                descriptors: vec![DeviceDescriptor {
                    device_id: "mock0".into(),
                    backend: BackendKind::Mock,
                    total_vram_bytes: 1024,
                    lane_count: 1,
                    supports_partitioning: false,
                    healthy: true,
                }],
            },
            limits,
        )
        .unwrap();
        let error = host
            .execute(
                "mock0",
                1,
                GpuCommand {
                    executable: "echo".into(),
                    inputs: vec![b"hello".to_vec()],
                    output_capacity: 5,
                    deadline_unix_ms: None,
                },
            )
            .await
            .unwrap_err();
        assert!(matches!(error, HostError::InvalidCommand(_)));
    }
}
