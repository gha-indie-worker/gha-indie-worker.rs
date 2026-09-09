use std::{
    env,
    path::{Path, PathBuf},
    process::Stdio,
};

use tokio::{
    fs::{self, OpenOptions},
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    process::Command,
    time::timeout,
};

use crate::{
    config::Config,
    log_sidecar::{CommandOutcome, LogSidecar, LogStream, SidecarTap},
};

const LOG_PUMP_CHUNK_BYTES: usize = 16 * 1024;

pub(crate) fn shellish(value: &str) -> String {
    if value.chars().all(|ch| {
        ch.is_ascii_alphanumeric() || matches!(ch, '/' | '.' | '_' | '-' | ':' | '=' | '@')
    }) {
        value.to_string()
    } else {
        format!("'{}'", value.replace('\'', "'\\''"))
    }
}

pub(crate) fn printable_command(program: &str, args: &[String]) -> String {
    std::iter::once(program.to_string())
        .chain(args.iter().cloned())
        .map(|value| shellish(&value))
        .collect::<Vec<_>>()
        .join(" ")
}

pub(crate) fn redacted_build_args(args: &[String]) -> Vec<String> {
    let mut redacted = Vec::with_capacity(args.len());
    let mut redact_next = false;
    for arg in args {
        if redact_next {
            let key = arg.split_once('=').map(|(key, _)| key).unwrap_or(arg);
            redacted.push(format!("{key}=<redacted>"));
            redact_next = false;
            continue;
        }
        redacted.push(arg.clone());
        if arg == "--build-arg" {
            redact_next = true;
        }
    }
    redacted
}

pub(crate) async fn append_log(path: &Path, message: &str, max_bytes: u64) {
    append_log_bytes(path, message.as_bytes(), max_bytes).await;
}

pub(crate) async fn append_log_bytes(path: &Path, bytes: &[u8], max_bytes: u64) {
    let current_len = fs::metadata(path).await.map(|meta| meta.len()).unwrap_or(0);
    if current_len >= max_bytes {
        return;
    }
    let remaining = (max_bytes - current_len) as usize;
    let limit = remaining.min(bytes.len());
    if limit == 0 {
        return;
    }
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent).await;
    }
    if let Ok(mut file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .await
    {
        let _ = file.write_all(&bytes[..limit]).await;
    }
}

async fn write_primary_stdio(stream: LogStream, bytes: &[u8]) {
    match stream {
        LogStream::Stdout => {
            let mut stdout = tokio::io::stdout();
            let _ = stdout.write_all(bytes).await;
            let _ = stdout.flush().await;
        }
        LogStream::Stderr => {
            let mut stderr = tokio::io::stderr();
            let _ = stderr.write_all(bytes).await;
            let _ = stderr.flush().await;
        }
    }
}

pub(crate) async fn pipe_reader<R>(
    mut reader: R,
    log_path: PathBuf,
    prefix: &'static str,
    max_bytes: u64,
    stream: LogStream,
    sidecar: Option<SidecarTap>,
) where
    R: AsyncRead + Unpin,
{
    let mut buffer = vec![0_u8; LOG_PUMP_CHUNK_BYTES];
    loop {
        match reader.read(&mut buffer).await {
            Ok(0) => break,
            Ok(read) => {
                let bytes = &buffer[..read];
                write_primary_stdio(stream, bytes).await;
                if !prefix.is_empty() {
                    append_log(&log_path, prefix, max_bytes).await;
                }
                append_log_bytes(&log_path, bytes, max_bytes).await;
                if let Some(sidecar) = sidecar.as_ref() {
                    sidecar.try_data(stream, bytes);
                }
            }
            Err(error) => {
                let message = format!("{prefix}failed to read command output: {error}\n");
                append_log(&log_path, &message, max_bytes).await;
                write_primary_stdio(LogStream::Stderr, message.as_bytes()).await;
                break;
            }
        }
    }
}

pub(crate) async fn run_logged_command(
    config: &Config,
    log_path: &Path,
    cwd: &Path,
    program: &str,
    args: Vec<String>,
) -> Result<(), String> {
    run_logged_command_inner(config, log_path, cwd, program, args, None, None).await
}

pub(crate) async fn run_logged_command_with_input(
    config: &Config,
    log_path: &Path,
    cwd: &Path,
    program: &str,
    args: Vec<String>,
    display_args: Vec<String>,
    stdin: Vec<u8>,
) -> Result<(), String> {
    run_logged_command_inner(
        config,
        log_path,
        cwd,
        program,
        args,
        Some(display_args),
        Some(stdin),
    )
    .await
}

