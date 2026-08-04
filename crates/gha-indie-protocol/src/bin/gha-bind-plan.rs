use std::env;
use std::fs;
use std::path::Path;
use std::process::ExitCode;

use gha_indie_protocol::{bind_plan, BindingDocument, ProfileCatalog, ProtocolError, WorkflowPlan};
use serde::de::DeserializeOwned;

const MAX_PLAN_BYTES: usize = 4 * 1024 * 1024;
const MAX_CATALOG_BYTES: usize = 512 * 1024;
const MAX_BINDINGS_BYTES: usize = 512 * 1024;

fn main() -> ExitCode {
    match run() {
        Ok(output) => {
            println!("{output}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            let payload = serde_json::to_string(&error).unwrap_or_else(|_| {
                r#"{"code":"error_serialization","message":"failed to serialize protocol error"}"#
                    .to_string()
            });
            eprintln!("{payload}");
            ExitCode::from(2)
        }
    }
}

fn run() -> Result<String, ProtocolError> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() != 3 {
        return Err(cli_error(
            "usage: gha-bind-plan <plan.json> <profile-catalog.json> <bindings.json>",
        ));
    }

    let plan: WorkflowPlan = read_json(Path::new(&arguments[0]), MAX_PLAN_BYTES, "plan")?;
    let catalog: ProfileCatalog =
        read_json(Path::new(&arguments[1]), MAX_CATALOG_BYTES, "profile catalog")?;
    let bindings: BindingDocument =
        read_json(Path::new(&arguments[2]), MAX_BINDINGS_BYTES, "bindings")?;
    let batch = bind_plan(&plan, &catalog, &bindings)?;
    serde_json::to_string_pretty(&batch).map_err(|error| {
        cli_error(format!(
            "failed to serialize bound dispatch batch as JSON: {error}"
        ))
    })
}

fn read_json<T: DeserializeOwned>(
    path: &Path,
    maximum_bytes: usize,
    label: &str,
) -> Result<T, ProtocolError> {
    let bytes = fs::read(path).map_err(|error| {
        cli_error(format!(
            "failed to read {label} file {}: {error}",
            path.display()
        ))
    })?;
    if bytes.len() > maximum_bytes {
        return Err(cli_error(format!(
            "{label} file {} is {} bytes; maximum is {maximum_bytes}",
            path.display(),
            bytes.len()
        )));
    }
    serde_json::from_slice(&bytes).map_err(|error| {
        cli_error(format!(
            "{label} file {} is not valid protocol JSON: {error}",
            path.display()
        ))
    })
}

fn cli_error(message: impl Into<String>) -> ProtocolError {
    ProtocolError {
        code: "cli_input",
        message: message.into(),
    }
}
