use std::collections::{BTreeMap, BTreeSet};

use super::OrgRepositoryScope;
use crate::error::RuntimeError;
use crate::github::GitHubRepository;

const PREFIX_INFERENCE_MIN_REPOSITORIES_EXCLUSIVE: usize = 7;
const PREFIX_DOMINANCE_NUMERATOR: usize = 4;
const PREFIX_DOMINANCE_DENOMINATOR: usize = 7;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum FamilyPrefixSource {
    Disabled,
    Explicit,
    Statistical,
    OwnerFallback,
}

impl FamilyPrefixSource {
    pub(super) const fn as_str(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Explicit => "explicit",
            Self::Statistical => "statistical",
            Self::OwnerFallback => "owner-fallback",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct FamilyPrefixResolution {
    pub(super) prefix: Option<String>,
    pub(super) source: FamilyPrefixSource,
}

/// Parse shell or JSON-shaped repository arguments into a stable name list.
pub fn normalize_repository_arguments(values: &[String]) -> Result<Vec<String>, RuntimeError> {
    let mut repositories = Vec::new();
    for value in values {
        let value = value.trim();
        if value.is_empty() {
            continue;
        }

        if value.starts_with('[') {
            let decoded = serde_json::from_str::<Vec<String>>(value).map_err(|error| {
                RuntimeError::Usage(format!("invalid JSON repository list argument: {error}"))
            })?;
            repositories.extend(decoded);
            continue;
        }

        let decoded = if value.starts_with('"') {
            serde_json::from_str::<String>(value).map_err(|error| {
                RuntimeError::Usage(format!("invalid JSON repository name argument: {error}"))
            })?
        } else {
            value.to_owned()
        };
        repositories.extend(decoded.split(',').map(str::to_owned));
    }

    for repository in &mut repositories {
        *repository = repository.trim().to_owned();
        validate_repository_name(repository)?;
    }
    repositories.sort();
    repositories.dedup();
    Ok(repositories)
}

pub(crate) fn expected_repository_names(
    explicit: &[String],
    family_prefix: Option<&str>,
    family_members: &[String],
) -> BTreeSet<String> {
    let mut expected = explicit
        .iter()
        .map(|name| name.trim())
        .filter(|name| !name.is_empty())
        .map(ToOwned::to_owned)
        .collect::<BTreeSet<_>>();

    if let Some(prefix) = family_prefix
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        expected.extend(
            family_members
                .iter()
                .map(|member| member.trim())
                .filter(|member| !member.is_empty())
                .map(|member| format!("{prefix}-{member}")),
        );
    }

    expected
}

pub(super) fn resolve_family_prefix(
    repositories: &[GitHubRepository],
    scope: &OrgRepositoryScope,
) -> FamilyPrefixResolution {
    if scope.family_members.is_empty() {
        return FamilyPrefixResolution {
            prefix: None,
            source: FamilyPrefixSource::Disabled,
        };
    }

    if let Some(prefix) = scope
        .family_prefix
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        return FamilyPrefixResolution {
            prefix: Some(prefix.to_owned()),
            source: FamilyPrefixSource::Explicit,
        };
    }

    let inventory_is_complete = repositories.len() < scope.repo_limit;
    if inventory_is_complete
        && repositories.len() > PREFIX_INFERENCE_MIN_REPOSITORIES_EXCLUSIVE
    {
        let mut counts = BTreeMap::<String, usize>::new();
        for repository in repositories {
            if let Some(prefix) = repository_family_prefix(&repository.name, &scope.family_members) {
                *counts.entry(prefix).or_default() += 1;
            }
        }

        let best_count = counts.values().copied().max().unwrap_or_default();
        if best_count > 0
            && best_count.saturating_mul(PREFIX_DOMINANCE_DENOMINATOR)
                > repositories
                    .len()
                    .saturating_mul(PREFIX_DOMINANCE_NUMERATOR)
        {
            let mut winners = counts
                .iter()
                .filter(|(_, count)| **count == best_count)
                .map(|(prefix, _)| prefix);
            if let Some(prefix) = winners.next()
                && winners.next().is_none()
            {
                return FamilyPrefixResolution {
                    prefix: Some(prefix.clone()),
                    source: FamilyPrefixSource::Statistical,
                };
            }
        }
    }

    FamilyPrefixResolution {
        prefix: Some(scope.owner.clone()),
        source: FamilyPrefixSource::OwnerFallback,
    }
}

