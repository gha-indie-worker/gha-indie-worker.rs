use std::{
    collections::HashMap,
    env, io,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Duration,
};

#[cfg(unix)]
use std::{
    os::fd::{AsRawFd, OwnedFd},
    process::Stdio,
};

use serde::Serialize;
use tokio::{sync::mpsc, task::JoinHandle, time::timeout};

#[cfg(unix)]
use tokio::{
    io::AsyncWriteExt,
    net::unix::pipe::{self, Sender},
    process::{Child, Command},
};

#[cfg(unix)]
const METADATA_FD: i32 = 3;
#[cfg(unix)]
const DATA_FD: i32 = 4;
const METADATA_SCHEMA: &str = "gha-indie-worker.build-log-metadata/v1";
const MAX_CHUNK_BYTES: usize = 64 * 1024;
const DEFAULT_QUEUE_CHUNKS: usize = 128;
const MAX_QUEUE_CHUNKS: usize = 1024;
const MAX_JOB_CONTEXTS: usize = 4096;
#[cfg(unix)]
const WRITE_TIMEOUT: Duration = Duration::from_millis(250);
#[cfg(unix)]
const RECEIVER_EXIT_BUDGET: Duration = Duration::from_secs(7);
#[cfg(unix)]
const RECEIVER_KILL_BUDGET: Duration = Duration::from_millis(500);
pub(crate) const MAX_SHUTDOWN_BUDGET: Duration = Duration::from_secs(8);
const WORKER_GLOBAL_JOB_ID: &str = "worker-global";

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

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct JobContext {
    repository: Option<String>,
    github_organization: Option<String>,
}

