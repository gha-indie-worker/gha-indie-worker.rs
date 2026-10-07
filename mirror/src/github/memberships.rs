use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::{GhCli, input};
use crate::error::RuntimeError;

/// Active organization membership visible to the authenticated GitHub account.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct OrganizationMembership {
    /// Validated canonical organization login.
    pub login: String,
    /// GitHub membership role: admin or member.
    pub role: String,
}

#[derive(Deserialize)]
struct MembershipResponse {
    state: String,
    role: String,
    organization: MembershipOrganization,
}

#[derive(Deserialize)]
struct MembershipOrganization {
    login: String,
}

impl GhCli {
    /// Read the effective GitHub.com identity, without exposing credential values.
    pub async fn authenticated_login(&self) -> Result<String, RuntimeError> {
        #[derive(Deserialize)]
        struct User {
            login: String,
        }
        let value = self.membership_get("user".to_owned()).await?;
        let user: User = serde_json::from_str(&value)?;
        input::owner(&user.login).map_err(|_| invalid_membership())
    }

    /// Read one active organization membership for the authenticated account.
    pub async fn organization_membership(
        &self,
        owner: &str,
    ) -> Result<OrganizationMembership, RuntimeError> {
        let owner = input::owner(owner)?;
        let text = self
            .membership_get(format!("user/memberships/orgs/{owner}"))
            .await?;
        let row: MembershipResponse = serde_json::from_str(&text)?;
        if row.state != "active"
            || !matches!(row.role.as_str(), "admin" | "member")
            || !row.organization.login.eq_ignore_ascii_case(&owner)
        {
            return Err(invalid_membership());
        }
        let login = input::owner(&row.organization.login).map_err(|_| invalid_membership())?;
        Ok(OrganizationMembership {
            login,
            role: row.role,
        })
    }

    /// Enumerate all active memberships, including private organizations.
    /// Each page uses the existing bounded, authenticated, rate-limited gateway.
    /// A failed or oversized discovery never becomes an empty successful list.
    pub async fn organization_memberships(
        &self,
    ) -> Result<Vec<OrganizationMembership>, RuntimeError> {
        let mut memberships = BTreeMap::new();
        for page in 1..=100 {
            let text = self
                .membership_get(format!(
                    "user/memberships/orgs?state=active&per_page=100&page={page}"
                ))
                .await?;
            let rows: Vec<MembershipResponse> = serde_json::from_str(&text)?;
            let count = rows.len();
            if count > 100 {
                return Err(invalid_membership());
            }

            let page_memberships = validated_page(rows)?;
            memberships = merge_memberships(memberships, page_memberships)?;
            if count < 100 {
                return Ok(memberships.into_values().collect());
            }
        }
        Err(RuntimeError::Invariant(
            "organization discovery exceeded 100 pages; refusing a partial inventory".to_owned(),
        ))
    }

    async fn membership_get(&self, endpoint: String) -> Result<String, RuntimeError> {
        let result = self
            .execute(vec![
                "api".to_owned(),
                endpoint,
                "--method".to_owned(),
                "GET".to_owned(),
                "--hostname".to_owned(),
                "github.com".to_owned(),
                "-H".to_owned(),
                "Accept: application/vnd.github+json".to_owned(),
                "-H".to_owned(),
                "X-GitHub-Api-Version: 2022-11-28".to_owned(),
            ])
            .await?;
        if !result.success {
            return Err(RuntimeError::Dependency {
                command: "gh organization discovery".to_owned(),
                exit_code: result.exit_code,
                message: "GitHub rejected discovery; verify the active account and organization membership read permissions".to_owned(),
            });
        }
        Ok(result.stdout)
    }
}

fn invalid_membership() -> RuntimeError {
    RuntimeError::Invariant(
        "GitHub returned an invalid or inconsistent organization membership".to_owned(),
    )
}

