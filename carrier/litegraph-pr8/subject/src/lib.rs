use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
};
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceState {
    pub device_id: String,
    pub backend: String,
    pub architecture: String,
    pub supported_isolation: Vec<String>,
    pub total_vram_bytes: u64,
    pub free_vram_bytes: u64,
    pub available_lanes: u32,
    pub queue_depth: u32,
    pub healthy: bool,
}

impl DeviceState {
    pub fn validate(&self) -> Result<(), String> {
        validate_id(&self.device_id, "device_id")?;
        if !matches!(
            self.backend.as_str(),
            "cuda" | "rocm" | "metal" | "vulkan" | "mock"
        ) {
            return Err(format!("unsupported backend {}", self.backend));
        }
        validate_id(&self.architecture, "architecture")?;
        if self.supported_isolation.is_empty()
            || self.supported_isolation.iter().any(|value| {
                !matches!(
                    value.as_str(),
                    "shared" | "sandbox" | "partitioned" | "dedicated"
                )
            })
        {
            return Err("invalid supported_isolation".into());
        }
        if self.total_vram_bytes == 0 || self.free_vram_bytes > self.total_vram_bytes {
            return Err("invalid VRAM accounting".into());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeSnapshot {
    pub node_id: String,
    pub region: String,
    pub accepting_invocations: bool,
    pub active_invocations: u64,
    pub resident_artifacts: Vec<String>,
    pub devices: Vec<DeviceState>,
}

#[derive(Clone)]
pub struct NodeState {
    node_id: String,
    region: String,
    accepting: Arc<AtomicBool>,
    active_invocations: Arc<AtomicU64>,
    resident_artifacts: Arc<RwLock<BTreeSet<String>>>,
    devices: Arc<RwLock<Vec<DeviceState>>>,
}

impl NodeState {
    pub fn new(node_id: impl Into<String>, region: impl Into<String>) -> Result<Self, String> {
        let node_id = node_id.into();
        let region = region.into();
        validate_id(&node_id, "node_id")?;
        validate_id(&region, "region")?;
        Ok(Self {
            node_id,
            region,
            accepting: Arc::new(AtomicBool::new(true)),
            active_invocations: Arc::new(AtomicU64::new(0)),
            resident_artifacts: Default::default(),
            devices: Default::default(),
        })
    }

    pub async fn set_devices(&self, mut devices: Vec<DeviceState>) -> Result<(), String> {
        let mut ids = BTreeSet::new();
        for device in &devices {
            device.validate()?;
            if !ids.insert(device.device_id.clone()) {
                return Err(format!("duplicate device id {}", device.device_id));
            }
        }
        devices.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        *self.devices.write().await = devices;
        Ok(())
    }

    pub async fn mark_resident(&self, digest: impl Into<String>) -> Result<bool, String> {
        let digest = digest.into();
        validate_digest(&digest)?;
        Ok(self.resident_artifacts.write().await.insert(digest))
    }

    pub async fn evict(&self, digest: &str) -> Result<bool, String> {
        validate_digest(digest)?;
        Ok(self.resident_artifacts.write().await.remove(digest))
    }

    pub fn drain(&self) {
        self.accepting.store(false, Ordering::Release);
    }

    pub fn resume(&self) {
        self.accepting.store(true, Ordering::Release);
    }

    pub fn begin_invocation(&self) -> Option<InvocationGuard> {
        if !self.accepting.load(Ordering::Acquire) {
            return None;
        }
        self.active_invocations
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
                current.checked_add(1)
            })
            .ok()?;

        // Close the race where drain() happens between the first accepting check and the
        // increment. A post-drain request rolls its count back and never executes.
        if !self.accepting.load(Ordering::Acquire) {
            self.active_invocations.fetch_sub(1, Ordering::AcqRel);
            return None;
        }
        Some(InvocationGuard {
            active: self.active_invocations.clone(),
        })
    }

    pub async fn snapshot(&self) -> NodeSnapshot {
        NodeSnapshot {
            node_id: self.node_id.clone(),
            region: self.region.clone(),
            accepting_invocations: self.accepting.load(Ordering::Acquire),
            active_invocations: self.active_invocations.load(Ordering::Acquire),
            resident_artifacts: self
                .resident_artifacts
                .read()
                .await
                .iter()
                .cloned()
                .collect(),
            devices: self.devices.read().await.clone(),
        }
    }
}

fn validate_id(value: &str, field: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 256 || value.bytes().any(|b| b.is_ascii_control()) {
        return Err(format!("invalid {field}"));
    }
    Ok(())
}

fn validate_digest(value: &str) -> Result<(), String> {
    if value.len() != 71
        || !value.starts_with("sha256:")
        || !value[7..]
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("resident artifact digest must be lowercase sha256:<64 hex>".into());
    }
    Ok(())
}

pub struct InvocationGuard {
    active: Arc<AtomicU64>,
}

impl Drop for InvocationGuard {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::AcqRel);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn drain_blocks_new_but_preserves_inflight_count() {
        let node = NodeState::new("n1", "local").unwrap();
        let guard = node.begin_invocation().unwrap();
        node.drain();
        assert!(node.begin_invocation().is_none());
        assert_eq!(node.snapshot().await.active_invocations, 1);
        drop(guard);
        assert_eq!(node.snapshot().await.active_invocations, 0);
    }

    #[tokio::test]
    async fn rejects_invalid_device_accounting() {
        let node = NodeState::new("n1", "local").unwrap();
        let result = node
            .set_devices(vec![DeviceState {
                device_id: "gpu0".into(),
                backend: "cuda".into(),
                architecture: "sm_90".into(),
                supported_isolation: vec!["sandbox".into()],
                total_vram_bytes: 10,
                free_vram_bytes: 11,
                available_lanes: 1,
                queue_depth: 0,
                healthy: true,
            }])
            .await;
        assert!(result.is_err());
    }
}
