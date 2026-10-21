//! Read-only GitHub Actions run observer. No fallback issuer, dispatcher, or export.
//! The authenticated transport is fixed to api.github.com; raw user strings
//! cannot select an alternate host or issue a trusted provider diagnostic.
use super::{
    classify_admission_failure, ObserveError, RunIdentity, RunReceipt, WorkflowAdmissionEvidence,
};
use serde_json::Value;
use std::collections::HashSet;
use std::time::Duration;

const MAX_BODY_BYTES: usize = 1_048_576;
const PAGE_SIZE: usize = 100;
const MAX_JOBS: usize = 1_000;

fn identity_valid(id: &RunIdentity) -> bool {
    let Some((owner, name)) = id.repo.split_once('/') else {
        return false;
    };
    let valid_part = |part: &str| {
        !part.is_empty()
            && part.len() <= 100
            && part != "."
            && part != ".."
            && part
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_' | b'.'))
    };
    valid_part(owner)
        && valid_part(name)
        && !name.contains("..")
        && id.run_id > 0
        && id.attempt > 0
        && id.head_sha.len() == 40
        && id.head_sha.bytes().all(|c| c.is_ascii_hexdigit())
}

fn integer(value: &Value, key: &str) -> Result<u64, ObserveError> {
    value
        .get(key)
        .and_then(Value::as_u64)
        .ok_or(ObserveError::MalformedResponse)
}

fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str, ObserveError> {
    value
        .get(key)
        .and_then(Value::as_str)
        .ok_or(ObserveError::MalformedResponse)
}

fn read_run(value: &Value, identity: &RunIdentity) -> Result<(String, String), ObserveError> {
    let full_name = value
        .get("repository")
        .and_then(|repo| repo.get("full_name"))
        .and_then(Value::as_str)
        .ok_or(ObserveError::MalformedResponse)?;
    if integer(value, "id")? != identity.run_id
        || integer(value, "run_attempt")? != identity.attempt
        || string(value, "head_sha")? != identity.head_sha
        || !full_name.eq_ignore_ascii_case(&identity.repo)
    {
        return Err(ObserveError::MismatchedIdentity);
    }
    let status = string(value, "status")?;
    let conclusion = string(value, "conclusion")?;
    if status.is_empty() || conclusion.is_empty() {
        return Err(ObserveError::MalformedResponse);
    }
    Ok((status.to_owned(), conclusion.to_owned()))
}

/// Parses exactly the expected pages from the attempt-scoped GitHub endpoint.
/// Every job and every step array must be present and bound to this run,
/// attempt and head. A job assigned to a runner blocks fallback.
fn read_pages(pages: &[Value], id: &RunIdentity) -> Result<(u32, u32, bool), ObserveError> {
    if pages.is_empty() || pages.len() > MAX_JOBS / PAGE_SIZE {
        return Err(ObserveError::IncompletePagination);
    }
    let mut expected_total: Option<usize> = None;
    let mut count = 0_usize;
    let mut step_count = 0_usize;
    let mut assigned = false;
    let mut seen = HashSet::new();
    for (index, page) in pages.iter().enumerate() {
        let total = usize::try_from(integer(page, "total_count")?)
            .map_err(|_| ObserveError::MalformedResponse)?;
        if total > MAX_JOBS || expected_total.is_some_and(|prior| prior != total) {
            return Err(ObserveError::IncompletePagination);
        }
        expected_total = Some(total);
        let jobs = page
            .get("jobs")
            .and_then(Value::as_array)
            .ok_or(ObserveError::MalformedResponse)?;
        let expected_len = total.saturating_sub(index * PAGE_SIZE).min(PAGE_SIZE);
        if jobs.len() != expected_len {
            return Err(ObserveError::IncompletePagination);
        }
        for job in jobs {
            if integer(job, "run_id")? != id.run_id
                || integer(job, "run_attempt")? != id.attempt
                || string(job, "head_sha")? != id.head_sha
            {
                return Err(ObserveError::MismatchedIdentity);
            }
            let job_id = integer(job, "id")?;
            if job_id == 0 || !seen.insert(job_id) {
                return Err(ObserveError::IncompletePagination);
            }
            if string(job, "status")? != "completed" {
                return Err(ObserveError::IncompletePagination);
            }
            let _ = string(job, "conclusion")?;
            let steps = job
                .get("steps")
                .and_then(Value::as_array)
                .ok_or(ObserveError::MalformedResponse)?;
            step_count = step_count
                .checked_add(steps.len())
                .ok_or(ObserveError::IncompletePagination)?;
            for step in steps {
                if integer(step, "number")? == 0 || string(step, "name")?.is_empty() {
                    return Err(ObserveError::MalformedResponse);
                }
            }
            if job
                .get("runner_id")
                .and_then(Value::as_u64)
                .is_some_and(|n| n > 0)
                || job
                    .get("runner_name")
                    .and_then(Value::as_str)
                    .is_some_and(|s| !s.is_empty())
            {
                assigned = true;
            }
        }
        count = count
            .checked_add(jobs.len())
            .ok_or(ObserveError::IncompletePagination)?;
    }
    let total = expected_total.ok_or(ObserveError::IncompletePagination)?;
    if count != total || pages.len() != total.max(1).div_ceil(PAGE_SIZE) {
        return Err(ObserveError::IncompletePagination);
    }
    Ok((
        u32::try_from(count).map_err(|_| ObserveError::IncompletePagination)?,
        u32::try_from(step_count).map_err(|_| ObserveError::IncompletePagination)?,
        assigned,
    ))
}

