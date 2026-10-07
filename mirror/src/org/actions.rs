#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct OrgAction {
    name: &'static str,
    pub(super) command: String,
    pub(super) description: &'static str,
    pub(super) mutates: bool,
}

fn prefix_argument(family_prefix: Option<&str>) -> String {
    family_prefix
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|prefix| format!(" --prefix {prefix}"))
        .unwrap_or_default()
}

pub(super) fn available_actions(owner: &str, family_prefix: Option<&str>) -> Vec<OrgAction> {
    let prefix_argument = prefix_argument(family_prefix);
    let list_missing = format!("oresc org --name {owner}{prefix_argument} list-missing-repos");
    let create_all = format!(
        "oresc org --name {owner}{prefix_argument} create-missing-repos --all"
    );
    let filter_missing = format!("{list_missing} $({list_missing})");
    let create_selected = format!(
        "oresc org --name {owner}{prefix_argument} create-missing-repos $({list_missing})"
    );
    let set_visibility = format!(
        "oresc org --name {owner} set-repo-visibility --all --visibility private --dry-run"
    );
    let clone_all = format!("oresc org --name {owner} clone --all");
    let pull_all = format!("oresc org --name {owner} pull --all");
    let sync_all = format!("oresc org --name {owner} sync --all");
    let audit = match family_prefix
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        Some(prefix) => format!("oresc audit org --org {owner} --prefix {prefix}"),
        None => format!("oresc audit org --org {owner}"),
    };

    vec![
        OrgAction {
            name: "list-missing-repos",
            command: list_missing,
            description: "list missing canonical repositories as shell-composable names",
            mutates: false,
        },
        OrgAction {
            name: "create-missing-repos",
            command: create_all,
            description: "create every currently missing canonical repository",
            mutates: true,
        },
        OrgAction {
            name: "filter-missing-repos",
            command: filter_missing,
            description: "pass repository-name selectors back into the missing-repository command",
            mutates: false,
        },
        OrgAction {
            name: "create-selected-missing-repos",
            command: create_selected,
            description: "pipe the missing-repository selector directly into repository creation",
            mutates: true,
        },
        OrgAction {
            name: "set-repo-visibility",
            command: set_visibility,
            description: "preview visibility changes for existing repositories before applying them",
            mutates: true,
        },
        OrgAction {
            name: "clone",
            command: clone_all,
            description: "clone all visible repositories into the confirmed local organization workspace",
            mutates: true,
        },
        OrgAction {
            name: "pull",
            command: pull_all,
            description: "fast-forward pull verified existing local organization repositories",
            mutates: true,
        },
        OrgAction {
            name: "sync",
            command: sync_all,
            description: "clone missing and fast-forward pull existing local organization repositories",
            mutates: true,
        },
        OrgAction {
            name: "audit-org",
            command: audit,
            description: "run the deeper topology and repository-layout audit",
            mutates: false,
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::{OrgAction, available_actions};

    fn action<'a>(actions: &'a [OrgAction], name: &str) -> &'a OrgAction {
        actions
            .iter()
            .find(|action| action.name == name)
            .expect("action must exist")
    }

    #[test]
    fn resolved_prefix_is_preserved_across_list_and_create_actions() {
        let actions = available_actions("hhaus-org", Some("hhaus"));

        assert_eq!(
            action(&actions, "list-missing-repos").command,
            "oresc org --name hhaus-org --prefix hhaus list-missing-repos"
        );
        assert_eq!(
            action(&actions, "create-missing-repos").command,
            "oresc org --name hhaus-org --prefix hhaus create-missing-repos --all"
        );
        assert_eq!(
            action(&actions, "filter-missing-repos").command,
            "oresc org --name hhaus-org --prefix hhaus list-missing-repos $(oresc org --name hhaus-org --prefix hhaus list-missing-repos)"
        );
        assert_eq!(
            action(&actions, "create-selected-missing-repos").command,
            "oresc org --name hhaus-org --prefix hhaus create-missing-repos $(oresc org --name hhaus-org --prefix hhaus list-missing-repos)"
        );
        assert_eq!(
            action(&actions, "set-repo-visibility").command,
            "oresc org --name hhaus-org set-repo-visibility --all --visibility private --dry-run"
        );
        assert_eq!(
            action(&actions, "clone").command,
            "oresc org --name hhaus-org clone --all"
        );
        assert_eq!(
            action(&actions, "pull").command,
            "oresc org --name hhaus-org pull --all"
        );
        assert_eq!(
            action(&actions, "sync").command,
            "oresc org --name hhaus-org sync --all"
        );
        assert_eq!(
            action(&actions, "audit-org").command,
            "oresc audit org --org hhaus-org --prefix hhaus"
        );
    }

    #[test]
    fn disabled_family_actions_do_not_invent_a_prefix() {
        let actions = available_actions("example-org", None);

        assert_eq!(
            action(&actions, "list-missing-repos").command,
            "oresc org --name example-org list-missing-repos"
        );
        assert_eq!(
            action(&actions, "create-missing-repos").command,
            "oresc org --name example-org create-missing-repos --all"
        );
        assert_eq!(
            action(&actions, "audit-org").command,
            "oresc audit org --org example-org"
        );
    }
}
