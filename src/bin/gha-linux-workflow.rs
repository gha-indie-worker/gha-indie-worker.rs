use std::path::PathBuf;
use std::process::ExitCode;

use dd_build_server::workflow::plan_workflow;
use dd_build_server::workflow_runner::{
    execute_trusted_linux_workflow, TrustedLinuxWorkflowConfig, WorkflowConclusion,
};

const USAGE: &str = "Usage: gha-linux-workflow --workflow <file> --run-root <directory> [--max-parallel <jobs>] --allow-host-execution";

struct Arguments {
    workflow: PathBuf,
    run_root: PathBuf,
    maximum_parallel_jobs: usize,
    allow_host_execution: bool,
}

impl Arguments {
    fn parse() -> Result<Option<Self>, String> {
        Self::parse_values(std::env::args().skip(1))
    }

    fn parse_values(values: impl IntoIterator<Item = String>) -> Result<Option<Self>, String> {
        let mut workflow = None;
        let mut run_root = None;
        let mut maximum_parallel_jobs = 1usize;
        let mut allow_host_execution = false;
        let mut values = values.into_iter();
        while let Some(argument) = values.next() {
            match argument.as_str() {
                "--help" | "-h" => return Ok(None),
                "--workflow" => {
                    workflow = Some(PathBuf::from(
                        values
                            .next()
                            .ok_or_else(|| "--workflow requires a path".to_string())?,
                    ));
                }
                "--run-root" => {
                    run_root =
                        Some(PathBuf::from(values.next().ok_or_else(|| {
                            "--run-root requires a directory".to_string()
                        })?));
                }
                "--max-parallel" => {
                    let raw = values
                        .next()
                        .ok_or_else(|| "--max-parallel requires a positive integer".to_string())?;
                    maximum_parallel_jobs = raw.parse::<usize>().map_err(|_| {
                        format!("--max-parallel value {raw:?} is not a positive integer")
                    })?;
                    if maximum_parallel_jobs == 0 {
                        return Err("--max-parallel must be greater than zero".to_string());
                    }
                }
                "--allow-host-execution" => allow_host_execution = true,
                unknown => return Err(format!("unknown argument {unknown:?}")),
            }
        }
        Ok(Some(Self {
            workflow: workflow.ok_or_else(|| "--workflow is required".to_string())?,
            run_root: run_root.ok_or_else(|| "--run-root is required".to_string())?,
            maximum_parallel_jobs,
            allow_host_execution,
        }))
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let arguments = match Arguments::parse() {
        Ok(Some(arguments)) => arguments,
        Ok(None) => {
            println!("{USAGE}");
            return ExitCode::SUCCESS;
        }
        Err(error) => {
            eprintln!("{error}\n{USAGE}");
            return ExitCode::from(2);
        }
    };

    let source = match tokio::fs::read_to_string(&arguments.workflow).await {
        Ok(source) => source,
        Err(error) => {
            eprintln!(
                "failed to read workflow {}: {error}",
                arguments.workflow.display()
            );
            return ExitCode::from(2);
        }
    };
    let plan = match plan_workflow(&source) {
        Ok(plan) => plan,
        Err(error) => {
            eprintln!("workflow planning failed: {error}");
            return ExitCode::from(2);
        }
    };
    let mut config = TrustedLinuxWorkflowConfig::new(arguments.run_root);
    config.allow_host_process_execution = arguments.allow_host_execution;
    config.maximum_parallel_jobs = arguments.maximum_parallel_jobs;
    let result = match execute_trusted_linux_workflow(&plan, &config).await {
        Ok(result) => result,
        Err(error) => {
            eprintln!("Linux workflow scheduler rejected the plan: {error}");
            return ExitCode::from(2);
        }
    };
    match serde_json::to_string_pretty(&result) {
        Ok(json) => println!("{json}"),
        Err(error) => {
            eprintln!("failed to serialize Linux workflow result: {error}");
            return ExitCode::from(2);
        }
    }
    if result.conclusion == WorkflowConclusion::Success {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_required_authority_and_parallel_limit() {
        let parsed = Arguments::parse_values(
            [
                "--workflow",
                "workflow.yml",
                "--run-root",
                "runs",
                "--max-parallel",
                "3",
                "--allow-host-execution",
            ]
            .into_iter()
            .map(str::to_string),
        )
        .expect("arguments should parse")
        .expect("help was not requested");
        assert_eq!(parsed.workflow, PathBuf::from("workflow.yml"));
        assert_eq!(parsed.run_root, PathBuf::from("runs"));
        assert_eq!(parsed.maximum_parallel_jobs, 3);
        assert!(parsed.allow_host_execution);
    }

    #[test]
    fn rejects_zero_parallelism_and_unknown_arguments() {
        let zero = Arguments::parse_values(
            [
                "--workflow",
                "workflow.yml",
                "--run-root",
                "runs",
                "--max-parallel",
                "0",
            ]
            .into_iter()
            .map(str::to_string),
        )
        .err()
        .expect("zero should fail");
        assert!(zero.contains("greater than zero"));

        let unknown = Arguments::parse_values(["--wat"].into_iter().map(str::to_string))
            .err()
            .expect("unknown argument should fail");
        assert!(unknown.contains("unknown argument"));
    }
}
