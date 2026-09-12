use std::env;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::process::ExitCode;

use gha_indie_protocol::{
    bind_plan, profile_catalog_digest, workflow_plan_digest, BindingDocument, ProfileCatalog,
    ProtocolError, WorkflowPlan,
};
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
    match arguments.as_slice() {
        [flag, catalog_path] if flag == "--catalog-digest" => {
            let catalog: ProfileCatalog = read_json(
                Path::new(catalog_path),
                MAX_CATALOG_BYTES,
                "profile catalog",
            )?;
            profile_catalog_digest(&catalog)
        }
        [flag, plan_path] if flag == "--plan-digest" => {
            let plan: WorkflowPlan = read_json(Path::new(plan_path), MAX_PLAN_BYTES, "plan")?;
            workflow_plan_digest(&plan)
        }
        [plan_path, catalog_path, bindings_path] => {
            let plan: WorkflowPlan = read_json(Path::new(plan_path), MAX_PLAN_BYTES, "plan")?;
            let catalog: ProfileCatalog = read_json(
                Path::new(catalog_path),
                MAX_CATALOG_BYTES,
                "profile catalog",
            )?;
            let bindings: BindingDocument = read_json(
                Path::new(bindings_path),
                MAX_BINDINGS_BYTES,
                "bindings",
            )?;
            let batch = bind_plan(&plan, &catalog, &bindings)?;
            serde_json::to_string_pretty(&batch).map_err(|error| {
                cli_error(format!(
                    "failed to serialize bound dispatch batch as JSON: {error}"
                ))
            })
        }
        _ => Err(cli_error(
            "usage: gha-bind-plan <plan.json> <profile-catalog.json> <bindings.json> | --catalog-digest <profile-catalog.json> | --plan-digest <plan.json>",
        )),
    }
}

fn read_json<T: DeserializeOwned>(
    path: &Path,
    maximum_bytes: usize,
    label: &str,
) -> Result<T, ProtocolError> {
    let file = File::open(path).map_err(|error| {
        cli_error(format!(
            "failed to open {label} file {}: {error}",
            path.display()
        ))
    })?;
    let metadata = file.metadata().map_err(|error| {
        cli_error(format!(
            "failed to inspect {label} file {}: {error}",
            path.display()
        ))
    })?;
    if metadata.len() > maximum_bytes as u64 {
        return Err(cli_error(format!(
            "{label} file {} is {} bytes; maximum is {maximum_bytes}",
            path.display(),
            metadata.len()
        )));
    }
    let bytes = read_bounded(file, maximum_bytes, label)?;
    serde_json::from_slice(&bytes).map_err(|error| {
        cli_error(format!(
            "{label} file {} is not valid protocol JSON: {error}",
            path.display()
        ))
    })
}

fn read_bounded<R: Read>(
    reader: R,
    maximum_bytes: usize,
    label: &str,
) -> Result<Vec<u8>, ProtocolError> {
    let read_limit = maximum_bytes
        .checked_add(1)
        .ok_or_else(|| cli_error(format!("{label} admission limit overflow")))?;
    let mut limited = reader.take(read_limit as u64);
    let mut bytes = Vec::with_capacity(usize::min(8 * 1024, maximum_bytes));
    limited
        .read_to_end(&mut bytes)
        .map_err(|error| cli_error(format!("failed to read {label}: {error}")))?;
    if bytes.len() > maximum_bytes {
        return Err(cli_error(format!(
            "{label} input exceeds the {maximum_bytes}-byte admission limit"
        )));
    }
    Ok(bytes)
}

fn cli_error(message: impl Into<String>) -> ProtocolError {
    ProtocolError {
        code: "cli_input",
        message: message.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn bounded_reader_accepts_exact_limit() {
        let bytes = read_bounded(Cursor::new(vec![b'x'; 32]), 32, "fixture").unwrap();
        assert_eq!(bytes.len(), 32);
    }

    #[test]
    fn bounded_reader_rejects_limit_plus_one() {
        let error = read_bounded(Cursor::new(vec![b'x'; 33]), 32, "fixture").unwrap_err();
        assert_eq!(error.code, "cli_input");
        assert!(error.message.contains("32-byte admission limit"));
    }
}
