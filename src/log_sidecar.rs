use std::{
    env,
    io::{self, Write},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Duration,
};

use serde::Serialize;
use tokio::{
    process::{Child, Command},
    sync::mpsc,
    task::JoinHandle,
    time::timeout,
};

const METADATA_FD: i32 = 3;
const DATA_FD: i32 = 4;
const METADATA_SCHEMA: &str = "gha-indie-worker.build-log-metadata/v1";
const MAX_CHUNK_BYTES: usize = 64 * 1024;
const DEFAULT_QUEUE_CHUNKS: usize = 128;
const MAX_QUEUE_CHUNKS: usize = 1024;
const WRITE_TIMEOUT: Duration = Duration::from_millis(250);
const RECEIVER_EXIT_BUDGET: Duration = Duration::from_secs(7);
const RECEIVER_KILL_BUDGET: Duration = Duration::from_millis(500);
pub(crate) const MAX_SHUTDOWN_BUDGET: Duration = Duration::from_secs(8);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LogStream {
    Stdout,
    Stderr,
}

impl LogStream {
    fn wire_name(self) -> &'static str {
        match self {
            Self::Stdout => "stdout",
            Self::Stderr => "stderr",
        }
    }
}

#[derive(Debug)]
struct QueuedChunk {
    job_id: String,
    stream: LogStream,
    sequence: u32,
    timestamp: String,
    data: Vec<u8>,
}

#[derive(Default)]
struct Accounting {
    stdout_sequence: AtomicU32,
    stderr_sequence: AtomicU32,
    worker_sequence: AtomicU32,
    dropped_chunks: AtomicU32,
    dropped_bytes: AtomicU32,
}

impl Accounting {
    fn next_sequence(&self, stream: LogStream) -> u32 {
        let counter = match stream {
            LogStream::Stdout => &self.stdout_sequence,
            LogStream::Stderr => &self.stderr_sequence,
        };
        saturating_increment(counter)
    }

    fn next_worker_sequence(&self) -> u32 {
        saturating_increment(&self.worker_sequence)
    }

    fn record_drop(&self, bytes: usize) {
        saturating_add(&self.dropped_chunks, 1);
        saturating_add(&self.dropped_bytes, u32::try_from(bytes).unwrap_or(u32::MAX));
    }

    fn take_drops(&self) -> (u32, u32) {
        (
            self.dropped_chunks.swap(0, Ordering::AcqRel),
            self.dropped_bytes.swap(0, Ordering::AcqRel),
        )
    }
}

fn saturating_increment(counter: &AtomicU32) -> u32 {
    counter
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
            Some(value.saturating_add(1))
        })
        .unwrap_or_else(|value| value)
}

fn saturating_add(counter: &AtomicU32, amount: u32) {
    let _ = counter.fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
        Some(value.saturating_add(amount))
    });
}

#[derive(Clone)]
struct FanoutSender {
    sender: mpsc::Sender<QueuedChunk>,
    accounting: Arc<Accounting>,
}

impl FanoutSender {
    fn emit(&self, log_path: &Path, stream: LogStream, bytes: &[u8]) {
        let job_id = job_id_from_log_path(log_path);
        for chunk in bytes.chunks(MAX_CHUNK_BYTES) {
            let queued = QueuedChunk {
                job_id: job_id.clone(),
                stream,
                sequence: self.accounting.next_sequence(stream),
                timestamp: timestamp(),
                data: chunk.to_vec(),
            };
            if self.sender.try_send(queued).is_err() {
                self.accounting.record_drop(chunk.len());
            }
        }
    }
}

struct RuntimeState {
    fanout: Option<FanoutSender>,
    supervisor: Option<JoinHandle<()>>,
}

impl RuntimeState {
    fn empty() -> Self {
        Self {
            fanout: None,
            supervisor: None,
        }
    }
}

static STATE: OnceLock<Mutex<RuntimeState>> = OnceLock::new();

fn state() -> &'static Mutex<RuntimeState> {
    STATE.get_or_init(|| Mutex::new(RuntimeState::empty()))
}

