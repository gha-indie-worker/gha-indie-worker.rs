use std::env;

const RECEIVER_ENV_ALLOWLIST: &str = "BUILD_SERVER_LOG_RECEIVER_ENV_ALLOWLIST";
const MAX_RECEIVER_ENV_KEYS: usize = 32;

const RESERVED_OR_SECRET_KEYS: &[&str] = &[
    // Producer-owned protocol/control-plane variables. The receiver may observe
    // these only from the worker's canonical values, never by overriding them
    // through the parent environment allowlist.
    "INDIEBUILD_LOG_METADATA_FD",
    "INDIEBUILD_LOG_DATA_FD",
    "INDIEBUILD_LOG_PROTOCOL",
    "BUILD_SERVER_LOG_RECEIVER_PROGRAM",
    "BUILD_SERVER_LOG_RECEIVER_ARGS_JSON",
    "BUILD_SERVER_LOG_RECEIVER_ENV_ALLOWLIST",
    "BUILD_SERVER_LOG_RECEIVER_QUEUE_CHUNKS",
    // Existing worker credentials/authorities. A user-defined receiver that
    // needs authentication must receive a receiver-specific credential name;
    // it must never borrow the worker's GitHub/AWS/database/control secrets.
    "BUILD_SERVER_GIT_TOKEN",
    "GH_PAT",
    "GITHUB_TOKEN",
    "GH_TOKEN",
    "BUILD_SERVER_AUTH_SECRET",
    "SERVER_AUTH_SECRET",
    "BUILD_SERVER_DATABASE_URL",
    "DATABASE_URL",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "FIDUCIA_API_KEY",
    "BUILD_SERVER_GITHUB_WEBHOOK_SECRET",
    "BUILD_SERVER_REGISTRY_WEBHOOK_SECRET",
    "GH_SECRETS_SYNC_TOKEN",
    "BUILD_SERVER_LAMBDA_AUTH_SECRET",
    // Process/runtime injection controls. Passing one of these can change what
    // executable code is loaded before the receiver gets a chance to enforce
    // its own policy, so an explicit allowlist is not sufficient authority.
    "LD_PRELOAD",
    "LD_LIBRARY_PATH",
    "LD_AUDIT",
    "LD_DEBUG",
    "DYLD_INSERT_LIBRARIES",
    "DYLD_LIBRARY_PATH",
    "DYLD_FRAMEWORK_PATH",
    "BASH_ENV",
    "ENV",
    "SHELLOPTS",
    "PS4",
    "NODE_OPTIONS",
    "PYTHONPATH",
    "PYTHONHOME",
    "PYTHONSTARTUP",
    "PYTHONINSPECT",
    "PERL5OPT",
    "PERL5LIB",
    "RUBYOPT",
    "RUBYLIB",
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_SYSTEM",
    "GIT_ASKPASS",
    "SSH_ASKPASS",
    "RUSTC_WRAPPER",
    "RUSTC_WORKSPACE_WRAPPER",
    "RUSTFLAGS",
    "SSLKEYLOGFILE",
];

fn valid_env_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= 128
        && key
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
}

fn reserved_or_unsafe(key: &str) -> bool {
    RESERVED_OR_SECRET_KEYS.contains(&key)
        || key.starts_with("INDIEBUILD_LOG_")
        || key.starts_with("BUILD_SERVER_LOG_RECEIVER_")
}

pub(crate) fn allowlist_is_safe(raw: &str) -> bool {
    let mut count = 0_usize;
    for key in raw.split(',').map(str::trim).filter(|key| !key.is_empty()) {
        count = count.saturating_add(1);
        if count > MAX_RECEIVER_ENV_KEYS || !valid_env_key(key) || reserved_or_unsafe(key) {
            return false;
        }
    }
    true
}

/// Defense-in-depth gate for the optional log receiver process.
///
/// `log_sidecar` already starts the receiver with `env_clear()`. This gate
/// constrains the *explicit* inheritance escape hatch so operators cannot
/// accidentally reintroduce worker credentials, replace producer-owned FD
/// metadata, or enable dynamic-loader/runtime code injection in the receiver.
///
/// The diagnostic caller deliberately reports only a fixed reason. It never
/// logs the rejected variable name or value.
pub(crate) fn receiver_environment_is_safe() -> bool {
    env::var(RECEIVER_ENV_ALLOWLIST)
        .map(|raw| allowlist_is_safe(&raw))
        .unwrap_or(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_receiver_specific_sink_configuration() {
        assert!(allowlist_is_safe(
            "OTEL_EXPORTER_OTLP_ENDPOINT,SUPABASE_URL,SUPABASE_ANON_KEY,LOG_SINK_API_KEY"
        ));
    }

    #[test]
    fn rejects_worker_credentials() {
        for key in [
            "GH_PAT",
            "GITHUB_TOKEN",
            "AWS_SECRET_ACCESS_KEY",
            "DATABASE_URL",
            "FIDUCIA_API_KEY",
            "BUILD_SERVER_GITHUB_WEBHOOK_SECRET",
        ] {
            assert!(!allowlist_is_safe(key), "{key} must stay outside receiver authority");
        }
    }

    #[test]
    fn rejects_protocol_override_and_receiver_self_configuration() {
        for key in [
            "INDIEBUILD_LOG_METADATA_FD",
            "INDIEBUILD_LOG_DATA_FD",
            "INDIEBUILD_LOG_PROTOCOL",
            "BUILD_SERVER_LOG_RECEIVER_PROGRAM",
            "BUILD_SERVER_LOG_RECEIVER_QUEUE_CHUNKS",
        ] {
            assert!(!allowlist_is_safe(key), "{key} must remain producer-owned");
        }
    }

    #[test]
    fn rejects_runtime_injection_controls() {
        for key in [
            "LD_PRELOAD",
            "DYLD_INSERT_LIBRARIES",
            "BASH_ENV",
            "NODE_OPTIONS",
            "PYTHONPATH",
            "GIT_CONFIG_GLOBAL",
            "RUSTC_WRAPPER",
            "SSLKEYLOGFILE",
        ] {
            assert!(!allowlist_is_safe(key), "{key} can change receiver execution semantics");
        }
    }

    #[test]
    fn rejects_invalid_names_and_oversized_lists() {
        assert!(!allowlist_is_safe("good-name"));
        assert!(!allowlist_is_safe("lowercase"));
        let oversized = (0..=MAX_RECEIVER_ENV_KEYS)
            .map(|index| format!("LOG_SINK_{index}"))
            .collect::<Vec<_>>()
            .join(",");
        assert!(!allowlist_is_safe(&oversized));
    }
}