/// Validate one remote page into a brand-new canonical value.
///
/// The caller no longer lends a mutable accumulator to parsing/validation.
/// Every accepted row becomes a freshly constructed `OrganizationMembership`,
/// and pending memberships disappear at this boundary instead of leaking into
/// a shared object that later phases need to clean up. The iterator is generic
/// so callers can supply owned vectors or streaming adapters without reintroducing
/// a mutable output parameter.
fn validated_page<I>(rows: I) -> Result<BTreeMap<String, OrganizationMembership>, RuntimeError>
where
    I: IntoIterator<Item = MembershipResponse>,
{
    rows.into_iter().try_fold(BTreeMap::new(), |page, row| {
        if !matches!(row.state.as_str(), "active" | "pending")
            || !matches!(row.role.as_str(), "admin" | "member")
        {
            return Err(invalid_membership());
        }
        if row.state != "active" {
            return Ok(page);
        }

        let login = input::owner(&row.organization.login).map_err(|_| invalid_membership())?;
        let key = login.to_ascii_lowercase();
        insert_membership(
            page,
            key,
            OrganizationMembership {
                login,
                role: row.role,
            },
        )
    })
}

/// Combine two owned inventories and return the next inventory value.
///
/// We deliberately consume the maps instead of cloning the full BTreeMap on
/// every page. Organization discovery is cold-path audit work, but it can still
/// reach 10,000 rows; ownership gives callers an immutable/value-returning API
/// without turning a linear scan into allocation-heavy O(n²) copying.
fn merge_memberships(
    memberships: BTreeMap<String, OrganizationMembership>,
    page: BTreeMap<String, OrganizationMembership>,
) -> Result<BTreeMap<String, OrganizationMembership>, RuntimeError> {
    page.into_iter()
        .try_fold(memberships, |current, (key, membership)| {
            insert_membership(current, key, membership)
        })
}

fn insert_membership(
    mut memberships: BTreeMap<String, OrganizationMembership>,
    key: String,
    membership: OrganizationMembership,
) -> Result<BTreeMap<String, OrganizationMembership>, RuntimeError> {
    if let Some(previous) = memberships.get(&key) {
        if previous.role != membership.role {
            return Err(invalid_membership());
        }
        return Ok(memberships);
    }
    memberships.insert(key, membership);
    Ok(memberships)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(value: &str) -> Vec<MembershipResponse> {
        serde_json::from_str(value).unwrap()
    }

    #[test]
    fn pages_deduplicate_case_insensitively_and_exclude_pending() {
        let first = validated_page(rows(
            r#"[
            {"state":"active","role":"admin","organization":{"login":"Beta"}},
            {"state":"pending","role":"admin","organization":{"login":"pending"}}
        ]"#,
        ))
        .unwrap();
        let second = validated_page(rows(
            r#"[
            {"state":"active","role":"admin","organization":{"login":"BETA"}},
            {"state":"active","role":"member","organization":{"login":"alpha"}}
        ]"#,
        ))
        .unwrap();
        let memberships = merge_memberships(first, second).unwrap();

        assert_eq!(
            memberships.keys().map(String::as_str).collect::<Vec<_>>(),
            vec!["alpha", "beta"]
        );
        assert_eq!(memberships["beta"].login, "Beta");
    }

    #[test]
    fn page_validation_returns_new_values_without_mutable_out_parameters() {
        let memberships = validated_page(rows(
            r#"[{"state":"active","role":"member","organization":{"login":"alpha"}}]"#,
        ))
        .unwrap();
        assert_eq!(memberships.len(), 1);
        assert_eq!(memberships["alpha"].login, "alpha");
        assert_eq!(memberships["alpha"].role, "member");
    }

    #[test]
    fn page_validation_accepts_generic_owned_iterators() {
        let memberships = validated_page(rows(
            r#"[{"state":"active","role":"member","organization":{"login":"alpha"}}]"#,
        ))
        .unwrap();
        assert_eq!(memberships["alpha"].role, "member");
    }

    #[test]
    fn malformed_or_conflicting_memberships_fail_closed() {
        for value in [
            r#"[{"state":"active","role":"admin","organization":{"login":"../users"}}]"#,
            r#"[{"state":"active","role":"owner","organization":{"login":"alpha"}}]"#,
            r#"[{"state":"unknown","role":"admin","organization":{"login":"alpha"}}]"#,
            r#"[{"state":"active","role":"member","organization":{"login":"alpha"}},{"state":"active","role":"admin","organization":{"login":"ALPHA"}}]"#,
        ] {
            assert!(validated_page(rows(value)).is_err());
        }

        let first = validated_page(rows(
            r#"[{"state":"active","role":"member","organization":{"login":"alpha"}}]"#,
        ))
        .unwrap();
        let conflicting = validated_page(rows(
            r#"[{"state":"active","role":"admin","organization":{"login":"ALPHA"}}]"#,
        ))
        .unwrap();
        assert!(merge_memberships(first, conflicting).is_err());
    }
}