fn make_receipt(
    id: &RunIdentity,
    first_run: &Value,
    pages: &[Value],
    second_run: &Value,
) -> Result<RunReceipt, ObserveError> {
    if !identity_valid(id) {
        return Err(ObserveError::InvalidIdentity);
    }
    let (status, conclusion) = read_run(first_run, id)?;
    if read_run(second_run, id)? != (status.clone(), conclusion.clone()) {
        return Err(ObserveError::ChangedDuringObservation);
    }
    let (jobs_observed, steps_observed, runner_assigned) = read_pages(pages, id)?;
    // These response fields alone cannot prove billing or capacity as a
    // machine-readable provider diagnosis. There is deliberately no issuer.
    // A runner assignment is conservatively treated as execution evidence.
    let classification = classify_admission_failure(&WorkflowAdmissionEvidence {
        conclusion: Some(&conclusion),
        status: Some(&status),
        jobs_observed: Some(jobs_observed),
        steps_observed: Some(steps_observed.max(u32::from(runner_assigned))),
        runner_name: runner_assigned.then_some("assigned-runner"),
        runner_labels: &[],
        platform_diagnostics: &[],
    });
    debug_assert!(!classification.fallback_allowed);
    Ok(RunReceipt {
        identity: id.clone(),
        jobs_observed,
        steps_observed,
        runner_assigned,
        classification,
    })
}

async fn get_json(client: &reqwest::Client, token: &str, url: &str) -> Result<Value, ObserveError> {
    let mut response = client
        .get(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "gha-indie-worker-admission-observer")
        .bearer_auth(token)
        .send()
        .await
        .map_err(|_| ObserveError::Transport)?;
    if response.status() != reqwest::StatusCode::OK
        || response
            .content_length()
            .is_some_and(|n| n > MAX_BODY_BYTES as u64)
    {
        return Err(ObserveError::Transport);
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| ObserveError::Transport)?
    {
        if chunk.len() > MAX_BODY_BYTES - body.len() {
            return Err(ObserveError::MalformedResponse);
        }
        body.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&body).map_err(|_| ObserveError::MalformedResponse)
}

