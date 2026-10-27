#[path = "../src/log_sidecar.rs"]
mod log_sidecar;

use std::{env, fs, path::PathBuf, time::Duration};

use log_sidecar::LogStream;

#[cfg(unix)]
fn unique_temp_dir() -> PathBuf {
    env::temp_dir().join(format!(
        "ghaiw-build-log-e2e-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_nanos()
    ))
}

#[cfg(unix)]
#[tokio::test]
async fn real_worker_producer_streams_exact_bytes_through_reference_sidecar() {
    let Some(sidecar_bin) = env::var_os("GHAIW_REFERENCE_SIDECAR_BIN").map(PathBuf::from) else {
        eprintln!(
            "GHAIW_REFERENCE_SIDECAR_BIN not set; dedicated contract CI supplies the merged reference receiver"
        );
        return;
    };
    assert!(
        sidecar_bin.is_absolute(),
        "reference sidecar path must be absolute"
    );
    assert!(sidecar_bin.is_file(), "reference sidecar binary must exist");

    let temp = unique_temp_dir();
    let job_dir = temp.join("build-e2e");
    fs::create_dir_all(&job_dir).expect("create e2e job directory");
    let log_path = job_dir.join("build.log");
    let stdout_path = temp.join("receiver.stdout.bin");
    let stderr_path = temp.join("receiver.stderr.bin");

    let script = "exec \"$1\" >\"$2\" 2>\"$3\"";
    env::set_var("BUILD_SERVER_LOG_RECEIVER_PROGRAM", "/bin/sh");
    env::set_var(
        "BUILD_SERVER_LOG_RECEIVER_ARGS_JSON",
        serde_json::to_string(&vec![
            "-c".to_string(),
            script.to_string(),
            "ghaiw-build-log-e2e".to_string(),
            sidecar_bin.to_string_lossy().into_owned(),
            stdout_path.to_string_lossy().into_owned(),
            stderr_path.to_string_lossy().into_owned(),
        ])
        .expect("receiver args json"),
    );
    env::set_var("BUILD_SERVER_LOG_RECEIVER_QUEUE_CHUNKS", "8");

    log_sidecar::init();
    log_sidecar::register_job_from_clone(
        &log_path,
        "https://github.com/gha-indie-worker/gha-indie-worker.rs.git",
    );

    let stdout_payload = b"worker-to-sidecar stdout\n";
    let stderr_payload = [0xff, 0x00, b'\n', b'e', b'r', b'r', b'\n'];
    log_sidecar::emit(&log_path, LogStream::Stdout, stdout_payload);
    log_sidecar::emit(&log_path, LogStream::Stderr, &stderr_payload);
    log_sidecar::shutdown().await;

    for key in [
        "BUILD_SERVER_LOG_RECEIVER_PROGRAM",
        "BUILD_SERVER_LOG_RECEIVER_ARGS_JSON",
        "BUILD_SERVER_LOG_RECEIVER_QUEUE_CHUNKS",
    ] {
        env::remove_var(key);
    }

    assert_eq!(
        fs::read(&stdout_path).expect("captured receiver stdout"),
        stdout_payload,
        "reference sidecar must receive the worker stdout bytes exactly"
    );
    assert_eq!(
        fs::read(&stderr_path).expect("captured receiver stderr"),
        stderr_payload,
        "reference sidecar must receive non-UTF8 worker stderr bytes exactly"
    );

    let _ = fs::remove_dir_all(temp);
}
