use std::env;
use std::fs;
use std::io::{self, Read};
use std::process::ExitCode;

use dd_build_server::workflow::plan_workflow;

fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() > 1 {
        eprintln!("usage: gha-workflow-plan [workflow.yml|-]");
        return ExitCode::from(2);
    }

    let yaml = match arguments.first().map(String::as_str) {
        Some(path) if path != "-" => fs::read_to_string(path),
        _ => read_stdin(),
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

fn read_stdin() -> io::Result<String> {
    let mut yaml = String::new();
    io::stdin().read_to_string(&mut yaml)?;
    Ok(yaml)
}