pub(crate) async fn run_logged_command_inner(
    config: &Config,
    log_path: &Path,
    cwd: &Path,
    program: &str,
    args: Vec<String>,
    display_args: Option<Vec<String>>,
    stdin: Option<Vec<u8>>,
) -> Result<(), String> {
    let display_args = display_args.unwrap_or_else(|| args.clone());
    let command_line = format!("\n$ {}\n", printable_command(program, &display_args));
    append_log(log_path, &command_line, config.max_log_bytes).await;
    write_primary_stdio(LogStream::Stdout, command_line.as_bytes()).await;

    // Sidecar startup is deliberately fail-open: build execution keeps its
    // existing fail-closed security policy, while an optional observability
    // sink can disappear without changing build success or blocking stdio.
    let sidecar = LogSidecar::spawn(log_path, program).await;
    let sidecar_tap = sidecar.as_ref().map(LogSidecar::tap);

    let mut command = Command::new(program);
    command
        .args(&args)
        .current_dir(cwd)
        .env_clear()
        .env("HOME", cwd)
        .env(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_ASKPASS", "/bin/false")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if program == config.git_bin {
        if let Some(auth_header) = config.git_http_auth_header.as_deref() {
            command
                .env("GIT_CONFIG_COUNT", "1")
                .env("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader")
                .env("GIT_CONFIG_VALUE_0", auth_header);
        }
    }
    if stdin.is_some() {
        command.stdin(Stdio::piped());
    }
    for key in [
        "KUBERNETES_SERVICE_HOST",
        "KUBERNETES_SERVICE_PORT",
        "KUBERNETES_SERVICE_PORT_HTTPS",
    ] {
        if let Ok(value) = env::var(key) {
            command.env(key, value);
        }
    }

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            if let Some(sidecar) = sidecar {
                sidecar.finish(CommandOutcome::WaitFailed).await;
            }
            return Err(format!("failed to spawn {program}: {error}"));
        }
    };
    if let Some(input) = stdin {
        if let Some(mut child_stdin) = child.stdin.take() {
            if let Err(error) = child_stdin.write_all(&input).await {
                let _ = child.start_kill();
                if let Some(sidecar) = sidecar {
                    sidecar.finish(CommandOutcome::WaitFailed).await;
                }
                return Err(format!("failed to write stdin for {program}: {error}"));
            }
        }
    }

    let stdout_task = child.stdout.take().map(|stdout| {
        tokio::spawn(pipe_reader(
            stdout,
            log_path.to_path_buf(),
            "",
            config.max_log_bytes,
            LogStream::Stdout,
            sidecar_tap.clone(),
        ))
    });
    let stderr_task = child.stderr.take().map(|stderr| {
        tokio::spawn(pipe_reader(
            stderr,
            log_path.to_path_buf(),
            "",
            config.max_log_bytes,
            LogStream::Stderr,
            sidecar_tap,
        ))
    });

    enum WaitResult {
        Exited(std::process::ExitStatus),
        Failed(String),
        TimedOut,
    }

    let wait_result = match timeout(config.job_timeout, child.wait()).await {
        Ok(Ok(status)) => WaitResult::Exited(status),
        Ok(Err(error)) => WaitResult::Failed(format!("{program} failed to wait: {error}")),
        Err(_) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            WaitResult::TimedOut
        }
    };

    if let Some(task) = stdout_task {
        let _ = task.await;
    }
    if let Some(task) = stderr_task {
        let _ = task.await;
    }

    match wait_result {
        WaitResult::Exited(status) => {
            let status_line = format!("exit status: {status}\n");
            append_log(log_path, &status_line, config.max_log_bytes).await;
            write_primary_stdio(LogStream::Stdout, status_line.as_bytes()).await;
            if let Some(sidecar) = sidecar {
                sidecar
                    .finish(CommandOutcome::Exited {
                        success: status.success(),
                        code: status.code(),
                    })
                    .await;
            }
            if status.success() {
                Ok(())
            } else {
                Err(format!("{program} exited with {status}"))
            }
        }
        WaitResult::Failed(error) => {
            if let Some(sidecar) = sidecar {
                sidecar.finish(CommandOutcome::WaitFailed).await;
            }
            Err(error)
        }
        WaitResult::TimedOut => {
            if let Some(sidecar) = sidecar {
                sidecar.finish(CommandOutcome::TimedOut).await;
            }
            Err(format!(
                "{program} timed out after {:?}",
                config.job_timeout
            ))
        }
    }
}

pub(crate) fn build_dependencies_ready(config: &Config) -> bool {
    config.server_auth_secret.is_some()
        && config.work_root.exists()
        && executable_available(&config.git_bin)
        && executable_available(&config.nerdctl_bin)
        && executable_available(&config.tar_bin)
        && (!config.deploy_enabled || executable_available(&config.kubectl_bin))
}

pub(crate) fn executable_available(value: &str) -> bool {
    let path = Path::new(value);
    if path.is_absolute() || path.components().count() > 1 {
        return path.is_file();
    }
    env::var_os("PATH").is_some_and(|paths| {
        env::split_paths(&paths)
            .map(|directory| directory.join(value))
            .any(|candidate| candidate.is_file())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn executable_lookup_accepts_path_commands_and_rejects_missing_tools() {
        assert!(executable_available("sh"));
        assert!(!executable_available(
            "dd-build-server-tool-that-does-not-exist"
        ));
    }
}
