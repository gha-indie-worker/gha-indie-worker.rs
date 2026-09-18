//! Worker-local telemetry boundary built on the public `tracing` facade.
//!
//! Export/subscriber installation remains an outer deployment concern. This
//! module keeps request spans and service identity available without requiring a
//! private source checkout merely to compile the public worker crate.

use std::time::Instant;

use axum::{extract::Request, middleware::Next, response::Response};

#[derive(Debug)]
pub struct TelemetryGuard {
    service: &'static str,
}

impl Drop for TelemetryGuard {
    fn drop(&mut self) {
        tracing::info!(service = self.service, "telemetry boundary stopped");
    }
}

pub fn init(service: &'static str) -> TelemetryGuard {
    tracing::info!(service, "telemetry boundary initialized");
    TelemetryGuard { service }
}

pub async fn trace_request(request: Request, next: Next) -> Response {
    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let started = Instant::now();
    let response = next.run(request).await;
    tracing::info!(
        http.method = %method,
        http.route = %path,
        http.status_code = response.status().as_u16(),
        duration_ms = started.elapsed().as_millis() as u64,
        "http request completed"
    );
    response
}

#[cfg(test)]
mod tests {
    use axum::{body::Body, middleware, routing::get, Router};
    use tower::ServiceExt;

    use super::*;

    #[test]
    fn telemetry_guard_preserves_static_service_identity() {
        let guard = init("dd-build-server");
        assert_eq!(guard.service, "dd-build-server");
    }

    #[tokio::test]
    async fn request_layer_preserves_response_status() {
        let app = Router::new()
            .route(
                "/health",
                get(|| async { axum::http::StatusCode::NO_CONTENT }),
            )
            .layer(middleware::from_fn(trace_request));
        let response = app
            .oneshot(
                Request::builder()
                    .uri("/health")
                    .body(Body::empty())
                    .expect("request"),
            )
            .await
            .expect("response");
        assert_eq!(response.status(), axum::http::StatusCode::NO_CONTENT);
    }
}
