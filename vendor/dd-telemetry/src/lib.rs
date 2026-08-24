#[derive(Debug, Default)]
pub struct TelemetryGuard;

pub fn init(_service_name: &str) -> TelemetryGuard {
    TelemetryGuard
}

pub fn http_trace_layer() -> tower::layer::util::Identity {
    tower::layer::util::Identity::new()
}