#[derive(Debug)]
struct ReceiverSpec {
    program: PathBuf,
    args: Vec<String>,
    environment: Vec<(String, String)>,
    queue_chunks: usize,
}

fn receiver_spec() -> Option<ReceiverSpec> {
    let program = env::var("BUILD_SERVER_LOG_RECEIVER_PROGRAM")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())?;
    let program = PathBuf::from(program);
    if !program.is_absolute() || !program.is_file() {
        tracing::warn!("build log receiver disabled: program must be an existing absolute path");
        return None;
    }

    let args = match env::var("BUILD_SERVER_LOG_RECEIVER_ARGS_JSON") {
        Ok(raw) => match serde_json::from_str::<Vec<String>>(&raw) {
            Ok(args) if args.len() <= 64 && args.iter().all(|arg| arg.len() <= 4096) => args,
            _ => {
                tracing::warn!("build log receiver disabled: args JSON is invalid or exceeds bounds");
                return None;
            }
        },
        Err(_) => Vec::new(),
    };

    let environment = env::var("BUILD_SERVER_LOG_RECEIVER_ENV_ALLOWLIST")
        .ok()
        .into_iter()
        .flat_map(|raw| raw.split(',').map(str::trim).map(str::to_string).collect::<Vec<_>>())
        .filter(|key| valid_env_key(key))
        .take(32)
        .filter_map(|key| env::var(&key).ok().map(|value| (key, value)))
        .collect::<Vec<_>>();

    let queue_chunks = env::var("BUILD_SERVER_LOG_RECEIVER_QUEUE_CHUNKS")
        .ok()
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(DEFAULT_QUEUE_CHUNKS)
        .min(MAX_QUEUE_CHUNKS);

    Some(ReceiverSpec {
        program,
        args,
        environment,
        queue_chunks,
    })
}

fn valid_env_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= 128
        && key
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
}

pub(crate) fn init() {
    let Some(spec) = receiver_spec() else {
        return;
    };
    let mut guard = state().lock().expect("log sidecar state mutex poisoned");
    if guard.fanout.is_some() || guard.supervisor.is_some() {
        return;
    }

    let accounting = Arc::new(Accounting::default());
    let (sender, receiver) = mpsc::channel(spec.queue_chunks);
    match spawn_supervisor(spec, receiver, accounting.clone()) {
        Ok(supervisor) => {
            guard.fanout = Some(FanoutSender { sender, accounting });
            guard.supervisor = Some(supervisor);
            tracing::info!("optional build log receiver enabled");
        }
        Err(_) => {
            tracing::warn!("build log receiver disabled: receiver process could not be started");
        }
    }
}

pub(crate) fn emit(log_path: &Path, stream: LogStream, bytes: &[u8]) {
    let fanout = state()
        .lock()
        .expect("log sidecar state mutex poisoned")
        .fanout
        .clone();
    if let Some(fanout) = fanout {
        fanout.emit(log_path, stream, bytes);
    }
}

pub(crate) async fn shutdown() {
    let supervisor = {
        let mut guard = state().lock().expect("log sidecar state mutex poisoned");
        guard.fanout.take();
        guard.supervisor.take()
    };
    let Some(mut supervisor) = supervisor else {
        return;
    };

    if timeout(MAX_SHUTDOWN_BUDGET, &mut supervisor).await.is_err() {
        supervisor.abort();
        tracing::warn!("build log receiver exceeded the hard 8-second shutdown budget");
    }
}

