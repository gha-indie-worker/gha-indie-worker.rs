//! Source-free reference oracle for GitHub App Check Run recovery issue #79.
//! Runtime integration is owned by the separately stacked reporting PR #97.
//! Only a complete filter=all scan may authorize a "missing" conclusion.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Check {
    pub id: u64,
    pub owner: String,
    pub repository: String,
    pub head_sha: String,
    pub name: String,
    pub external_id: String,
    pub app_id: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target<'a> {
    pub owner: &'a str,
    pub repository: &'a str,
    pub head_sha: &'a str,
    pub name: &'a str,
    pub external_id: &'a str,
    pub app_id: u64,
}
#[derive(Clone, Debug)]
pub struct Page {
    pub checks: Vec<Check>,
    pub total_count: usize,
    pub has_next: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Recovery { Found(u64), Missing }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecoveryError { Incomplete, Duplicated, InvalidPagination, InvalidTarget }

pub const PAGE_SIZE: usize = 100;
pub const MAX_PAGES: usize = 100;

/// These literals are the required GitHub collection query, not a URL
/// generated from caller-supplied strings.
pub const CHECK_RUN_QUERY: &str = "filter=all&per_page=100";

pub fn resolve_complete(
    target: &Target<'_>,
    pages: &[Result<Page, RecoveryError>],
) -> Result<Recovery, RecoveryError> {
    if target.owner.is_empty() || target.repository.is_empty()
        || target.head_sha.len() != 40
        || !target.head_sha.bytes().all(|c| c.is_ascii_hexdigit())
        || target.name.is_empty() || target.external_id.is_empty() || target.app_id == 0 {
        return Err(RecoveryError::InvalidTarget);
    }
    if pages.is_empty() || pages.len() > MAX_PAGES { return Err(RecoveryError::Incomplete); }
    let mut matching: Option<u64> = None;
    let mut seen = 0usize;
    let mut total: Option<usize> = None;
    for (index, result) in pages.iter().enumerate() {
        let page = result.as_ref().map_err(|_| RecoveryError::Incomplete)?;
        if page.checks.len() > PAGE_SIZE { return Err(RecoveryError::InvalidPagination); }
        if let Some(t) = total {
            if t != page.total_count { return Err(RecoveryError::InvalidPagination); }
        } else {
            total = Some(page.total_count);
        }
        seen = seen.checked_add(page.checks.len()).ok_or(RecoveryError::InvalidPagination)?;
        if page.has_next != (index + 1 < pages.len()) {
            return Err(RecoveryError::Incomplete);
        }
        for check in &page.checks {
            if check.owner == target.owner && check.repository == target.repository
                && check.head_sha == target.head_sha && check.name == target.name
                && check.external_id == target.external_id && check.app_id == target.app_id {
                if check.id == 0 { return Err(RecoveryError::InvalidPagination); }
                if matching.is_some() { return Err(RecoveryError::Duplicated); }
                matching = Some(check.id);
            }
        }
    }
    if seen != total.unwrap_or(usize::MAX) { return Err(RecoveryError::Incomplete); }
    Ok(matching.map_or(Recovery::Missing, Recovery::Found))
}

#[cfg(test)]
mod tests {
    use super::*;
    const HEAD: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    fn target() -> Target<'static> {
        Target { owner: "org", repository: "repo", head_sha: HEAD,
            name: "indiebuild.dev/ci", external_id: "attempt-7", app_id: 42 }
    }
    fn check(id: u64) -> Check {
        Check { id, owner: "org".into(), repository: "repo".into(),
            head_sha: HEAD.into(), name: "indiebuild.dev/ci".into(),
            external_id: "attempt-7".into(), app_id: 42 }
    }
    fn page(checks: Vec<Check>, total_count: usize, has_next: bool) -> Result<Page, RecoveryError> {
        Ok(Page { checks, total_count, has_next })
    }
    #[test]
    fn older_than_latest_same_name_run_recovers() {
        let mut latest = check(8);
        latest.external_id = "newer-attempt".into();
        assert_eq!(resolve_complete(&target(), &[page(vec![latest, check(7)], 2, false)]),
            Ok(Recovery::Found(7)));
    }
    #[test]
    fn matching_run_on_second_page_is_found() {
        let mut unrelated = check(8);
        unrelated.external_id = "other".into();
        let first = vec![unrelated; PAGE_SIZE];
        assert_eq!(resolve_complete(&target(), &[page(first,101,true),page(vec![check(7)],101,false)]),
            Ok(Recovery::Found(7)));
    }
    #[test]
    fn failed_missing_or_truncated_pagination_never_implies_absence() {
        assert_eq!(resolve_complete(&target(), &[page(vec![],1,false)]),Err(RecoveryError::Incomplete));
        assert_eq!(resolve_complete(&target(), &[page(vec![],1,true)]),Err(RecoveryError::Incomplete));
        assert_eq!(resolve_complete(&target(), &[Err(RecoveryError::Incomplete)]),Err(RecoveryError::Incomplete));
    }
    #[test]
    fn duplicate_exact_authoritative_runs_are_integrity_failure() {
        assert_eq!(resolve_complete(&target(), &[page(vec![check(7),check(8)],2,false)]),
            Err(RecoveryError::Duplicated));
    }
    #[test]
    fn wrong_head_app_context_or_repo_is_not_recovered() {
        let mut wrong_head=check(2);wrong_head.head_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into();
        let mut wrong_app=check(3);wrong_app.app_id=43;
        let mut wrong_repo=check(4);wrong_repo.repository="other".into();
        let mut wrong_name=check(5);wrong_name.name="other-context".into();
        assert_eq!(resolve_complete(&target(), &[page(vec![wrong_head,wrong_app,wrong_repo,wrong_name],4,false)]),
            Ok(Recovery::Missing));
    }
    #[test]
    fn crash_after_create_recovers_numeric_id_without_ambiguous_new_create() {
        // Durable external id was recorded but Check Run id was not; recovery
        // must return the *existing* id rather than infer absence.
        assert_eq!(resolve_complete(&target(), &[page(vec![check(101)],1,false)]),
            Ok(Recovery::Found(101)));
    }
    #[test]
    fn configured_query_never_uses_filter_latest() {
        assert_eq!(CHECK_RUN_QUERY,"filter=all&per_page=100");
        assert!(!CHECK_RUN_QUERY.contains("latest"));
    }
}
