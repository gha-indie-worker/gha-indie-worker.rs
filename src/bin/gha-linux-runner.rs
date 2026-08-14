use std::path::PathBuf;
use std::process::ExitCode;

use dd_build_server::linux_runner::{
    execute_trusted_linux_job, JobConclusion, TrustedLinuxRunnerConfig,
};
use dd_build_server::workflow::{plan_workflow, PlannedJob};

const USAGE: &str = "Usage: gha-linux-runner --workflow <file> --job <id> --workspace <directory> --allow-host-execution";

struct Arguments {
    workflow: PathBuf,
    job: String,
    workspace: PathBuf,
    allow_host_execution: bool,
}

impl Arguments {
    fn parse() -> Result<Option<Self>, String> {
        let mut workflow = None;
        let mut job = None;
        let mut workspace = None;
        let mut allow_host_execution = false;
        let mut values = std::env::args().skip(1);
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
                "--job" => {
                    job = Some(
                        values
                            .next()
                            .ok_or_else(|| "--job requires an identifier".to_string())?,
                    );
                }
                "--workspace" => {
                    workspace =
                        Some(PathBuf::from(values.next().ok_or_else(|| {
                            "--workspace requires a directory".to_string()
                        })?));
                }
                "--allow-host-execution" => allow_host_execution = true,
                unknown => return Err(format!("unknown argument {unknown:?}")),
            }
        }
        Ok(Some(Self {
            workflow: workflow.ok_or_else(|| "--workflow is required".to_string())?,
            job: job.ok_or_else(|| "--job is required".to_string())?,
            workspace: workspace.ok_or_else(|| "--workspace is required".to_string())?,
            allow_host_execution,
        }))
    }
}

fn select_job(jobs: &[PlannedJob], requested: &str) -> Result<PlannedJob, String> {
    if let Some(job) = jobs.iter().find(|job| job.id == requested) {
        return Ok(job.clone());
    }
    let matches = jobs
        .iter()
        .filter(|job| job.base_job_id == requested)
        .collect::<Vec<_>>();
    match matches.as_slice() {
        [job] => Ok((*job).clone()),
        [] => Err(format!(
            "planned workflow has no job or instance {requested:?}"
        )),
        many => Err(format!(
            "job {requested:?} expands to {} matrix instances; select one of: {}",
            many.len(),
            many.iter()
                .map(|job| job.id.as_str())
                .collect::<Vec<_>>()
                .join(", ")
        )),
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
    let job = match select_job(&plan.jobs, &arguments.job) {
        Ok(job) => job,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::from(2);
        }
    };

    let mut config = TrustedLinuxRunnerConfig::new(arguments.workspace);
    config.allow_host_process_execution = arguments.allow_host_execution;
    let result = match execute_trusted_linux_job(&job, &config).await {
        Ok(result) => result,
        Err(error) => {
            eprintln!("Linux runner rejected the job: {error}");
            return ExitCode::from(2);
        }
    };
    match serde_json::to_string_pretty(&result) {
        Ok(json) => println!("{json}"),
        Err(error) => {
            eprintln!("failed to serialize Linux runner result: {error}");
            return ExitCode::from(2);
        }
    }
    if result.conclusion == JobConclusion::Success {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selects_exact_or_single_base_job_and_rejects_ambiguous_matrix() {
        let single = plan_workflow(
            "jobs:\n  one:\n    runs-on: ubuntu-latest\n    steps: [{ run: echo one }]\n",
        )
        .unwrap();
        assert_eq!(select_job(&single.jobs, "one").unwrap().id, "one");

        let matrix = plan_workflow(
            "jobs:\n  many:\n    runs-on: ubuntu-latest\n    strategy:\n      matrix:\n        value: [one, two]\n    steps: [{ run: echo matrix }]\n",
        )
        .unwrap();
        let error = select_job(&matrix.jobs, "many").unwrap_err();
        assert!(error.contains("2 matrix instances"));
        let first = matrix.jobs.first().unwrap();
        assert_eq!(select_job(&matrix.jobs, &first.id).unwrap().id, first.id);
    }
}
