use std::{
    env, path::Path, process::Stdio,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[cfg(unix)]
use std::{
    fs::File,
    io,
    os::fd::{AsRawFd, FromRawFd},
};

use serde_json::json;
use tokio::{
    io::AsyncWriteExt,
    process::{Child, Command},
    sync::mpsc,
    task::JoinHandle,
    time::timeout,
};

pub(crate) const PROTOCOL: &str = "gha-indie-worker.log-sidecar.v1";
#[cfg(unix)]
const METADATA_FD: i32 = 3;
const FRAME_MAGIC: &[u8; 4] = b"GHLG";
const FRAME_VERSION: u8 = 1;
#[cfg(unix)]
const MAX_SHUTDOWN_GRACE: Duration = Duration::from_secs(8);
const DEFAULT_QUEUE_CAPACITY: usize = 256;
const MAX_QUEUE_CAPACITY: usize = 4096;

#[derive(Clone, Debug)]
struct LogSidecarConfig {
    bin: String,
    args: Vec<String>,
    env_allowlist: Vec<String>,
    queue_capacity: usize,
    shutdown_grace: Duration,
}

impl LogSidecarConfig {
    fn from_env() -> Option<Self> {
        let bin = nonempty_env("BUILD_SERVER_LOG_SIDECAR_BIN")?;
        let args = match nonempty_env("BUILD_SERVER_LOG_SIDECAR_ARGS_JSON") {
            Some(raw) => match serde_json::from_str::<Vec<String>>(&raw) {
                Ok(args) => args,
                Err(_) => {
                    tracing::warn!(
                        "BUILD_SERVER_LOG_SIDECAR_ARGS_JSON must be a JSON array of strings; disabling live log sidecar"
                    );
                    return None;
                }
            },
            None => Vec::new(),
        };
        let env_allowlist = nonempty_env("BUILD_SERVER_LOG_SIDECAR_ENV_ALLOWLIST")
            .map(|raw| {
                raw.split(',')
                    .map(str::trim)
                    .filter(|key| valid_env_key(key))
                    .map(ToOwned::to_owned)
                    .collect()
            })
            .unwrap_or_default();
        let queue_capacity = positive_usize_env(
            "BUILD_SERVER_LOG_SIDECAR_QUEUE_CAPACITY",
            DEFAULT_QUEUE_CAPACITY,
        )
        .min(MAX_QUEUE_CAPACITY);
        let shutdown_ms = positive_u64_env("BUILD_SERVER_LOG_SIDECAR_SHUTDOWN_MS", 8_000).min(8_000);

        Some(Self {
            bin,
            args,
            env_allowlist,
            queue_capacity,
            shutdown_grace: Duration::from_millis(shutdown_ms),
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum LogStream {
    Stdout = 1,
    Stderr = 2,
}

#[derive(Clone)]
pub(crate) struct SidecarTap {
    tx: mpsc::Sender<SidecarEvent>,
    dropped_frames: Arc<AtomicU64>,
    dropped_bytes: Arc<AtomicU64>,
}

impl SidecarTap {
    pub(crate) fn try_data(&self, stream: LogStream, bytes: &[u8]) {
        let byte_count = bytes.len() as u64;
        if self
            .tx
            .try_send(SidecarEvent::Data {
                stream,
                bytes: bytes.to_vec(),
            })
            .is_err()
        {
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
            self.dropped_bytes.fetch_add(byte_count, Ordering::Relaxed);
        }
    }

    fn try_metadata(&self, value: serde_json::Value) {
        let mut bytes = match serde_json::to_vec(&value) {
            Ok(bytes) => bytes,
            Err(_) => return,
        };
        bytes.push(b'\n');
        let byte_count = bytes.len() as u64;
        if self.tx.try_send(SidecarEvent::Metadata(bytes)).is_err() {
            self.dropped_frames.fetch_add(1, Ordering::Relaxed);
            self.dropped_bytes.fetch_add(byte_count, Ordering::Relaxed);
        }
    }

    fn dropped_frames(&self) -> u64 {
        self.dropped_frames.load(Ordering::Relaxed)
    }

    fn dropped_bytes(&self) -> u64 {
        self.dropped_bytes.load(Ordering::Relaxed)
    }
}

enum SidecarEvent {
    Data { stream: LogStream, bytes: Vec<u8> },
    Metadata(Vec<u8>),
}

pub(crate) enum CommandOutcome {
    Exited { success: bool, code: Option<i32> },
    TimedOut,
    WaitFailed,
}

pub(crate) struct LogSidecar {
    tap: SidecarTap,
    child: Child,
    writer_task: JoinHandle<()>,
    shutdown_grace: Duration,
    job_id: Option<String>,
    program: String,
}

impl LogSidecar {
    pub(crate) async fn spawn(log_path: &Path, program: &str) -> Option<Self> {
        let config = LogSidecarConfig::from_env()?;
        let sidecar_bin = config.bin.as_str();

        #[cfg(not(unix))]
        {
            let _ = (
                &config.args,
                &config.env_allowlist,
                config.queue_capacity,
                config.shutdown_grace,
                log_path,
                program,
                sidecar_bin,
            );
            tracing::warn!(
                protocol = PROTOCOL,
                "live log sidecar is currently available only on Unix hosts"
            );
            return None;
        }

        #[cfg(unix)]
        {
            let (metadata_reader, metadata_writer) = match metadata_pipe() {
                Ok(pipe) => pipe,
                Err(error) => {
                    tracing::warn!("failed to create log-sidecar metadata pipe: {error}");
                    return None;
                }
            };

            let job_id = job_id_from_log_path(log_path);
            let mut command = Command::new(sidecar_bin);
            command
                .args(&config.args)
                .env_clear()
                .env(
                    "PATH",
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                )
                .env("GHAIW_LOG_SIDECAR_PROTOCOL", PROTOCOL)
                .env("GHAIW_LOG_SIDECAR_METADATA_FD", METADATA_FD.to_string())
                .stdin(Stdio::piped())
                .stdout(Stdio::inherit())
                .stderr(Stdio::inherit())
                .kill_on_drop(true);
            if let Some(job_id) = job_id.as_deref() {
                command.env("GHAIW_LOG_SIDECAR_JOB_ID", job_id);
            }
            for key in &config.env_allowlist {
                if let Some(value) = env::var_os(key) {
                    command.env(key, value);
                }
            }

            if let Err(error) = inherit_metadata_fd(&mut command, metadata_reader.as_raw_fd()) {
                tracing::warn!("failed to configure log-sidecar metadata fd: {error}");
                return None;
            }

            let mut child = match command.spawn() {
                Ok(child) => child,
                Err(error) => {
                    tracing::warn!("failed to spawn live log sidecar: {error}");
                    return None;
                }
            };
            drop(metadata_reader);

            let data_writer = match child.stdin.take() {
                Some(writer) => writer,
                None => {
                    let _ = child.start_kill();
                    tracing::warn!("live log sidecar did not expose piped stdin");
                    return None;
                }
            };
            let metadata_writer = tokio::fs::File::from_std(metadata_writer);
            let (tx, rx) = mpsc::channel(config.queue_capacity.max(1));
            let dropped_frames = Arc::new(AtomicU64::new(0));
            let dropped_bytes = Arc::new(AtomicU64::new(0));
            let tap = SidecarTap {
                tx,
                dropped_frames,
                dropped_bytes,
            };
            let writer_task = tokio::spawn(writer_loop(rx, data_writer, metadata_writer));
            let program = program_label(program);

            tap.try_metadata(json!({
                "schemaVersion": PROTOCOL,
                "event": "command_started",
                "atMs": now_ms(),
                "jobId": job_id,
                "program": program,
            }));

            Some(Self {
                tap,
                child,
                writer_task,
                shutdown_grace: config.shutdown_grace.min(MAX_SHUTDOWN_GRACE),
                job_id,
                program,
            })
        }
    }

    pub(crate) fn tap(&self) -> SidecarTap {
        self.tap.clone()
    }

    pub(crate) async fn finish(self, outcome: CommandOutcome) {
        let (event, success, exit_code) = match outcome {
            CommandOutcome::Exited { success, code } => ("command_finished", Some(success), code),
            CommandOutcome::TimedOut => ("command_timed_out", Some(false), None),
            CommandOutcome::WaitFailed => ("command_wait_failed", Some(false), None),
        };
        self.tap.try_metadata(json!({
            "schemaVersion": PROTOCOL,
            "event": event,
            "atMs": now_ms(),
            "jobId": self.job_id,
            "program": self.program,
            "success": success,
            "exitCode": exit_code,
            "droppedFrames": self.tap.dropped_frames(),
            "droppedBytes": self.tap.dropped_bytes(),
        }));

        drop(self.tap);
        let mut writer_task = self.writer_task;
        let mut child = self.child;
        let drain = async {
            let _ = (&mut writer_task).await;
            let _ = child.wait().await;
        };

        if timeout(self.shutdown_grace, drain).await.is_err() {
            writer_task.abort();
            let _ = child.start_kill();
            tracing::warn!(
                protocol = PROTOCOL,
                grace_ms = self.shutdown_grace.as_millis() as u64,
                "live log sidecar exceeded shutdown grace and was terminated"
            );
        }
    }
}

async fn writer_loop(
    mut rx: mpsc::Receiver<SidecarEvent>,
    mut data_writer: tokio::process::ChildStdin,
    mut metadata_writer: tokio::fs::File,
) {
    while let Some(event) = rx.recv().await {
        let result = match event {
            SidecarEvent::Data { stream, bytes } => {
                let frame = encode_data_frame(stream, &bytes);
                data_writer.write_all(&frame).await
            }
            SidecarEvent::Metadata(bytes) => metadata_writer.write_all(&bytes).await,
        };
        if result.is_err() {
            break;
        }
    }
    let _ = data_writer.shutdown().await;
    let _ = metadata_writer.shutdown().await;
}

pub(crate) fn encode_data_frame(stream: LogStream, bytes: &[u8]) -> Vec<u8> {
    let frame_len = u32::try_from(bytes.len()).unwrap_or(u32::MAX);
    let bytes = &bytes[..usize::try_from(frame_len).unwrap_or(bytes.len())];
    let mut frame = Vec::with_capacity(10 + bytes.len());
    frame.extend_from_slice(FRAME_MAGIC);
    frame.push(FRAME_VERSION);
    frame.push(stream as u8);
    frame.extend_from_slice(&frame_len.to_be_bytes());
    frame.extend_from_slice(bytes);
    frame
}

fn nonempty_env(key: &str) -> Option<String> {
    env::var(key)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

fn positive_usize_env(key: &str, fallback: usize) -> usize {
    nonempty_env(key)
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(fallback)
}

fn positive_u64_env(key: &str, fallback: u64) -> u64 {
    nonempty_env(key)
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(fallback)
}

fn valid_env_key(key: &str) -> bool {
    let mut chars = key.chars();
    matches!(chars.next(), Some(first) if first == '_' || first.is_ascii_alphabetic())
        && chars.all(|ch| ch == '_' || ch.is_ascii_alphanumeric())
}

fn job_id_from_log_path(log_path: &Path) -> Option<String> {
    log_path
        .parent()
        .and_then(Path::file_name)
        .map(|value| value.to_string_lossy().into_owned())
}

fn program_label(program: &str) -> String {
    Path::new(program)
        .file_name()
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_else(|| program.to_string())
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(unix)]
mod unix_sys {
    use std::os::raw::c_int;

    pub(super) const F_GETFD: c_int = 1;
    pub(super) const F_SETFD: c_int = 2;
    pub(super) const FD_CLOEXEC: c_int = 1;

    unsafe extern "C" {
        pub(super) fn pipe(fds: *mut c_int) -> c_int;
        pub(super) fn close(fd: c_int) -> c_int;
        pub(super) fn dup2(oldfd: c_int, newfd: c_int) -> c_int;
        pub(super) fn fcntl(fd: c_int, cmd: c_int, ...) -> c_int;
    }
}

#[cfg(unix)]
fn metadata_pipe() -> io::Result<(File, File)> {
    let mut fds = [0_i32; 2];
    // SAFETY: `pipe` receives a valid pointer to two i32 slots. On success it
    // returns two newly-owned descriptors; each is immediately wrapped exactly
    // once in `File`, transferring ownership to Rust.
    if unsafe { unix_sys::pipe(fds.as_mut_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    for fd in fds {
        // SAFETY: `fd` was returned by `pipe` above and remains open here.
        let flags = unsafe { unix_sys::fcntl(fd, unix_sys::F_GETFD) };
        if flags < 0 {
            // SAFETY: best-effort cleanup of descriptors returned by `pipe`.
            unsafe {
                unix_sys::close(fds[0]);
                unix_sys::close(fds[1]);
            }
            return Err(io::Error::last_os_error());
        }
        // SAFETY: fcntl is called with the documented F_SETFD operation.
        if unsafe { unix_sys::fcntl(fd, unix_sys::F_SETFD, flags | unix_sys::FD_CLOEXEC) } != 0 {
            // SAFETY: best-effort cleanup of descriptors returned by `pipe`.
            unsafe {
                unix_sys::close(fds[0]);
                unix_sys::close(fds[1]);
            }
            return Err(io::Error::last_os_error());
        }
    }
    // SAFETY: ownership of each successful pipe descriptor is transferred once.
    let reader = unsafe { File::from_raw_fd(fds[0]) };
    // SAFETY: ownership of each successful pipe descriptor is transferred once.
    let writer = unsafe { File::from_raw_fd(fds[1]) };
    Ok((reader, writer))
}

#[cfg(unix)]
fn inherit_metadata_fd(command: &mut Command, source_fd: i32) -> io::Result<()> {
    use std::os::unix::process::CommandExt;

    // `pre_exec` is the only standard-library hook for mapping an arbitrary
    // inherited descriptor. The closure performs only async-signal-safe fd
    // operations between fork and exec.
    unsafe {
        command.as_std_mut().pre_exec(move || {
            if source_fd == METADATA_FD {
                let flags = unix_sys::fcntl(source_fd, unix_sys::F_GETFD);
                if flags < 0 {
                    return Err(io::Error::last_os_error());
                }
                if unix_sys::fcntl(
                    source_fd,
                    unix_sys::F_SETFD,
                    flags & !unix_sys::FD_CLOEXEC,
                ) != 0
                {
                    return Err(io::Error::last_os_error());
                }
            } else if unix_sys::dup2(source_fd, METADATA_FD) < 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn data_frame_is_versioned_and_stream_tagged() {
        let frame = encode_data_frame(LogStream::Stderr, b"boom\n");
        assert_eq!(&frame[..4], b"GHLG");
        assert_eq!(frame[4], 1);
        assert_eq!(frame[5], LogStream::Stderr as u8);
        assert_eq!(u32::from_be_bytes(frame[6..10].try_into().unwrap()), 5);
        assert_eq!(&frame[10..], b"boom\n");
    }

    #[test]
    fn bounded_tap_drops_when_receiver_is_backpressured() {
        let (tx, _rx) = mpsc::channel(1);
        let tap = SidecarTap {
            tx,
            dropped_frames: Arc::new(AtomicU64::new(0)),
            dropped_bytes: Arc::new(AtomicU64::new(0)),
        };
        tap.try_data(LogStream::Stdout, b"first");
        tap.try_data(LogStream::Stdout, b"second");
        assert_eq!(tap.dropped_frames(), 1);
        assert_eq!(tap.dropped_bytes(), 6);
    }

    #[test]
    fn program_and_job_metadata_are_bounded_labels_not_full_paths() {
        assert_eq!(program_label("/usr/local/bin/cargo"), "cargo");
        assert_eq!(
            job_id_from_log_path(Path::new("/var/jobs/build-123/build.log")).as_deref(),
            Some("build-123")
        );
    }

    #[test]
    fn env_key_allowlist_rejects_shellish_names() {
        assert!(valid_env_key("OTEL_EXPORTER_OTLP_ENDPOINT"));
        assert!(!valid_env_key("A=B"));
        assert!(!valid_env_key("$(touch-pwned)"));
        assert!(!valid_env_key(""));
    }
}