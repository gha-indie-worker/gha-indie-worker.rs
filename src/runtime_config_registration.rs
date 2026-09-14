//! Minimal runtime-config control-plane registration boundary.
//!
//! The worker only needs registration at startup. Keeping that small HTTP
//! boundary here avoids requiring the public worker crate to checkout a private
//! split-repo client implementation. No credential value is ever logged.

use std::{env, time::Duration};

use reqwest::{redirect::Policy, Client};
use serde::Serialize;

const INITIAL_BACKOFF: Duration = Duration::from_secs(15);
const MAX_BACKOFF: Duration = Duration::from_secs(300);

#[derive(Clone, Debug, PartialEq, Eq)]
struct RegistrationConfig {
    service_name: String,
    scope: String,
    environment: String,
    register_url: String,
    apply_url: String,
    server_auth: Option<String>,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
struct RegistrationRequest {
    env: String,
    name: String,
    scope: String,
    apply_url: String,
    labels: Option<Vec<String>>,
}

impl RegistrationConfig {
    fn from_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Option<Self> {
        let service_name = nonempty(lookup("RUNTIME_CONFIG_SERVICE_NAME"))?;
        let register_url = nonempty(lookup("RUNTIME_CONFIG_REGISTER_URL"))?;
        let apply_url = nonempty(lookup("RUNTIME_CONFIG_APPLY_URL"))?;
        let scope = nonempty(lookup("RUNTIME_CONFIG_SCOPE")).unwrap_or_else(|| service_name.clone());
        let environment = normalize_environment(nonempty(lookup("RUNTIME_CONFIG_ENV")).as_deref());
        let server_auth = nonempty(lookup("RUNTIME_CONFIG_SERVER_SECRET"));
        Some(Self {
            service_name,
            scope,
            environment,
            register_url,
            apply_url,
            server_auth,
        })
    }

    fn from_env() -> Option<Self> {
        Self::from_lookup(|key| env::var(key).ok())
    }

    fn request(&self) -> RegistrationRequest {
        RegistrationRequest {
            env: self.environment.clone(),
            name: self.service_name.clone(),
            scope: self.scope.clone(),
            apply_url: self.apply_url.clone(),
            labels: None,
        }
    }
}

fn nonempty(value: Option<String>) -> Option<String> {
    value.and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

fn normalize_environment(value: Option<&str>) -> String {
    match value.map(str::trim).map(str::to_ascii_lowercase).as_deref() {
        Some("prod" | "production") => "prod".to_owned(),
        _ => "stage".to_owned(),
    }
}

fn next_backoff(current: Duration) -> Duration {
    current.saturating_mul(2).min(MAX_BACKOFF)
}

async fn register_once(client: &Client, config: &RegistrationConfig) -> Result<bool, reqwest::Error> {
    let mut request = client.post(&config.register_url).json(&config.request());
    if let Some(server_auth) = config.server_auth.as_deref() {
        request = request.header("x-server-auth", server_auth);
    }
    Ok(request.send().await?.status().is_success())
}

/// Register this worker with the runtime-config control plane when configured.
///
/// Missing configuration disables registration. Failed attempts use bounded
/// exponential backoff and never print the server credential.
pub async fn register_with_control_plane() {
    let Some(config) = RegistrationConfig::from_env() else {
        tracing::info!("runtime-config registration disabled: required endpoint settings are absent");
        return;
    };

    let client = Client::builder()
        .timeout(Duration::from_secs(10))
        .redirect(Policy::none())
        .build()
        .expect("runtime-config registration HTTP client must build");

    let mut backoff = INITIAL_BACKOFF;
    loop {
        match register_once(&client, &config).await {
            Ok(true) => {
                tracing::info!(
                    service = %config.service_name,
                    scope = %config.scope,
                    environment = %config.environment,
                    "registered with runtime-config control plane"
                );
                return;
            }
            Ok(false) => tracing::warn!(
                service = %config.service_name,
                "runtime-config registration returned a non-success status"
            ),
            Err(_) => tracing::warn!(
                service = %config.service_name,
                "runtime-config registration request failed"
            ),
        }
        tokio::time::sleep(backoff).await;
        backoff = next_backoff(backoff);
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::*;

    fn config(values: &[(&str, &str)]) -> Option<RegistrationConfig> {
        let values: BTreeMap<_, _> = values.iter().copied().collect();
        RegistrationConfig::from_lookup(|key| values.get(key).map(|value| (*value).to_owned()))
    }

    #[test]
    fn missing_required_endpoint_disables_registration() {
        assert!(config(&[("RUNTIME_CONFIG_SERVICE_NAME", "worker")]).is_none());
    }

    #[test]
    fn scope_defaults_to_service_and_environment_defaults_to_stage() {
        let config = config(&[
            ("RUNTIME_CONFIG_SERVICE_NAME", "worker"),
            ("RUNTIME_CONFIG_REGISTER_URL", "https://control.example/register"),
            ("RUNTIME_CONFIG_APPLY_URL", "https://worker.example/apply"),
        ])
        .expect("complete config");
        assert_eq!(config.scope, "worker");
        assert_eq!(config.environment, "stage");
    }

    #[test]
    fn production_alias_normalizes_to_prod() {
        assert_eq!(normalize_environment(Some("production")), "prod");
        assert_eq!(normalize_environment(Some("PROD")), "prod");
        assert_eq!(normalize_environment(Some("staging")), "stage");
    }

    #[test]
    fn request_contains_no_server_auth_credential() {
        let config = config(&[
            ("RUNTIME_CONFIG_SERVICE_NAME", "worker"),
            ("RUNTIME_CONFIG_REGISTER_URL", "https://control.example/register"),
            ("RUNTIME_CONFIG_APPLY_URL", "https://worker.example/apply"),
            ("RUNTIME_CONFIG_SERVER_SECRET", "never-serialize-this"),
        ])
        .expect("complete config");
        let json = serde_json::to_string(&config.request()).expect("request serializes");
        assert!(!json.contains("never-serialize-this"));
        assert!(!json.contains("server_auth"));
    }

    #[test]
    fn retry_backoff_is_bounded() {
        assert_eq!(next_backoff(Duration::from_secs(15)), Duration::from_secs(30));
        assert_eq!(next_backoff(Duration::from_secs(240)), Duration::from_secs(300));
        assert_eq!(next_backoff(Duration::from_secs(300)), Duration::from_secs(300));
    }
}
