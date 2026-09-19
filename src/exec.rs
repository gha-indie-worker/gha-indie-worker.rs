use std::{
    env,
    path::{Path, PathBuf},
    process::Stdio,
    sync::OnceLock,
};

use tokio::{
    fs::{self, OpenOptions},
    io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader},
    process::Command,
    time::timeout,
};

use crate::config::Config;

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
    let current_len = fs::metadata(path).await.map(|meta| meta.len()).unwrap_or(0);
    if current_len >= max_bytes {
        return;
    }
    let remaining = (max_bytes - current_len) as usize;
    let bytes = message.as_bytes();
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

pub(crate) async fn pipe_reader<R>(
    reader: R,
    log_path: PathBuf,
    prefix: &'static str,
    max_bytes: u64,
) where
    R: AsyncRead + Unpin,
{
    let mut reader = BufReader::new(reader);
    let mut line = Vec::new();
    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line).await {
            Ok(0) => break,
            Ok(_) => {
                let text = String::from_utf8_lossy(&line);
                append_log(&log_path, &format!("{prefix}{text}"), max_bytes).await;
            }
            Err(error) => {
                append_log(
                    &log_path,
                    &format!("{prefix}failed to read command output: {error}\n"),
                    max_bytes,
                )
                .await;
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
    append_log(
        log_path,
        &format!("\n$ {}\n", printable_command(program, &display_args)),
        config.max_log_bytes,
    )
    .await;

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
        .env("GIT_ASKPASS", askpass_deny_bin())
        .envs(GIT_ISOLATION_ENV)
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

    let mut child = command
        .spawn()
        .map_err(|error| format!("failed to spawn {program}: {error}"))?;
    if let Some(input) = stdin {
        if let Some(mut child_stdin) = child.stdin.take() {
            child_stdin
                .write_all(&input)
                .await
                .map_err(|error| format!("failed to write stdin for {program}: {error}"))?;
        }
    }

    let stdout_task = child.stdout.take().map(|stdout| {
        tokio::spawn(pipe_reader(
            stdout,
            log_path.to_path_buf(),
            "",
            config.max_log_bytes,
        ))
    });
    let stderr_task = child.stderr.take().map(|stderr| {
        tokio::spawn(pipe_reader(
            stderr,
            log_path.to_path_buf(),
            "",
            config.max_log_bytes,
        ))
    });

    let status = match timeout(config.job_timeout, child.wait()).await {
        Ok(Ok(status)) => status,
        Ok(Err(error)) => return Err(format!("{program} failed to wait: {error}")),
        Err(_) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            return Err(format!(
                "{program} timed out after {:?}",
                config.job_timeout
            ));
        }
    };

    if let Some(task) = stdout_task {
        let _ = task.await;
    }
    if let Some(task) = stderr_task {
        let _ = task.await;
    }

    append_log(
        log_path,
        &format!("exit status: {status}\n"),
        config.max_log_bytes,
    )
    .await;

    if status.success() {
        Ok(())
    } else {
        Err(format!("{program} exited with {status}"))
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

/// Cuts every worker-owned git process off from configuration the host
/// supplies.
///
/// Clearing the environment and repointing `HOME` hides the *user's* config,
/// but the system config and system attributes are still read, and a checkout
/// is where they bite: a repository's `.gitattributes` can name a filter, and
/// the host's config is what gives that name a command to run. Replacement
/// refs can likewise substitute a different object for the one requested,
/// which would defeat an exact-commit checkout from underneath it.
///
/// `GIT_CONFIG_GLOBAL` is pinned as well so that a `.gitconfig` appearing in
/// the job directory — which is `HOME` here, and is writable by the code under
/// test on any later command — can never configure a subsequent git call.
pub(crate) const GIT_ISOLATION_ENV: [(&str, &str); 4] = [
    ("GIT_CONFIG_NOSYSTEM", "1"),
    ("GIT_ATTR_NOSYSTEM", "1"),
    ("GIT_NO_REPLACE_OBJECTS", "1"),
    ("GIT_CONFIG_GLOBAL", "/dev/null"),
];

/// Absolute path to a binary that always fails, used as `GIT_ASKPASS` so git can
/// never obtain credentials interactively or from a GUI helper.
///
/// Linux ships `/bin/false`; macOS only has `/usr/bin/false`. Pointing
/// `GIT_ASKPASS` at a path that does not exist is not fail-closed in the way it
/// looks: git reports `cannot exec` and then falls back to asking on the
/// terminal, so the value is resolved against the filesystem once at startup.
pub(crate) fn askpass_deny_bin() -> &'static str {
    static RESOLVED: OnceLock<&'static str> = OnceLock::new();
    *RESOLVED.get_or_init(|| {
        ["/bin/false", "/usr/bin/false"]
            .into_iter()
            .find(|candidate| Path::new(candidate).is_file())
            // `GIT_TERMINAL_PROMPT=0` still refuses the prompt if neither
            // exists, so keep the Linux default rather than leaving it unset.
            .unwrap_or("/bin/false")
    })
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

    /// A scratch directory standing in for a job directory, which is `HOME`
    /// for every command the worker runs.
    fn scratch(name: &str) -> PathBuf {
        let dir = env::temp_dir().join(format!("dd-bs-exec-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).expect("scratch dir");
        dir
    }

    fn test_config() -> Config {
        let mut config = crate::config::config_from_env();
        config.git_bin = "git".to_string();
        config.git_http_auth_header = None;
        config
    }

    #[test]
    fn host_git_configuration_is_cut_off() {
        let isolation: std::collections::HashMap<_, _> = GIT_ISOLATION_ENV.into_iter().collect();
        assert_eq!(isolation.get("GIT_CONFIG_NOSYSTEM"), Some(&"1"));
        assert_eq!(isolation.get("GIT_ATTR_NOSYSTEM"), Some(&"1"));
        assert_eq!(isolation.get("GIT_NO_REPLACE_OBJECTS"), Some(&"1"));
        assert_eq!(isolation.get("GIT_CONFIG_GLOBAL"), Some(&"/dev/null"));
    }

    #[tokio::test]
    async fn a_configured_credential_helper_is_never_invoked() {
        // A helper answers without prompting, so GIT_ASKPASS and
        // GIT_TERMINAL_PROMPT=0 do not stop it. It is planted where the worker's
        // git would look for user configuration: HOME is the working directory.
        let home = scratch("helper");
        let marker = home.join("helper-was-invoked");
        std::fs::write(
            home.join(".gitconfig"),
            format!(
                "[credential]\n\thelper = \"!f() {{ touch '{}'; }}; f\"\n",
                marker.display()
            ),
        )
        .expect("plant helper");
        let log = home.join("build.log");

        // `git credential fill` exists to consult helpers, so it is the most
        // direct way to ask "would one run?". It is expected to fail here: no
        // helper, no prompt, nothing to fill with.
        let _ = run_logged_command_with_input(
            &test_config(),
            &log,
            &home,
            "git",
            vec!["credential".to_string(), "fill".to_string()],
            vec!["credential".to_string(), "fill".to_string()],
            b"protocol=https\nhost=example.invalid\n\n".to_vec(),
        )
        .await;
        assert!(
            !marker.exists(),
            "a host-configured credential helper ran inside the checkout boundary"
        );
    }

    #[tokio::test]
    async fn a_command_bearing_filter_from_host_config_does_not_run_on_checkout() {
        // The repository names a filter in .gitattributes; configuration gives
        // that name a command. If host configuration were honoured, checking
        // the file out would execute it.
        let home = scratch("filter");
        let marker = home.join("filter-was-executed");
        // HOME is each command's working directory, so for commands run inside
        // the repository that is where user configuration would be found.
        let repo = home.join("repo");
        std::fs::create_dir_all(&repo).expect("repo dir");
        std::fs::write(
            repo.join(".gitconfig"),
            format!(
                "[filter \"pwn\"]\n\tsmudge = \"touch '{}'; cat\"\n\trequired = false\n[user]\n\tname = t\n\temail = t@example.invalid\n",
                marker.display()
            ),
        )
        .expect("plant filter");
        let log = home.join("build.log");
        let config = test_config();
        let git = |args: &[&str]| {
            let args: Vec<String> = args.iter().map(|arg| (*arg).to_string()).collect();
            let (config, log, repo) = (config.clone(), log.clone(), repo.clone());
            async move { run_logged_command(&config, &log, &repo, "git", args).await }
        };

        git(&["init", "--quiet"]).await.expect("init");
        std::fs::write(repo.join(".gitattributes"), "*.txt filter=pwn\n").expect("attributes");
        std::fs::write(repo.join("payload.txt"), "content\n").expect("payload");
        git(&["add", ".gitattributes", "payload.txt"])
            .await
            .expect("add");
        git(&[
            "-c",
            "user.name=t",
            "-c",
            "user.email=t@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "fixture",
        ])
        .await
        .expect("commit");
        // Force the smudge path: remove the file and check it back out.
        std::fs::remove_file(repo.join("payload.txt")).expect("remove");
        git(&["checkout", "--force", "HEAD", "--", "payload.txt"])
            .await
            .expect("checkout");

        assert!(
            repo.join("payload.txt").exists(),
            "the checkout itself worked"
        );
        assert!(
            !marker.exists(),
            "host git configuration supplied a command that ran during checkout"
        );
    }

    #[test]
    fn askpass_deny_binary_exists_on_this_platform() {
        // A non-existent GIT_ASKPASS makes git fall back to prompting, so the
        // resolved path must really be there on whichever host runs the worker.
        assert!(Path::new(askpass_deny_bin()).is_file());
    }

    #[test]
    fn executable_lookup_accepts_path_commands_and_rejects_missing_tools() {
        assert!(executable_available("sh"));
        assert!(!executable_available(
            "dd-build-server-tool-that-does-not-exist"
        ));
    }
}