#[cfg(unix)]
fn spawn_supervisor(
    spec: ReceiverSpec,
    receiver: mpsc::Receiver<QueuedChunk>,
    accounting: Arc<Accounting>,
) -> io::Result<JoinHandle<()>> {
    use std::{
        os::{fd::AsRawFd, unix::net::UnixStream},
        os::unix::process::CommandExt,
    };

    let (metadata_writer, metadata_child) = UnixStream::pair()?;
    let (data_writer, data_child) = UnixStream::pair()?;
    metadata_writer.set_write_timeout(Some(WRITE_TIMEOUT))?;
    data_writer.set_write_timeout(Some(WRITE_TIMEOUT))?;

    let metadata_parent_fd = metadata_child.as_raw_fd();
    let data_parent_fd = data_child.as_raw_fd();
    let mut command = Command::new(&spec.program);
    command
        .args(&spec.args)
        .env_clear()
        .env("INDIEBUILD_LOG_METADATA_FD", METADATA_FD.to_string())
        .env("INDIEBUILD_LOG_DATA_FD", DATA_FD.to_string())
        .env("INDIEBUILD_LOG_PROTOCOL", METADATA_SCHEMA)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    for (key, value) in &spec.environment {
        command.env(key, value);
    }

    // SAFETY: `pre_exec` is limited to async-signal-safe `dup2` calls. The
    // source descriptors are owned by `UnixStream`s kept alive until `spawn`
    // returns. `dup2` atomically installs the read streams at the documented
    // child descriptor numbers and clears close-on-exec on those targets.
    unsafe {
        command.as_std_mut().pre_exec(move || {
            if dup2(metadata_parent_fd, METADATA_FD) == -1 {
                return Err(io::Error::last_os_error());
            }
            if dup2(data_parent_fd, DATA_FD) == -1 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        });
    }

    let child = command.spawn()?;
    drop(metadata_child);
    drop(data_child);

    Ok(tokio::spawn(supervise(
        child,
        receiver,
        metadata_writer,
        data_writer,
        accounting,
    )))
}

#[cfg(unix)]
extern "C" {
    fn dup2(oldfd: i32, newfd: i32) -> i32;
}

#[cfg(not(unix))]
fn spawn_supervisor(
    _spec: ReceiverSpec,
    _receiver: mpsc::Receiver<QueuedChunk>,
    _accounting: Arc<Accounting>,
) -> io::Result<JoinHandle<()>> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "dual inherited descriptors are currently supported on Unix only",
    ))
}

#[cfg(unix)]
async fn supervise(
    mut child: Child,
    receiver: mpsc::Receiver<QueuedChunk>,
    metadata_writer: std::os::unix::net::UnixStream,
    data_writer: std::os::unix::net::UnixStream,
    accounting: Arc<Accounting>,
) {
    let writer = tokio::task::spawn_blocking(move || {
        writer_loop(receiver, metadata_writer, data_writer, &accounting)
    });
    match writer.await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => tracing::warn!("build log receiver I/O closed; primary logging continues"),
        Err(_) => tracing::warn!("build log receiver writer task ended unexpectedly"),
    }

    match timeout(RECEIVER_EXIT_BUDGET, child.wait()).await {
        Ok(Ok(_)) => {}
        Ok(Err(_)) => tracing::warn!("build log receiver wait failed"),
        Err(_) => {
            let _ = child.start_kill();
            let _ = timeout(RECEIVER_KILL_BUDGET, child.wait()).await;
        }
    }
}

#[cfg(unix)]
fn writer_loop(
    mut receiver: mpsc::Receiver<QueuedChunk>,
    mut metadata_writer: std::os::unix::net::UnixStream,
    mut data_writer: std::os::unix::net::UnixStream,
    accounting: &Accounting,
) -> io::Result<()> {
    let mut last_job_id = String::from("worker-global");
    while let Some(chunk) = receiver.blocking_recv() {
        last_job_id.clone_from(&chunk.job_id);
        write_drop_summary(&mut metadata_writer, accounting, &last_job_id)?;
        let metadata = WireMetadata {
            schema_version: METADATA_SCHEMA,
            event: "chunk",
            job_id: &chunk.job_id,
            stream: chunk.stream.wire_name(),
            sequence: chunk.sequence,
            byte_length: u32::try_from(chunk.data.len()).unwrap_or(u32::MAX),
            timestamp: &chunk.timestamp,
            dropped_chunks: None,
            dropped_bytes: None,
        };
        write_metadata(&mut metadata_writer, &metadata)?;
        data_writer.write_all(&chunk.data)?;
    }

    write_drop_summary(&mut metadata_writer, accounting, &last_job_id)?;
    for stream in [LogStream::Stdout, LogStream::Stderr] {
        let sequence = accounting.next_sequence(stream);
        let timestamp = timestamp();
        let metadata = WireMetadata {
            schema_version: METADATA_SCHEMA,
            event: "stream_closed",
            job_id: &last_job_id,
            stream: stream.wire_name(),
            sequence,
            byte_length: 0,
            timestamp: &timestamp,
            dropped_chunks: None,
            dropped_bytes: None,
        };
        write_metadata(&mut metadata_writer, &metadata)?;
    }
    Ok(())
}

