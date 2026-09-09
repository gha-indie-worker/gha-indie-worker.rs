# Live build-log sidecar protocol

`gha-indie-worker` can tee command output to an operator-selected receiver process while keeping the worker's normal stdout/stderr and bounded build log as the primary sinks.

The sidecar is observability-only. A slow, blocked, malformed, or crashed receiver must not change the build result.

## Enable a receiver

Set these worker environment variables:

| Variable | Meaning | Default / bound |
| --- | --- | --- |
| `BUILD_SERVER_LOG_SIDECAR_BIN` | Receiver executable. If unset, live sidecar delivery is disabled. Prefer an absolute path. | disabled |
| `BUILD_SERVER_LOG_SIDECAR_ARGS_JSON` | Receiver argv as a JSON array of strings. It is never evaluated by a shell. | `[]` |
| `BUILD_SERVER_LOG_SIDECAR_ENV_ALLOWLIST` | Comma-separated environment variable names that may be copied from the worker into the receiver. | none |
| `BUILD_SERVER_LOG_SIDECAR_QUEUE_CAPACITY` | Number of queued data/metadata frames before new frames are dropped and counted. | `256`, hard-capped at `4096` |
| `BUILD_SERVER_LOG_SIDECAR_SHUTDOWN_MS` | Maximum receiver drain/exit grace after a command completes. | `8000`, hard-capped at `8000` |

Example:

```text
BUILD_SERVER_LOG_SIDECAR_BIN=/usr/local/bin/my-log-receiver
BUILD_SERVER_LOG_SIDECAR_ARGS_JSON=["--tenant","builds"]
BUILD_SERVER_LOG_SIDECAR_ENV_ALLOWLIST=OTEL_EXPORTER_OTLP_ENDPOINT,MY_LOG_TOKEN
BUILD_SERVER_LOG_SIDECAR_QUEUE_CAPACITY=512
BUILD_SERVER_LOG_SIDECAR_SHUTDOWN_MS=8000
```

The executable and argv are worker/operator configuration, not fields in an admitted GitHub Actions workflow. Workflow YAML cannot select an arbitrary receiver command.

## Receiver contract: `gha-indie-worker.log-sidecar.v1`

On Unix the worker starts the receiver with:

- **stdin / FD 0:** framed binary build-log data;
- **FD 3:** UTF-8 JSON Lines lifecycle metadata;
- `GHAIW_LOG_SIDECAR_PROTOCOL=gha-indie-worker.log-sidecar.v1`;
- `GHAIW_LOG_SIDECAR_METADATA_FD=3`;
- `GHAIW_LOG_SIDECAR_JOB_ID=<job-id>` when available.

Receiver stdout/stderr are its own diagnostics channels. They are not part of the delivery protocol.

### Data frames on stdin

Every frame is:

| Bytes | Field |
| ---: | --- |
| 0..4 | ASCII magic `GHLG` |
| 4 | protocol frame version (`1`) |
| 5 | stream (`1` = stdout, `2` = stderr) |
| 6..10 | payload length as unsigned 32-bit big-endian |
| 10.. | raw payload bytes |

The worker currently emits chunks no larger than 16 KiB. Receivers should reject unexpectedly large frames; the reference receiver caps a frame at 64 KiB.

The payload is binary-safe. Receivers must not assume UTF-8.

### Metadata on FD 3

Metadata is one JSON object per line. Events include:

- `command_started`
- `command_finished`
- `command_timed_out`
- `command_wait_failed`

The envelope includes `schemaVersion`, `atMs`, bounded `jobId`/`program` labels, and terminal drop counters. Terminal events may include `success` and `exitCode`.

The sidecar metadata intentionally excludes command arguments and worker environment values. Existing command-log redaction remains authoritative for build-log text.

## Backpressure and failure semantics

Command stdout/stderr are first copied to the worker's ordinary stdout/stderr and bounded log file. Sidecar delivery uses a bounded Tokio channel and `try_send`; it never waits for the receiver. When the queue is full or closed, delivery frames are dropped and byte/frame counters increase.

At command teardown the worker allows the receiver at most eight seconds to drain queued data and exit. If that deadline expires, the receiver is terminated/detached and the build continues with its original result.

Sidecar spawn failures, broken pipes, early exits, and receiver protocol errors are fail-open for observability. They do not relax any build admission, sandbox, credential, timeout, or deployment policy.

## Reference receiver

`github.com/gha-indie-worker/gha-indie-worker-sidecar.rs` implements the v1 decoder without unsafe Rust. When it sees `GHAIW_LOG_SIDECAR_PROTOCOL`, it reads framed data from stdin and metadata from `/dev/fd/3`; otherwise it runs its existing `ores-otel-sidecar` service mode.

A production receiver can replace it with any executable that implements the same two-stream contract and forwards the bytes/metadata to Loki, OpenTelemetry, Supabase, S3/R2, Kafka/NATS, or another customer-selected system.

## Portability

The inherited FD 3 metadata lane is enabled on Unix hosts. Windows needs a corresponding named-pipe/handle implementation before this exact transport can be claimed there; sidecar delivery remains disabled rather than silently changing protocol on unsupported hosts.