fn repository_family_prefix(name: &str, family_members: &[String]) -> Option<String> {
    let lower_name = name.to_ascii_lowercase();
    family_members
        .iter()
        .map(|member| member.trim())
        .filter(|member| !member.is_empty())
        .filter_map(|member| {
            let suffix = format!("-{}", member.to_ascii_lowercase());
            lower_name
                .strip_suffix(&suffix)
                .filter(|prefix| !prefix.is_empty())
                .map(|prefix| (suffix.len(), prefix.to_owned()))
        })
        .max_by_key(|(suffix_len, _)| *suffix_len)
        .map(|(_, prefix)| prefix)
}

pub(super) fn missing_repository_names(
    repositories: &[GitHubRepository],
    expected: &BTreeSet<String>,
) -> Vec<String> {
    let existing = repositories
        .iter()
        .map(|repository| repository.name.to_ascii_lowercase())
        .collect::<BTreeSet<_>>();
    expected
        .iter()
        .filter(|name| !existing.contains(&name.to_ascii_lowercase()))
        .cloned()
        .collect()
}

pub(super) fn canonicalize_selection(
    selected: &[String],
    expected: &BTreeSet<String>,
) -> Result<Vec<String>, RuntimeError> {
    let by_lowercase = expected
        .iter()
        .map(|name| (name.to_ascii_lowercase(), name.clone()))
        .collect::<BTreeMap<_, _>>();
    let mut canonical = Vec::new();
    for name in selected {
        let Some(value) = by_lowercase.get(&name.to_ascii_lowercase()) else {
            return Err(RuntimeError::Usage(
                "selected repository is not part of the canonical organization repository set"
                    .to_owned(),
            ));
        };
        canonical.push(value.clone());
    }
    canonical.sort();
    canonical.dedup();
    Ok(canonical)
}

pub(super) fn ensure_complete_inventory(
    repositories: &[GitHubRepository],
    scope: &OrgRepositoryScope,
) -> Result<(), RuntimeError> {
    if repositories.len() < scope.repo_limit {
        return Ok(());
    }
    Err(RuntimeError::Usage(format!(
        "repository inventory reached --repo-limit={}; increase the limit before relying on a complete organization inventory",
        scope.repo_limit
    )))
}

pub(super) fn validate_repository_name(name: &str) -> Result<(), RuntimeError> {
    let valid = !name.is_empty()
        && name.len() <= 100
        && name != "."
        && name != ".."
        && name
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || ".-_".contains(character));
    if valid {
        Ok(())
    } else {
        Err(RuntimeError::Usage(
            "invalid GitHub repository name".to_owned(),
        ))
    }
}

pub(crate) fn validate_org_login(value: &str) -> Result<String, RuntimeError> {
    let value = value.trim();
    let valid = !value.is_empty()
        && value.len() <= 100
        && value
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || character == '-');
    if valid {
        Ok(value.to_owned())
    } else {
        Err(RuntimeError::Usage(
            "invalid GitHub organization name".to_owned(),
        ))
    }
}