#[cfg(unix)]
fn write_drop_summary(
    metadata_writer: &mut std::os::unix::net::UnixStream,
    accounting: &Accounting,
    job_id: &str,
) -> io::Result<()> {
    let (dropped_chunks, dropped_bytes) = accounting.take_drops();
    if dropped_chunks == 0 && dropped_bytes == 0 {
        return Ok(());
    }
    let timestamp = timestamp();
    let metadata = WireMetadata {
        schema_version: METADATA_SCHEMA,
        event: "dropped",
        job_id,
        stream: "worker",
        sequence: accounting.next_worker_sequence(),
        byte_length: 0,
        timestamp: &timestamp,
        dropped_chunks: Some(dropped_chunks),
        dropped_bytes: Some(dropped_bytes),
    };
    write_metadata(metadata_writer, &metadata)
}

#[cfg(unix)]
fn write_metadata(
    writer: &mut std::os::unix::net::UnixStream,
    metadata: &WireMetadata<'_>,
) -> io::Result<()> {
    serde_json::to_writer(&mut *writer, metadata).map_err(io::Error::other)?;
    writer.write_all(b"\n")
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WireMetadata<'a> {
    schema_version: &'static str,
    event: &'static str,
    job_id: &'a str,
    stream: &'static str,
    sequence: u32,
    byte_length: u32,
    timestamp: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    dropped_chunks: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    dropped_bytes: Option<u32>,
}

fn timestamp() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

fn job_id_from_log_path(log_path: &Path) -> String {
    log_path
        .parent()
        .and_then(Path::file_name)
        .and_then(|value| value.to_str())
        .filter(|value| !value.is_empty())
        .unwrap_or("worker-global")
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_queue_drops_sidecar_copy_without_blocking() {
        let accounting = Arc::new(Accounting::default());
        let (sender, mut receiver) = mpsc::channel(1);
        let fanout = FanoutSender {
            sender,
            accounting: accounting.clone(),
        };
        let path = Path::new("/tmp/jobs/build-1/build.log");
        fanout.emit(path, LogStream::Stdout, b"first");
        fanout.emit(path, LogStream::Stdout, b"second");
        let first = receiver.try_recv().expect("first sidecar copy queued");
        assert_eq!(first.data, b"first");
        assert_eq!(accounting.dropped_chunks.load(Ordering::Acquire), 1);
        assert_eq!(accounting.dropped_bytes.load(Ordering::Acquire), 6);
    }

    #[test]
    fn metadata_uses_contract_wire_names() {
        let metadata = WireMetadata {
            schema_version: METADATA_SCHEMA,
            event: "chunk",
            job_id: "build-1",
            stream: "stderr",
            sequence: 4,
            byte_length: 3,
            timestamp: "2026-09-09T19:45:00Z",
            dropped_chunks: None,
            dropped_bytes: None,
        };
        let encoded = serde_json::to_string(&metadata).expect("serialize metadata");
        assert!(encoded.contains("\"schemaVersion\""));
        assert!(encoded.contains("\"byteLength\":3"));
        assert!(encoded.contains("\"stream\":\"stderr\""));
    }

    #[test]
    fn log_path_derives_stable_job_id() {
        assert_eq!(
            job_id_from_log_path(Path::new("/var/lib/dd-build-server/jobs/build-42/build.log")),
            "build-42"
        );
        assert_eq!(job_id_from_log_path(Path::new("build.log")), "worker-global");
    }

    #[test]
    fn shutdown_budget_is_hard_capped_at_eight_seconds() {
        assert_eq!(MAX_SHUTDOWN_BUDGET, Duration::from_secs(8));
    }
}
