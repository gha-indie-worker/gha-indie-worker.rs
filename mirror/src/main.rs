#![forbid(unsafe_code)]
//! Process entry point for the long-form `ores-cli` executable.

use std::process::ExitCode;

#[path = "bin/shared_entrypoint.rs"]
mod shared_entrypoint;

#[tokio::main]
async fn main() -> ExitCode {
    shared_entrypoint::run(std::env::args().collect()).await
}
