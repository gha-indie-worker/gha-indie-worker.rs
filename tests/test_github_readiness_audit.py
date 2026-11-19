from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "github_readiness_audit.py"
SPEC = importlib.util.spec_from_file_location("github_readiness_audit", MODULE_PATH)
assert SPEC and SPEC.loader
audit = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = audit
SPEC.loader.exec_module(audit)


class FakeRunner:
    def __init__(self, responses: dict[tuple[str, ...], object]):
        self.responses = responses
        self.calls: list[tuple[str, ...]] = []

    def run(self, *args: str, input_text: str | None = None) -> str:
        key = tuple(args)
        self.calls.append(key)
        value = self.responses.get(key)
        if isinstance(value, Exception):
            raise value
        if value is None:
            raise AssertionError(f"unexpected command: {key}")
        if isinstance(value, str):
            return value
        return json.dumps(value)


class GitHubReadinessAuditTests(unittest.TestCase):
    def test_ready_report_requires_auth_org_repo_and_project_writes(self) -> None:
        graphql_key = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=example-org"
        )
        runner = FakeRunner(
            {
                ("gh", "auth", "status", "--hostname", "github.com"): "",
                ("gh", "api", "user"): {"login": "alex"},
                graphql_key: {
                    "data": {
                        "organization": {
                            "login": "example-org",
                            "viewerCanCreateRepositories": True,
                            "projectsV2": {
                                "totalCount": 1,
                                "nodes": [{"id": "PVT_1", "title": "Delivery", "viewerCanUpdate": True}],
                            },
                        }
                    }
                },
                ("gh", "api", "repos/example-org/service"): {
                    "full_name": "example-org/service",
                    "permissions": {"push": True, "admin": False},
                },
            }
        )

        report = audit.build_report(runner, ["example-org"], ["example-org/service"])

        self.assertTrue(report.authenticated)
        self.assertEqual(report.login, "alex")
        self.assertTrue(report.ready_for_unattended_writes)
        self.assertEqual(report.blockers, [])
        self.assertTrue(report.repositories[0].pull_request_write)
        self.assertTrue(report.organizations[0].project_update)

    def test_missing_repo_create_and_project_write_fail_closed(self) -> None:
        graphql_key = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=locked-org"
        )
        runner = FakeRunner(
            {
                ("gh", "auth", "status", "--hostname", "github.com"): "",
                ("gh", "api", "user"): {"login": "alex"},
                graphql_key: {
                    "data": {
                        "organization": {
                            "login": "locked-org",
                            "viewerCanCreateRepositories": False,
                            "projectsV2": {
                                "totalCount": 1,
                                "nodes": [{"id": "PVT_2", "title": "Roadmap", "viewerCanUpdate": False}],
                            },
                        }
                    }
                },
            }
        )

        report = audit.build_report(runner, ["locked-org"], [])

        self.assertFalse(report.ready_for_unattended_writes)
        self.assertIn("locked-org: repository creation is not permitted.", report.blockers)
        self.assertIn(
            "locked-org: at least one sampled GitHub Project is not writable.",
            report.blockers,
        )

    def test_no_projects_is_explicitly_unproven(self) -> None:
        graphql_key = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=new-org"
        )
        runner = FakeRunner(
            {
                ("gh", "auth", "status", "--hostname", "github.com"): "",
                ("gh", "api", "user"): {"login": "alex"},
                graphql_key: {
                    "data": {
                        "organization": {
                            "login": "new-org",
                            "viewerCanCreateRepositories": True,
                            "projectsV2": {"totalCount": 0, "nodes": []},
                        }
                    }
                },
            }
        )

        report = audit.build_report(runner, ["new-org"], [])

        self.assertIsNone(report.organizations[0].project_update)
        self.assertIn("new-org: GitHub Projects write access is unproven.", report.blockers)

    def test_missing_or_mismatched_repository_identity_cannot_grant_writes(self) -> None:
        auth = ("gh", "auth", "status", "--hostname", "github.com")
        user = ("gh", "api", "user")
        request = ("gh", "api", "repos/Example-Org/service")
        for observed in (None, "different-org/service", "Example-Org/other"):
            response = {"permissions": {"push": True, "admin": True}}
            if observed is not None:
                response["full_name"] = observed
            runner = FakeRunner({auth: "", user: {"login": "alex"}, request: response})
            report = audit.build_report(runner, [], ["Example-Org/service"])
            self.assertFalse(report.ready_for_unattended_writes)
            self.assertFalse(report.repositories[0].readable)
            self.assertFalse(report.repositories[0].contents_write)
            self.assertFalse(report.repositories[0].merge_permission)
            self.assertTrue(any("repository identity" in b for b in report.blockers))

    def test_repository_identity_matching_is_case_insensitive(self) -> None:
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            ("gh", "api", "repos/example-org/SERVICE"): {
                "full_name": "Example-Org/service", "permissions": {"push": True},
            },
        })
        report = audit.build_report(runner, [], ["example-org/SERVICE"])
        self.assertTrue(report.ready_for_unattended_writes)

    def test_secondary_limit_stops_org_and_repo_queries_immediately(self) -> None:
        auth = ("gh", "auth", "status", "--hostname", "github.com")
        user = ("gh", "api", "user")
        limited = ("gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=limited-org")
        runner = FakeRunner({
            auth: "",
            user: {"login": "alex"},
            limited: audit.AuditError("GitHub API error 403: You have exceeded a secondary rate limit."),
        })
        report = audit.build_report(runner, ["limited-org", "never-org"], ["org/never"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertTrue(any("secondary rate limit" in b for b in report.blockers))
        self.assertEqual(runner.calls, [auth, user, limited])

    def test_http_429_stops_remaining_repositories_immediately(self) -> None:
        auth = ("gh", "auth", "status", "--hostname", "github.com")
        user = ("gh", "api", "user")
        limited = ("gh", "api", "repos/org/limited")
        runner = FakeRunner({
            auth: "",
            user: {"login": "alex"},
            limited: audit.AuditError("GitHub API error 429: Too Many Requests"),
        })
        report = audit.build_report(runner, [], ["org/limited", "org/never"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(runner.calls, [auth, user, limited])

    def test_generic_permission_403_is_not_api_throttling(self) -> None:
        self.assertFalse(audit._is_rate_limit_error("HTTP 403: Resource not accessible by integration"))
        self.assertTrue(audit._is_rate_limit_error("HTTP 429: Too Many Requests"))
        self.assertTrue(audit._is_rate_limit_error("GitHub API error 403: secondary rate limit"))
        self.assertTrue(audit._is_rate_limit_error("GitHub API rate limit exceeded"))
        auth = ("gh", "auth", "status", "--hostname", "github.com")
        user = ("gh", "api", "user")
        forbidden = ("gh", "api", "repos/org/forbidden")
        following = ("gh", "api", "repos/org/allowed")
        runner = FakeRunner({
            auth: "",
            user: {"login": "alex"},
            forbidden: audit.AuditError("HTTP 403: Resource not accessible by integration"),
            following: {"full_name": "org/allowed", "permissions": {"push": True}},
        })
        report = audit.build_report(runner, [], ["org/forbidden", "org/allowed"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(runner.calls, [auth, user, forbidden, following])


    def test_credential_bearing_errors_are_redacted(self) -> None:
        self.assertEqual(
            audit._safe_error("request failed for github_pat_secretvalue"),
            "credential-bearing error redacted",
        )


if __name__ == "__main__":
    unittest.main()
