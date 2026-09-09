#[path = "../src/log_sidecar.rs"]
mod log_sidecar;

use std::{env, fs, path::Path, time::Duration};

use log_sidecar::{
    encode_data_frame, CommandOutcome, LogSidecar, LogStream, PROTOCOL,
};

fn contract_root() -> Option<std::path::PathBuf> {
    env::var_os("GHAIW_LOG_SIDECAR_CONTRACT_ROOT").map(std::path::PathBuf::from)
}

fn decode_hex(value: &str) -> Vec<u8> {
    assert_eq!(value.len() % 2, 0, "hex fixture must have complete bytes");
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let text = std::str::from_utf8(pair).expect("fixture hex is ASCII");
            u8::from_str_radix(text, 16).expect("fixture contains hexadecimal bytes")
        })
        .collect()
}

#[test]
fn admitted_wire_vectors_match_the_real_worker_encoder() {
    let Some(root) = contract_root() else {
        eprintln!("GHAIW_LOG_SIDECAR_CONTRACT_ROOT not set; dedicated contract CI supplies it");
        return;
    };
    let directory = root.join("instances/LogSidecarWireVector/valid");
    let mut entries = fs::read_dir(directory)
        .expect("wire-vector directory")
        .collect::<Result<Vec<_>, _>>()
        .expect("wire-vector entries");
    entries.sort_by_key(|entry| entry.file_name());
    assert!(!entries.is_empty(), "at least one admitted wire vector is required");

    for entry in entries {
        if entry.path().extension().and_then(|value| value.to_str()) != Some("json") {
            continue;
        }
        let vector: serde_json::Value =
            serde_json::from_slice(&fs::read(entry.path()).expect("wire-vector bytes"))
                .expect("wire-vector JSON");
        assert_eq!(vector["frame"]["protocol"].as_str(), Some(PROTOCOL));
        assert_eq!(vector["frame"]["magic"].as_str(), Some("GHLG"));
        assert_eq!(vector["frame"]["version"].as_u64(), Some(1));

        let stream = match vector["frame"]["stream"].as_str() {
            Some("stdout") => LogStream::Stdout,
            Some("stderr") => LogStream::Stderr,
            other => panic!("unexpected admitted stream: {other:?}"),
        };
        assert_eq!(
            vector["frame"]["streamId"].as_u64(),
            Some(stream as u8 as u64)
        );
        let payload = decode_hex(vector["payloadHex"].as_str().expect("payloadHex"));
        let expected_wire = decode_hex(vector["wireHex"].as_str().expect("wireHex"));
        assert_eq!(
            vector["frame"]["payloadLength"].as_u64(),
            Some(payload.len() as u64)
        );
        assert_eq!(encode_data_frame(stream, &payload), expected_wire);
    }
}

#[cfg(unix)]
#[tokio::test]
async fn real_worker_sidecar_emits_contract_shaped_fd3_metadata() {
    let Some(root) = contract_root() else {
        eprintln!("GHAIW_LOG_SIDECAR_CONTRACT_ROOT not set; dedicated contract CI supplies it");
        return;
    };

    let fixture: serde_json::Value = serde_json::from_slice(
        &fs::read(
            root.join("instances/LogSidecarCommandStarted/valid/command-started.json"),
        )
        .expect("metadata fixture"),
    )
    .expect("metadata fixture JSON");

    let unique = format!(
        "ghaiw-sidecar-contract-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_nanos()
    );
    let temp = env::temp_dir().join(unique);
    fs::create_dir_all(&temp).expect("create test directory");
    let metadata_path = temp.join("metadata.ndjson");
    let script = format!(
        "cat <&3 > '{}'; cat >/dev/null",
        metadata_path.display().to_string().replace('\\'', "'\\''")
    );

    env::set_var("BUILD_SERVER_LOG_SIDECAR_BIN", "/bin/sh");
    env::set_var(
        "BUILD_SERVER_LOG_SIDECAR_ARGS_JSON",
        serde_json::to_string(&vec!["-c", script.as_str()]).expect("args JSON"),
    );
    env::set_var("BUILD_SERVER_LOG_SIDECAR_QUEUE_CAPACITY", "8");
    env::set_var("BUILD_SERVER_LOG_SIDECAR_SHUTDOWN_MS", "2000");

    let sidecar = LogSidecar::spawn(Path::new("/var/jobs/build-contract/build.log"), "cargo")
        .await
        .expect("configured fake receiver should spawn");
    sidecar.tap().try_data(LogStream::Stdout, b"contract-data\n");
    sidecar
        .finish(CommandOutcome::Exited {
            success: true,
            code: Some(0),
        })
        .await;

    for key in [
        "BUILD_SERVER_LOG_SIDECAR_BIN",
        "BUILD_SERVER_LOG_SIDECAR_ARGS_JSON",
        "BUILD_SERVER_LOG_SIDECAR_QUEUE_CAPACITY",
        "BUILD_SERVER_LOG_SIDECAR_SHUTDOWN_MS",
    ] {
        env::remove_var(key);
    }

    let metadata = fs::read_to_string(&metadata_path).expect("captured FD3 metadata");
    let lines = metadata
        .lines()
        .map(|line| serde_json::from_str::<serde_json::Value>(line).expect("metadata JSON line"))
        .collect::<Vec<_>>();
    assert_eq!(lines.len(), 2, "start and terminal metadata are required");

    assert_eq!(lines[0]["schemaVersion"], fixture["schemaVersion"]);
    assert_eq!(lines[0]["event"], fixture["event"]);
    assert_eq!(lines[0]["program"], "cargo");
    assert_eq!(lines[0]["jobId"], "build-contract");
    assert!(lines[0]["atMs"].is_u64());

    assert_eq!(lines[1]["schemaVersion"], fixture["schemaVersion"]);
    assert_eq!(lines[1]["event"], "command_finished");
    assert_eq!(lines[1]["program"], "cargo");
    assert_eq!(lines[1]["jobId"], "build-contract");
    assert_eq!(lines[1]["success"], true);
    assert_eq!(lines[1]["exitCode"], 0);
    assert!(lines[1]["droppedFrames"].is_u64());
    assert!(lines[1]["droppedBytes"].is_u64());

    let _ = fs::remove_dir_all(temp);
}