pub(super) fn validate_repository_scope(scope: &OrgRepositoryScope) -> Result<(), RuntimeError> {
    validate_org_login(&scope.owner)?;
    let validation_prefix = scope.family_prefix.as_deref().or_else(|| {
        (!scope.family_members.is_empty()).then_some(scope.owner.as_str())
    });
    for name in expected_repository_names(
        &scope.expected_repositories,
        validation_prefix,
        &scope.family_members,
    ) {
        validate_repository_name(&name)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        FamilyPrefixSource, canonicalize_selection, expected_repository_names,
        missing_repository_names, normalize_repository_arguments, repository_family_prefix,
        resolve_family_prefix, validate_org_login, validate_repository_name,
    };
    use crate::github::GitHubRepository;
    use crate::org::OrgRepositoryScope;

    fn repository(name: &str) -> GitHubRepository {
        GitHubRepository {
            name: name.to_owned(),
            name_with_owner: format!("example/{name}"),
            is_archived: false,
            is_fork: false,
            is_private: true,
            url: format!("https://github.com/example/{name}"),
        }
    }

    fn scope(owner: &str, prefix: Option<&str>, members: &[&str]) -> OrgRepositoryScope {
        OrgRepositoryScope {
            owner: owner.to_owned(),
            repo_limit: 100,
            expected_repositories: vec![".github".to_owned()],
            family_prefix: prefix.map(ToOwned::to_owned),
            family_members: members.iter().map(|member| (*member).to_owned()).collect(),
        }
    }

    #[test]
    fn expected_names_merge_exact_and_family_repositories() {
        let expected = expected_repository_names(
            &[".github".to_owned()],
            Some("example"),
            &["interfaces".to_owned(), "docs".to_owned()],
        );
        assert_eq!(
            expected.into_iter().collect::<Vec<_>>(),
            vec![
                ".github".to_owned(),
                "example-docs".to_owned(),
                "example-interfaces".to_owned(),
            ]
        );
    }

    #[test]
    fn missing_names_compare_case_insensitively() {
        let repositories = vec![GitHubRepository {
            name: ".GitHub".to_owned(),
            name_with_owner: "example/.GitHub".to_owned(),
            is_archived: false,
            is_fork: false,
            is_private: true,
            url: "https://github.com/example/.GitHub".to_owned(),
        }];
        let expected = expected_repository_names(
            &[".github".to_owned()],
            Some("example"),
            &["docs".to_owned()],
        );
        assert_eq!(
            missing_repository_names(&repositories, &expected),
            vec!["example-docs".to_owned()]
        );
    }

    #[test]
    fn shell_and_json_repository_arguments_are_normalized() {
        let values = vec![
            "beta".to_owned(),
            "\"alpha\"".to_owned(),
            "[\"gamma\",\"alpha\"]".to_owned(),
            "delta,epsilon".to_owned(),
        ];
        assert_eq!(
            normalize_repository_arguments(&values).expect("normalize"),
            vec![
                "alpha".to_owned(),
                "beta".to_owned(),
                "delta".to_owned(),
                "epsilon".to_owned(),
                "gamma".to_owned(),
            ]
        );
    }

    #[test]
    fn selected_names_must_be_canonical() {
        let scope = OrgRepositoryScope {
            owner: "example".to_owned(),
            repo_limit: 100,
            expected_repositories: vec![".github".to_owned()],
            family_prefix: Some("example".to_owned()),
            family_members: vec!["docs".to_owned()],
        };
        let expected = expected_repository_names(
            &scope.expected_repositories,
            scope.family_prefix.as_deref(),
            &scope.family_members,
        );
        assert_eq!(
            canonicalize_selection(&["EXAMPLE-DOCS".to_owned()], &expected)
                .expect("canonical selection"),
            vec!["example-docs".to_owned()]
        );
        assert!(canonicalize_selection(&["other".to_owned()], &expected).is_err());
    }

    #[test]
    fn explicit_prefix_always_wins_over_statistical_evidence() {
        let repositories = [
            "auto-docs",
            "auto-interfaces",
            "auto-clients",
            "auto-sync",
            "auto-admin-mcp-server.rs",
            "misc-one",
            "misc-two",
            "misc-three",
        ]
        .into_iter()
        .map(repository)
        .collect::<Vec<_>>();
        let resolved = resolve_family_prefix(
            &repositories,
            &scope(
                "example-org",
                Some("manual"),
                &["docs", "interfaces", "clients", "sync", "admin-mcp-server.rs"],
            ),
        );
        assert_eq!(resolved.prefix.as_deref(), Some("manual"));
        assert_eq!(resolved.source, FamilyPrefixSource::Explicit);
    }

    #[test]
    fn statistical_prefix_requires_more_than_seven_repositories() {
        let repositories = [
            "auto-docs",
            "auto-interfaces",
            "auto-clients",
            "auto-sync",
            "auto-admin-mcp-server.rs",
            "misc-one",
            "misc-two",
        ]
        .into_iter()
        .map(repository)
        .collect::<Vec<_>>();
        let resolved = resolve_family_prefix(
            &repositories,
            &scope(
                "example-org",
                None,
                &["docs", "interfaces", "clients", "sync", "admin-mcp-server.rs"],
            ),
        );
        assert_eq!(resolved.prefix.as_deref(), Some("example-org"));
        assert_eq!(resolved.source, FamilyPrefixSource::OwnerFallback);
    }

    #[test]
    fn statistical_prefix_accepts_five_of_eight_repositories() {
        let repositories = [
            "hhaus-docs",
            "hhaus-interfaces",
            "hhaus-clients",
            "hhaus-sync",
            "hhaus-admin-mcp-server.rs",
            "misc-one",
            "misc-two",
            "misc-three",
        ]
        .into_iter()
        .map(repository)
        .collect::<Vec<_>>();
        let resolved = resolve_family_prefix(
            &repositories,
            &scope(
                "hhaus-org",
                None,
                &["docs", "interfaces", "clients", "sync", "admin-mcp-server.rs"],
            ),
        );
        assert_eq!(resolved.prefix.as_deref(), Some("hhaus"));
        assert_eq!(resolved.source, FamilyPrefixSource::Statistical);
    }

    #[test]
    fn statistical_prefix_rejects_four_of_eight_repositories() {
        let repositories = [
            "hhaus-docs",
            "hhaus-interfaces",
            "hhaus-clients",
            "hhaus-sync",
            "misc-one",
            "misc-two",
            "misc-three",
            "misc-four",
        ]
        .into_iter()
        .map(repository)
        .collect::<Vec<_>>();
        let resolved = resolve_family_prefix(
            &repositories,
            &scope("hhaus-org", None, &["docs", "interfaces", "clients", "sync"]),
        );
        assert_eq!(resolved.prefix.as_deref(), Some("hhaus-org"));
        assert_eq!(resolved.source, FamilyPrefixSource::OwnerFallback);
    }

    #[test]
    fn statistical_prefix_rejects_exactly_four_sevenths() {
        let mut names = vec![
            "hhaus-docs",
            "hhaus-interfaces",
            "hhaus-clients",
            "hhaus-sync",
            "hhaus-cli",
            "hhaus-infra",
            "hhaus-lib-core",
            "hhaus-admin-mcp-server.rs",
        ];
        names.extend([
            "misc-one",
            "misc-two",
            "misc-three",
            "misc-four",
            "misc-five",
            "misc-six",
        ]);
        let repositories = names.into_iter().map(repository).collect::<Vec<_>>();
        let resolved = resolve_family_prefix(
            &repositories,
            &scope(
                "hhaus-org",
                None,
                &[
                    "docs",
                    "interfaces",
                    "clients",
                    "sync",
                    "cli",
                    "infra",
                    "lib-core",
                    "admin-mcp-server.rs",
                ],
            ),
        );
        assert_eq!(resolved.prefix.as_deref(), Some("hhaus-org"));
        assert_eq!(resolved.source, FamilyPrefixSource::OwnerFallback);
    }

    #[test]
    fn longest_known_suffix_preserves_hyphenated_prefixes() {
        assert_eq!(
            repository_family_prefix(
                "team-core-admin-mcp-server.rs",
                &["mcp-server.rs".to_owned(), "admin-mcp-server.rs".to_owned()]
            )
            .as_deref(),
            Some("team-core")
        );
        assert_eq!(
            repository_family_prefix(
                "team-core-admin-api-server.rs",
                &["api-server.rs".to_owned(), "admin-api-server.rs".to_owned()]
            )
            .as_deref(),
            Some("team-core")
        );
    }

    #[test]
    fn disabled_family_has_no_effective_prefix() {
        let resolved = resolve_family_prefix(&[], &scope("example-org", Some("manual"), &[]));
        assert_eq!(resolved.prefix, None);
        assert_eq!(resolved.source, FamilyPrefixSource::Disabled);
    }

    #[test]
    fn incomplete_inventory_never_drives_statistical_inference() {
        let repositories = [
            "hhaus-docs",
            "hhaus-interfaces",
            "hhaus-clients",
            "hhaus-sync",
            "hhaus-admin-mcp-server.rs",
            "misc-one",
            "misc-two",
            "misc-three",
        ]
        .into_iter()
        .map(repository)
        .collect::<Vec<_>>();
        let mut scope = scope(
            "hhaus-org",
            None,
            &["docs", "interfaces", "clients", "sync", "admin-mcp-server.rs"],
        );
        scope.repo_limit = repositories.len();
        let resolved = resolve_family_prefix(&repositories, &scope);
        assert_eq!(resolved.prefix.as_deref(), Some("hhaus-org"));
        assert_eq!(resolved.source, FamilyPrefixSource::OwnerFallback);
    }

    #[test]
    fn invalid_name_errors_do_not_reflect_supplied_values() {
        let repository_secret = "ghp_secret/value";
        let repository_error = validate_repository_name(repository_secret)
            .expect_err("invalid repository name")
            .to_string();
        assert!(!repository_error.contains(repository_secret));

        let organization_secret = "ghp-secret/value";
        let organization_error = validate_org_login(organization_secret)
            .expect_err("invalid organization name")
            .to_string();
        assert!(!organization_error.contains(organization_secret));

        let expected = expected_repository_names(&[".github".to_owned()], None, &[]);
        let selector_secret = "ghp_secret_value";
        let selector_error = canonicalize_selection(&[selector_secret.to_owned()], &expected)
            .expect_err("noncanonical selector")
            .to_string();
        assert!(!selector_error.contains(selector_secret));
    }
}