#[derive(Debug)]
struct QueuedChunk {
    job_id: String,
    stream: LogStream,
    sequence: u32,
    timestamp: String,
    repository: Option<String>,
    github_organization: Option<String>,
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
        saturating_add(
            &self.dropped_bytes,
            u32::try_from(bytes).unwrap_or(u32::MAX),
        );
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
    fn emit(&self, job_id: &str, context: &JobContext, stream: LogStream, bytes: &[u8]) {
        for chunk in bytes.chunks(MAX_CHUNK_BYTES) {
            let queued = QueuedChunk {
                job_id: job_id.to_string(),
                stream,
                sequence: self.accounting.next_sequence(stream),
                timestamp: timestamp(),
                repository: context.repository.clone(),
                github_organization: context.github_organization.clone(),
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
    jobs: HashMap<String, JobContext>,
}

impl RuntimeState {
    fn empty() -> Self {
        Self {
            fanout: None,
            supervisor: None,
            jobs: HashMap::new(),
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
                tracing::warn!(
                    "build log receiver disabled: args JSON is invalid or exceeds bounds"
                );
                return None;
            }
        },
        Err(_) => Vec::new(),
    };

    let environment = env::var("BUILD_SERVER_LOG_RECEIVER_ENV_ALLOWLIST")
        .ok()
        .map(|raw| {
            raw.split(',')
                .map(str::trim)
                .filter(|key| valid_env_key(key))
                .take(32)
                .filter_map(|key| env::var(key).ok().map(|value| (key.to_string(), value)))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

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

pub(crate) fn register_job_from_clone(log_path: &Path, repo_url: &str) {
    let job_id = job_id_from_log_path(log_path);
    let context = github_job_context(repo_url);
    let mut guard = state().lock().expect("log sidecar state mutex poisoned");
    if guard.jobs.len() >= MAX_JOB_CONTEXTS && !guard.jobs.contains_key(&job_id) {
        if let Some(oldest_key) = guard.jobs.keys().next().cloned() {
            guard.jobs.remove(&oldest_key);
        }
    }
    guard.jobs.insert(job_id, context);
}

pub(crate) fn emit(log_path: &Path, stream: LogStream, bytes: &[u8]) {
    let job_id = job_id_from_log_path(log_path);
    let (fanout, context) = {
        let guard = state().lock().expect("log sidecar state mutex poisoned");
        (
            guard.fanout.clone(),
            guard.jobs.get(&job_id).cloned().unwrap_or_default(),
        )
    };
    if let Some(fanout) = fanout {
        fanout.emit(&job_id, &context, stream, bytes);
    }
}

pub(crate) async fn shutdown() {
    let supervisor = {
        let mut guard = state().lock().expect("log sidecar state mutex poisoned");
        guard.fanout.take();
        guard.jobs.clear();
        guard.supervisor.take()
    };
    let Some(supervisor) = supervisor else {
        return;
    };

    if !await_supervisor_with_budget(supervisor, MAX_SHUTDOWN_BUDGET).await {
        tracing::warn!("build log receiver exceeded the hard 8-second shutdown budget");
    }
}

async fn await_supervisor_with_budget(mut supervisor: JoinHandle<()>, budget: Duration) -> bool {
    if timeout(budget, &mut supervisor).await.is_ok() {
        return true;
    }
    supervisor.abort();
    false
}

#[cfg(unix)]
fn move_out_of_protocol_fds(fd: OwnedFd) -> io::Result<OwnedFd> {
    if !matches!(fd.as_raw_fd(), METADATA_FD | DATA_FD) {
        return Ok(fd);
    }

    // `dup2(oldfd, oldfd)` does not clear close-on-exec. Keep every
    // protocol descriptor occupied while cloning until the source is
    // outside FD3/FD4, then `dup2` can safely install inheritable
    // blocking read ends at the documented child descriptors.
    let mut held = Vec::new();
    let mut current = fd;
    while matches!(current.as_raw_fd(), METADATA_FD | DATA_FD) {
        let next = current.try_clone()?;
        held.push(current);
        current = next;
    }
    drop(held);
    Ok(current)
}

#[cfg(unix)]
fn spawn_supervisor(
    spec: ReceiverSpec,
    receiver: mpsc::Receiver<QueuedChunk>,
    accounting: Arc<Accounting>,
) -> io::Result<JoinHandle<()>> {
    use std::os::unix::process::CommandExt;

    // The reference receiver reopens FD3/FD4 through `/dev/fd`, so
    // these must be real pipes rather than Unix-domain socketpairs.
    // Tokio keeps the parent write ends asynchronous and cancellable;
    // the child receives blocking read ends for ordinary file reads.
    let (metadata_writer, metadata_child) = pipe::pipe()?;
    let (data_writer, data_child) = pipe::pipe()?;
    let metadata_child = move_out_of_protocol_fds(metadata_child.into_blocking_fd()?)?;
    let data_child = move_out_of_protocol_fds(data_child.into_blocking_fd()?)?;

    let metadata_child_fd = metadata_child.as_raw_fd();
    let data_child_fd = data_child.as_raw_fd();
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

    // SAFETY: `pre_exec` is limited to async-signal-safe `dup2` calls.
    // Sources are guaranteed outside FD3/FD4, so each `dup2` also
    // clears close-on-exec on its target. OwnedFd sources stay alive
    // until `spawn` returns.
    unsafe {
        command.as_std_mut().pre_exec(move || {
            if dup2(metadata_child_fd, METADATA_FD) == -1 {
                return Err(io::Error::last_os_error());
            }
            if dup2(data_child_fd, DATA_FD) == -1 {
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
    metadata_writer: Sender,
    data_writer: Sender,
    accounting: Arc<Accounting>,
) {
    match writer_loop(receiver, metadata_writer, data_writer, &accounting).await {
        Ok(()) => {}
        Err(_) => tracing::warn!("build log receiver I/O closed; primary logging continues"),
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
async fn writer_loop(
    mut receiver: mpsc::Receiver<QueuedChunk>,
    mut metadata_writer: Sender,
    mut data_writer: Sender,
    accounting: &Accounting,
) -> io::Result<()> {
    while let Some(chunk) = receiver.recv().await {
        write_drop_summary(&mut metadata_writer, accounting).await?;
        let metadata = WireMetadata {
            schema_version: METADATA_SCHEMA,
            event: "chunk",
            job_id: &chunk.job_id,
            stream: chunk.stream.wire_name(),
            sequence: chunk.sequence,
            byte_length: u32::try_from(chunk.data.len()).unwrap_or(u32::MAX),
            timestamp: &chunk.timestamp,
            repository: chunk.repository.as_deref(),
            github_organization: chunk.github_organization.as_deref(),
            dropped_chunks: None,
            dropped_bytes: None,
        };
        let write = async {
            write_metadata(&mut metadata_writer, &metadata).await?;
            data_writer.write_all(&chunk.data).await
        };
        match timeout(WRITE_TIMEOUT, write).await {
            Ok(result) => result?,
            Err(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "sidecar write timed out",
                ))
            }
        }
    }

    write_drop_summary(&mut metadata_writer, accounting).await?;
    for stream in [LogStream::Stdout, LogStream::Stderr] {
        let sequence = accounting.next_sequence(stream);
        let timestamp = timestamp();
        let metadata = WireMetadata {
            schema_version: METADATA_SCHEMA,
            event: "stream_closed",
            job_id: WORKER_GLOBAL_JOB_ID,
            stream: stream.wire_name(),
            sequence,
            byte_length: 0,
            timestamp: &timestamp,
            repository: None,
            github_organization: None,
            dropped_chunks: None,
            dropped_bytes: None,
        };
        match timeout(
            WRITE_TIMEOUT,
            write_metadata(&mut metadata_writer, &metadata),
        )
        .await
        {
            Ok(result) => result?,
            Err(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "sidecar close timed out",
                ))
            }
        }
    }
    Ok(())
}

#[cfg(unix)]
async fn write_drop_summary(
    metadata_writer: &mut Sender,
    accounting: &Accounting,
) -> io::Result<()> {
    let (dropped_chunks, dropped_bytes) = accounting.take_drops();
    if dropped_chunks == 0 && dropped_bytes == 0 {
        return Ok(());
    }
    let timestamp = timestamp();
    let metadata = WireMetadata {
        schema_version: METADATA_SCHEMA,
        event: "dropped",
        job_id: WORKER_GLOBAL_JOB_ID,
        stream: "worker",
        sequence: accounting.next_worker_sequence(),
        byte_length: 0,
        timestamp: &timestamp,
        repository: None,
        github_organization: None,
        dropped_chunks: Some(dropped_chunks),
        dropped_bytes: Some(dropped_bytes),
    };
    match timeout(WRITE_TIMEOUT, write_metadata(metadata_writer, &metadata)).await {
        Ok(result) => result,
        Err(_) => Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "sidecar drop accounting timed out",
        )),
    }
}

#[cfg(unix)]
async fn write_metadata(writer: &mut Sender, metadata: &WireMetadata<'_>) -> io::Result<()> {
    let mut encoded = serde_json::to_vec(metadata).map_err(io::Error::other)?;
    encoded.push(b'\n');
    writer.write_all(&encoded).await
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
    repository: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    github_organization: Option<&'a str>,
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
        .unwrap_or(WORKER_GLOBAL_JOB_ID)
        .to_string()
}

fn github_job_context(repo_url: &str) -> JobContext {
    let path = repo_url
        .strip_prefix("https://github.com/")
        .or_else(|| repo_url.strip_prefix("ssh://git@github.com/"))
        .or_else(|| repo_url.strip_prefix("git@github.com:"));
    let Some(path) = path else {
        return JobContext::default();
    };
    let path = path.trim_end_matches('/').trim_end_matches(".git");
    let mut components = path.split('/');
    let Some(owner) = components
        .next()
        .filter(|value| valid_github_component(value))
    else {
        return JobContext::default();
    };
    let Some(repo) = components
        .next()
        .filter(|value| valid_github_component(value))
    else {
        return JobContext::default();
    };
    if components.next().is_some() {
        return JobContext::default();
    }
    JobContext {
        repository: Some(format!("{owner}/{repo}")),
        github_organization: Some(owner.to_string()),
    }
}

fn valid_github_component(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 100
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    use std::io::Read as _;

    #[cfg(unix)]
    fn pipe_pair() -> (Sender, std::fs::File) {
        let (writer, reader) = pipe::pipe().expect("anonymous unix pipe");
        let reader = reader
            .into_blocking_fd()
            .expect("blocking anonymous pipe reader");
        (writer, std::fs::File::from(reader))
    }

    #[cfg(unix)]
    fn queued(stream: LogStream, sequence: u32, data: &[u8]) -> QueuedChunk {
        QueuedChunk {
            job_id: "build-1".to_string(),
            stream,
            sequence,
            timestamp: "2026-09-09T19:45:00Z".to_string(),
            repository: Some("gha-indie-worker/gha-indie-worker.rs".to_string()),
            github_organization: Some("gha-indie-worker".to_string()),
            data: data.to_vec(),
        }
    }

    #[test]
    fn full_queue_drops_sidecar_copy_without_blocking() {
        let accounting = Arc::new(Accounting::default());
        let (sender, mut receiver) = mpsc::channel(1);
        let fanout = FanoutSender {
            sender,
            accounting: accounting.clone(),
        };
        fanout.emit(
            "build-1",
            &github_job_context("https://github.com/gha-indie-worker/gha-indie-worker.rs.git"),
            LogStream::Stdout,
            b"first",
        );
        fanout.emit(
            "build-1",
            &JobContext::default(),
            LogStream::Stdout,
            b"second",
        );
        let first = receiver.try_recv().expect("first sidecar copy queued");
        assert_eq!(first.data, b"first");
        assert_eq!(
            first.repository.as_deref(),
            Some("gha-indie-worker/gha-indie-worker.rs")
        );
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
            repository: Some("gha-indie-worker/gha-indie-worker.rs"),
            github_organization: Some("gha-indie-worker"),
            dropped_chunks: None,
            dropped_bytes: None,
        };
        let encoded = serde_json::to_string(&metadata).expect("serialize metadata");
        assert!(encoded.contains("\"schemaVersion\""));
        assert!(encoded.contains("\"byteLength\":3"));
        assert!(encoded.contains("\"stream\":\"stderr\""));
        assert!(encoded.contains("\"githubOrganization\":\"gha-indie-worker\""));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn writer_loop_preserves_metadata_order_and_raw_bytes() {
        let accounting = Accounting::default();
        let (sender, receiver) = mpsc::channel(4);
        sender
            .try_send(queued(LogStream::Stdout, 1, b"one"))
            .expect("queue stdout");
        sender
            .try_send(queued(LogStream::Stderr, 1, &[0xff, 0x00]))
            .expect("queue stderr");
        sender
            .try_send(queued(LogStream::Stdout, 2, b"two"))
            .expect("queue second stdout");
        drop(sender);

        let (metadata_writer, mut metadata_reader) = pipe_pair();
        let (data_writer, mut data_reader) = pipe_pair();
        writer_loop(receiver, metadata_writer, data_writer, &accounting)
            .await
            .expect("writer loop");

        let mut metadata_text = String::new();
        metadata_reader
            .read_to_string(&mut metadata_text)
            .expect("read metadata");
        let records = metadata_text
            .lines()
            .map(|line| serde_json::from_str::<serde_json::Value>(line).expect("metadata json"))
            .collect::<Vec<_>>();
        assert_eq!(records.len(), 5);
        assert_eq!(records[0]["event"], "chunk");
        assert_eq!(records[0]["stream"], "stdout");
        assert_eq!(records[0]["sequence"], 1);
        assert_eq!(records[0]["byteLength"], 3);
        assert_eq!(records[1]["stream"], "stderr");
        assert_eq!(records[1]["sequence"], 1);
        assert_eq!(records[1]["byteLength"], 2);
        assert_eq!(records[2]["stream"], "stdout");
        assert_eq!(records[2]["sequence"], 2);
        assert_eq!(records[3]["event"], "stream_closed");
        assert_eq!(records[4]["event"], "stream_closed");

        let mut raw = Vec::new();
        data_reader.read_to_end(&mut raw).expect("read raw bytes");
        assert_eq!(raw, vec![b'o', b'n', b'e', 0xff, 0x00, b't', b'w', b'o']);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn writer_loop_emits_bounded_drop_receipt_before_close() {
        let accounting = Accounting::default();
        accounting.record_drop(65_536);
        accounting.record_drop(17);
        let (sender, receiver) = mpsc::channel(1);
        drop(sender);

        let (metadata_writer, mut metadata_reader) = pipe_pair();
        let (data_writer, _data_reader) = pipe_pair();
        writer_loop(receiver, metadata_writer, data_writer, &accounting)
            .await
            .expect("writer loop");

        let mut metadata_text = String::new();
        metadata_reader
            .read_to_string(&mut metadata_text)
            .expect("read metadata");
        let records = metadata_text
            .lines()
            .map(|line| serde_json::from_str::<serde_json::Value>(line).expect("metadata json"))
            .collect::<Vec<_>>();
        assert_eq!(records.len(), 3);
        assert_eq!(records[0]["event"], "dropped");
        assert_eq!(records[0]["stream"], "worker");
        assert_eq!(records[0]["droppedChunks"], 2);
        assert_eq!(records[0]["droppedBytes"], 65_553);
        assert_eq!(accounting.dropped_chunks.load(Ordering::Acquire), 0);
        assert_eq!(accounting.dropped_bytes.load(Ordering::Acquire), 0);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn closed_receiver_is_optional_sink_failure() {
        let accounting = Accounting::default();
        let (sender, receiver) = mpsc::channel(1);
        sender
            .try_send(queued(LogStream::Stdout, 1, b"still-primary"))
            .expect("queue chunk");
        drop(sender);

        let (metadata_writer, metadata_reader) = pipe_pair();
        let (data_writer, data_reader) = pipe_pair();
        drop(metadata_reader);
        drop(data_reader);

        assert!(
            writer_loop(receiver, metadata_writer, data_writer, &accounting)
                .await
                .is_err()
        );
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn non_reading_receiver_hits_sidecar_write_deadline() {
        let accounting = Accounting::default();
        let (sender, receiver) = mpsc::channel(128);
        let payload = vec![b'x'; MAX_CHUNK_BYTES];
        for sequence in 1..=128 {
            sender
                .try_send(queued(LogStream::Stdout, sequence, &payload))
                .expect("queue pressure chunk");
        }
        drop(sender);

        let (metadata_writer, _metadata_reader) = pipe_pair();
        let (data_writer, _data_reader) = pipe_pair();
        let error = tokio::time::timeout(
            Duration::from_secs(2),
            writer_loop(receiver, metadata_writer, data_writer, &accounting),
        )
        .await
        .expect("writer loop must stay bounded")
        .expect_err("non-reading receiver must fail the optional sink");
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    }

    #[tokio::test]
    async fn supervisor_budget_aborts_hung_receiver() {
        let supervisor = tokio::spawn(async {
            tokio::time::sleep(Duration::from_secs(60)).await;
        });
        let started = std::time::Instant::now();
        assert!(!await_supervisor_with_budget(supervisor, Duration::from_millis(20)).await);
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[test]
    fn repo_url_is_normalized_for_indexing() {
        assert_eq!(
            github_job_context("https://github.com/gha-indie-worker/gha-indie-worker.rs.git"),
            JobContext {
                repository: Some("gha-indie-worker/gha-indie-worker.rs".to_string()),
                github_organization: Some("gha-indie-worker".to_string()),
            }
        );
        assert_eq!(
            github_job_context("git@github.com:ORESoftware/ores-otel.git")
                .repository
                .as_deref(),
            Some("ORESoftware/ores-otel")
        );
        assert_eq!(
            github_job_context("file:///tmp/repo"),
            JobContext::default()
        );
    }

    #[test]
    fn log_path_derives_stable_job_id() {
        assert_eq!(
            job_id_from_log_path(Path::new(
                "/var/lib/dd-build-server/jobs/build-42/build.log"
            )),
            "build-42"
        );
        assert_eq!(
            job_id_from_log_path(Path::new("build.log")),
            WORKER_GLOBAL_JOB_ID
        );
    }

    #[test]
    fn shutdown_budget_is_hard_capped_at_eight_seconds() {
        assert_eq!(MAX_SHUTDOWN_BUDGET, Duration::from_secs(8));
    }
}
