use std::env;
use std::fs;
use std::io::{self, Read};
use std::process::ExitCode;

use dd_build_server::workflow::plan_workflow;
use dd_build_server::MAX_WORKFLOW_SOURCE_BYTES;

fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() > 1 {
        eprintln!("usage: gha-workflow-plan [workflow.yml|-]");
        return ExitCode::from(2);
    }

    let yaml = match arguments.first().map(String::as_str) {
        Some(path) if path != "-" => read_path(path),
        _ => read_bounded(io::stdin().lock()),
    };
    let yaml = match yaml {
        Ok(yaml) => yaml,
        Err(error) => {
            eprintln!("failed to read workflow YAML: {error}");
            return ExitCode::FAILURE;
        }
    };

    match plan_workflow(&yaml) {
        Ok(plan) => match serde_json::to_string_pretty(&plan) {
            Ok(json) => {
                println!("{json}");
                ExitCode::SUCCESS
            }
            Err(error) => {
                eprintln!("failed to serialize workflow plan: {error}");
                ExitCode::FAILURE
            }
        },
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn read_path(path: &str) -> io::Result<String> {
    let file = fs::File::open(path)?;
    if file.metadata()?.len() > MAX_WORKFLOW_SOURCE_BYTES as u64 {
        return Err(too_large_error());
    }
    read_bounded(file)
}

fn read_bounded<R: Read>(reader: R) -> io::Result<String> {
    let mut limited = reader.take((MAX_WORKFLOW_SOURCE_BYTES + 1) as u64);
    let mut bytes = Vec::with_capacity(usize::min(8 * 1024, MAX_WORKFLOW_SOURCE_BYTES));
    limited.read_to_end(&mut bytes)?;
    if bytes.len() > MAX_WORKFLOW_SOURCE_BYTES {
        return Err(too_large_error());
    }
    String::from_utf8(bytes).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

fn too_large_error() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!("workflow input exceeds the {MAX_WORKFLOW_SOURCE_BYTES}-byte admission limit"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn accepts_exact_source_limit() {
        let input = vec![b'a'; MAX_WORKFLOW_SOURCE_BYTES];
        let accepted = read_bounded(Cursor::new(input))
            .unwrap_or_else(|error| panic!("exact-limit input was rejected: {error}"));
        assert_eq!(accepted.len(), MAX_WORKFLOW_SOURCE_BYTES);
    }

    #[test]
    fn rejects_source_over_limit() {
        let input = vec![b'a'; MAX_WORKFLOW_SOURCE_BYTES + 1];
        let error = read_bounded(Cursor::new(input)).expect_err("oversized input must fail");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("admission limit"));
    }

    #[test]
    fn rejects_invalid_utf8() {
        let error =
            read_bounded(Cursor::new(vec![0xff])).expect_err("workflow source must be valid UTF-8");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }
}