/// Uses the caller's read-scoped token, fixed HTTPS GitHub API origin,
/// attempt-specific job pages and a second run fetch to catch concurrent reruns.
/// No job execution, mutation, exports, or automatic source transfer.
pub(crate) async fn inspect_authenticated(
    token: &str,
    id: &RunIdentity,
) -> Result<RunReceipt, ObserveError> {
    if !identity_valid(id) || token.is_empty() {
        return Err(ObserveError::InvalidIdentity);
    }
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| ObserveError::Transport)?;
    let base = format!(
        "https://api.github.com/repos/{}/actions/runs/{}",
        id.repo, id.run_id
    );
    let first = get_json(&client, token, &base).await?;
    let first_page = get_json(
        &client,
        token,
        &format!(
            "{base}/attempts/{}/jobs?per_page={PAGE_SIZE}&page=1",
            id.attempt
        ),
    )
    .await?;
    let count = usize::try_from(integer(&first_page, "total_count")?)
        .map_err(|_| ObserveError::MalformedResponse)?;
    if count > MAX_JOBS {
        return Err(ObserveError::IncompletePagination);
    }
    let total_pages = count.max(1).div_ceil(PAGE_SIZE);
    let mut pages = vec![first_page];
    for number in 2..=total_pages {
        let url = format!(
            "{base}/attempts/{}/jobs?per_page={PAGE_SIZE}&page={number}",
            id.attempt
        );
        pages.push(get_json(&client, token, &url).await?);
    }
    let second = get_json(&client, token, &base).await?;
    make_receipt(id, &first, &pages, &second)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn id() -> RunIdentity {
        RunIdentity {
            repo: "gha-indie-worker/gha-indie-worker.rs".into(),
            run_id: 42,
            attempt: 2,
            head_sha: "a".repeat(40),
        }
    }
    fn run(id: &RunIdentity) -> Value {
        json!({"id":id.run_id, "run_attempt":id.attempt,
            "head_sha":id.head_sha, "status":"completed", "conclusion":"failure",
            "repository":{"full_name":id.repo}})
    }
    fn job(id: &RunIdentity, job_id: u64, steps: usize) -> Value {
        let steps: Vec<Value> = (0..steps)
            .map(|n| json!({"number":n+1, "name":"step"}))
            .collect();
        json!({"id":job_id, "run_id":id.run_id, "run_attempt":id.attempt,
            "head_sha":id.head_sha, "status":"completed", "conclusion":"failure",
            "runner_id":null, "runner_name":null, "steps":steps})
    }
    #[test]
    fn zero_jobs_never_authorizes_fallback_without_typed_diagnostic() {
        let id = id();
        let r = run(&id);
        let receipt = make_receipt(&id, &r, &[json!({"total_count":0,"jobs":[]})], &r).unwrap();
        assert_eq!(receipt.jobs_observed, 0);
        assert_eq!(receipt.steps_observed, 0);
        assert!(!receipt.classification.fallback_allowed);
    }
    #[test]
    fn stepful_job_fails_closed() {
        let id = id();
        let r = run(&id);
        let receipt = make_receipt(
            &id,
            &r,
            &[json!({"total_count":1,"jobs":[job(&id,5,3)]})],
            &r,
        )
        .unwrap();
        assert_eq!(receipt.steps_observed, 3);
        assert!(!receipt.classification.fallback_allowed);
    }
    #[test]
    fn duplicate_job_or_missing_pages_rejected() {
        let id = id();
        let j = job(&id, 5, 0);
        assert_eq!(
            read_pages(&[json!({"total_count":2,"jobs":[j.clone(),j]})], &id),
            Err(ObserveError::IncompletePagination)
        );
        assert_eq!(
            read_pages(&[json!({"total_count":101,"jobs":[]})], &id),
            Err(ObserveError::IncompletePagination)
        );
    }
    #[test]
    fn exact_two_page_job_coverage_and_inconsistent_page_totals() {
        let id = id();
        let first: Vec<Value> = (1..=100).map(|n| job(&id, n, 0)).collect();
        let second = json!({"total_count":101,"jobs":[job(&id, 101, 0)]});
        let pages = [json!({"total_count":101,"jobs":first}), second.clone()];
        assert_eq!(read_pages(&pages, &id), Ok((101, 0, false)));
        assert_eq!(
            read_pages(&pages[..1], &id),
            Err(ObserveError::IncompletePagination)
        );
        let mut tampered = second;
        tampered["total_count"] = json!(102);
        assert_eq!(
            read_pages(&[pages[0].clone(), tampered], &id),
            Err(ObserveError::IncompletePagination)
        );
    }

    #[test]
    fn attempt_sha_repo_and_run_binding_rejected_on_mismatch() {
        let id = id();
        let r = run(&id);
        for field in ["id", "run_attempt", "head_sha"] {
            let mut wrong = r.clone();
            wrong[field] = if field == "head_sha" {
                json!("b".repeat(40))
            } else {
                json!(999)
            };
            assert_eq!(
                make_receipt(&id, &wrong, &[json!({"total_count":0,"jobs":[]})], &r).unwrap_err(),
                ObserveError::MismatchedIdentity
            );
        }
        let mut wrong = r.clone();
        wrong["repository"]["full_name"] = json!("other/repo");
        assert_eq!(read_run(&wrong, &id), Err(ObserveError::MismatchedIdentity));
    }
    #[test]
    fn changed_run_state_after_pagination_rejected() {
        let id = id();
        let r = run(&id);
        let mut newer = r.clone();
        newer["conclusion"] = json!("success");
        assert_eq!(
            make_receipt(&id, &r, &[json!({"total_count":0,"jobs":[]})], &newer).unwrap_err(),
            ObserveError::ChangedDuringObservation
        );
    }
    #[test]
    fn job_with_mismatched_attempt_or_head_rejected() {
        let id = id();
        let mut j = job(&id, 7, 0);
        j["run_attempt"] = json!(1);
        assert_eq!(
            read_pages(&[json!({"total_count":1,"jobs":[j]})], &id),
            Err(ObserveError::MismatchedIdentity)
        );
    }
    #[test]
    fn runner_assignment_never_authorizes_fallback() {
        let id = id();
        let r = run(&id);
        let mut j = job(&id, 7, 0);
        j["runner_id"] = json!(101);
        let receipt = make_receipt(&id, &r, &[json!({"total_count":1,"jobs":[j]})], &r).unwrap();
        assert!(receipt.runner_assigned);
        assert!(!receipt.classification.fallback_allowed);
    }
    #[test]
    fn rejects_incomplete_steps_and_invalid_identity() {
        let id = id();
        let mut j = job(&id, 7, 0);
        j.as_object_mut().unwrap().remove("steps");
        assert_eq!(
            read_pages(&[json!({"total_count":1,"jobs":[j]})], &id),
            Err(ObserveError::MalformedResponse)
        );
        let mut wrong = id;
        wrong.repo = "https://evil.example".into();
        assert!(!identity_valid(&wrong));
    }
}
