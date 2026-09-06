use std::collections::{BTreeMap, BTreeSet};

use crate::digest::{dispatch_request_digest, stable_request_id};
use crate::model::{
    BindingDocument, DispatchBatch, DispatchRequest, ProfileCatalog, ProtocolError, WorkflowPlan,
};
use crate::validate::{
    validate_bindings_shape, validate_catalog, validate_context_dir, validate_digest,
    validate_plan, validate_profile_name,
};
use crate::{profile_catalog_digest, workflow_plan_digest, DISPATCH_BATCH_SCHEMA, DISPATCH_SCHEMA};

pub fn bind_plan(
    plan: &WorkflowPlan,
    catalog: &ProfileCatalog,
    bindings: &BindingDocument,
) -> Result<DispatchBatch, ProtocolError> {
    validate_plan(plan)?;
    validate_catalog(catalog)?;
    validate_bindings_shape(bindings)?;

    let catalog_digest = profile_catalog_digest(catalog)?;
    if bindings.profile_catalog_digest != catalog_digest {
        return Err(ProtocolError::new(
            "profile_catalog_digest_mismatch",
            format!(
                "bindings reference {}, but supplied catalog digest is {catalog_digest}",
                bindings.profile_catalog_digest
            ),
        ));
    }

    let profile_by_name = catalog
        .profiles
        .iter()
        .map(|profile| (profile.name.as_str(), profile))
        .collect::<BTreeMap<_, _>>();
    let base_job_ids = plan
        .jobs
        .iter()
        .map(|job| job.base_job_id.clone())
        .collect::<BTreeSet<_>>();
    let binding_ids = bindings.jobs.keys().cloned().collect::<BTreeSet<_>>();
    if binding_ids != base_job_ids {
        let missing = base_job_ids
            .difference(&binding_ids)
            .cloned()
            .collect::<Vec<_>>();
        let extra = binding_ids
            .difference(&base_job_ids)
            .cloned()
            .collect::<Vec<_>>();
        return Err(ProtocolError::new(
            "binding_coverage_mismatch",
            format!(
                "bindings must cover every base job exactly once; missing={missing:?}, extra={extra:?}"
            ),
        ));
    }

    for (base_job_id, binding) in &bindings.jobs {
        validate_profile_name(&binding.profile)?;
        validate_digest("profileDigest", &binding.profile_digest)?;
        let Some(installed) = profile_by_name.get(binding.profile.as_str()) else {
            return Err(ProtocolError::new(
                "unknown_profile",
                format!(
                    "base job {base_job_id:?} selects absent profile {:?}",
                    binding.profile
                ),
            ));
        };
        if installed.digest != binding.profile_digest {
            return Err(ProtocolError::new(
                "profile_digest_mismatch",
                format!(
                    "base job {base_job_id:?} binds {} but catalog contains {}",
                    binding.profile_digest, installed.digest
                ),
            ));
        }
        validate_context_dir(binding.context_dir.as_deref().unwrap_or("."))?;
    }

    let plan_digest = workflow_plan_digest(plan)?;
    let order_index = plan
        .job_order
        .iter()
        .enumerate()
        .map(|(index, job_id)| (job_id.as_str(), index))
        .collect::<BTreeMap<_, _>>();
    let mut requests = Vec::with_capacity(plan.jobs.len());
    for job in &plan.jobs {
        let binding = bindings.jobs.get(&job.base_job_id).ok_or_else(|| {
            ProtocolError::new("internal_binding_error", "validated binding disappeared")
        })?;
        let job_order_index = *order_index.get(job.base_job_id.as_str()).ok_or_else(|| {
            ProtocolError::new(
                "invalid_job_order",
                format!("base job {:?} is missing from jobOrder", job.base_job_id),
            )
        })?;
        let mut request = DispatchRequest {
            schema_version: DISPATCH_SCHEMA.to_string(),
            request_id: String::new(),
            request_digest: String::new(),
            plan_digest: plan_digest.clone(),
            profile_catalog_digest: catalog_digest.clone(),
            repository_url: bindings.repository_url.clone(),
            commit_sha: bindings.commit_sha.clone(),
            job_instance_id: job.id.clone(),
            base_job_id: job.base_job_id.clone(),
            job_order_index,
            profile: binding.profile.clone(),
            profile_digest: binding.profile_digest.clone(),
            context_dir: binding
                .context_dir
                .clone()
                .unwrap_or_else(|| ".".to_string()),
            needs_instances: job.needs_instances.clone(),
            matrix: job.matrix.clone(),
            fail_fast: job.fail_fast,
            max_parallel: job.max_parallel,
        };
        request.request_id = stable_request_id(&request)?;
        request.request_digest = dispatch_request_digest(&request)?;
        requests.push(request);
    }

    Ok(DispatchBatch {
        schema_version: DISPATCH_BATCH_SCHEMA.to_string(),
        plan_digest,
        profile_catalog_digest: catalog_digest,
        repository_url: bindings.repository_url.clone(),
        commit_sha: bindings.commit_sha.clone(),
        requests,
    })
}
