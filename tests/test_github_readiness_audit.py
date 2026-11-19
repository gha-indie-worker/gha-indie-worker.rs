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

    def test_credential_bearing_errors_are_redacted(self) -> None:
        self.assertEqual(
            audit._safe_error("request failed for github_pat_secretvalue"),
            "credential-bearing error redacted",
        )


    def test_generic_credentials_and_url_userinfo_are_redacted(self) -> None:
        examples = (
            "Authorization: Bearer opaque-value",
            "basic Zm9vOmJhcg==",
            "GitHub token=abcdef",
            "request https://alice:password@example.com/resource failed",
            "x-api-key: secret-data",
            "client_secret=shhh",
        )
        for raw in examples:
            with self.subTest(raw=raw):
                self.assertEqual(audit._safe_error(raw), "credential-bearing error redacted")
        self.assertEqual(
            audit._safe_error("HTTP 403: Resource not accessible by integration"),
            "HTTP 403: Resource not accessible by integration",
        )

    def test_subprocess_timeout_and_spawn_errors_fail_closed(self) -> None:
        from unittest.mock import patch
        with patch.object(
            audit.subprocess, "run",
            side_effect=audit.subprocess.TimeoutExpired("gh", 30),
        ):
            with self.assertRaisesRegex(audit.AuditError, "timed out"):
                audit.SubprocessRunner().run("gh", "api", "repos/example/repo")
        with patch.object(audit.subprocess, "run", side_effect=OSError("secret=untrusted")):
            with self.assertRaisesRegex(audit.AuditError, "could not be executed") as caught:
                audit.SubprocessRunner().run("gh", "api", "repos/example/repo")
            self.assertNotIn("untrusted", str(caught.exception))

    def test_rate_limit_detector_does_not_confuse_permissions_403(self) -> None:
        self.assertFalse(audit._is_rate_limit_error(
            "HTTP 403: Resource not accessible by integration"
        ))
        self.assertTrue(audit._is_rate_limit_error("gh: HTTP 429: Too Many Requests"))
        self.assertTrue(audit._is_rate_limit_error(
            "HTTP 403: You have exceeded a secondary rate limit"
        ))
        self.assertTrue(audit._is_rate_limit_error(
            "HTTP 403: X-RateLimit-Remaining: 0"
        ))

    def test_subprocess_runner_classifies_429_as_rate_limited(self) -> None:
        from unittest.mock import patch
        outcome = audit.subprocess.CompletedProcess(
            args=["gh"], returncode=1, stdout="", stderr="gh: HTTP 429"
        )
        with patch.object(audit.subprocess, "run", return_value=outcome):
            with self.assertRaises(audit.RateLimitError):
                audit.SubprocessRunner().run("gh", "api", "user")

    def test_missing_or_wrong_repo_identity_fails_closed(self) -> None:
        for name in (None, "example-org/not-the-service"):
            with self.subTest(name=name):
                payload = {"permissions": {"push": True, "admin": True}}
                if name is not None:
                    payload["full_name"] = name
                runner = FakeRunner({
                    ("gh", "auth", "status", "--hostname", "github.com"): "",
                    ("gh", "api", "user"): {"login": "alex"},
                    ("gh", "api", "repos/example-org/service"): payload,
                })
                report = audit.build_report(runner, [], ["example-org/service"])
                self.assertFalse(report.ready_for_unattended_writes)
                self.assertFalse(report.repositories[0].readable)
                self.assertFalse(report.repositories[0].merge_permission)
                self.assertIn(
                    "example-org/service: repository identity is missing or mismatched.",
                    report.blockers,
                )

    def test_repo_rate_limit_stops_before_next_repository(self) -> None:
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            ("gh", "api", "repos/org/first"): audit.RateLimitError("HTTP 429"),
        })
        report = audit.build_report(runner, [], ["org/first", "org/second"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(
            runner.calls,
            [
                ("gh", "auth", "status", "--hostname", "github.com"),
                ("gh", "api", "user"),
                ("gh", "api", "repos/org/first"),
            ],
        )
        self.assertTrue(any("stopped remaining scan" in x for x in report.blockers))

    def test_org_rate_limit_skips_remaining_orgs_and_repos(self) -> None:
        first_query = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org1"
        )
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            first_query: audit.RateLimitError("secondary rate limit"),
        })
        report = audit.build_report(runner, ["org1", "org2"], ["org/repo"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(runner.calls[-1], first_query)
        self.assertEqual(len(runner.calls), 3)

    def test_graphql_rate_limit_in_http_200_payload_stops_scan(self) -> None:
        first_query = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org1"
        )
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            first_query: {"errors": [{"message": "API rate limit exceeded"}]},
        })
        report = audit.build_report(runner, ["org1", "org2"], ["org/repo"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(len(runner.calls), 3)


    def test_subprocess_runner_scans_both_error_streams(self) -> None:
        from unittest.mock import patch
        outcome = audit.subprocess.CompletedProcess(
            args=["gh"], returncode=1,
            stderr="warning: cli extension message",
            stdout="HTTP 429: too many requests",
        )
        with patch.object(audit.subprocess, "run", return_value=outcome):
            with self.assertRaises(audit.RateLimitError):
                audit.SubprocessRunner().run("gh", "api", "repos/org/first")

    def test_graphql_rate_limit_type_stops_remaining_calls(self) -> None:
        key = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org1"
        )
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            key: {"errors": [{"type": "RATE_LIMIT", "message": "retry later"}]},
        })
        report = audit.build_report(runner, ["org1", "org2"], ["org/repo"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(runner.calls[-1], key)
        self.assertEqual(len(runner.calls), 3)

    def test_http_200_rate_limit_message_stops_remaining_calls(self) -> None:
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            ("gh", "api", "repos/org/first"): {
                "message": "API rate limit exceeded for example user"
            },
        })
        report = audit.build_report(runner, [], ["org/first", "org/second"])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(len(runner.calls), 3)
        self.assertTrue(any("stopped remaining scan" in x for x in report.blockers))

    def test_malformed_repository_responses_fail_closed_without_crash(self) -> None:
        for payload in ([42], {"full_name": "org/repo", "permissions": []}):
            with self.subTest(payload=payload):
                runner = FakeRunner({
                    ("gh", "auth", "status", "--hostname", "github.com"): "",
                    ("gh", "api", "user"): {"login": "alex"},
                    ("gh", "api", "repos/org/repo"): payload,
                })
                report = audit.build_report(runner, [], ["org/repo"])
                self.assertFalse(report.ready_for_unattended_writes)
                self.assertTrue(any("org/repo:" in x for x in report.blockers))

    def test_mismatched_organization_identity_fails_closed(self) -> None:
        key = (
            "gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org1"
        )
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            key: {"data": {"organization": {
                "login": "other-org",
                "viewerCanCreateRepositories": True,
                "projectsV2": {"nodes": [{"viewerCanUpdate": True}]},
            }}},
        })
        report = audit.build_report(runner, ["org1"], [])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertIn("org1: GitHub organization response identity mismatch", report.blockers)


    def test_string_and_numeric_permission_flags_are_not_writable(self) -> None:
        for permission in ("false", "true", "yes", 1, 0, []):
            with self.subTest(permission=permission):
                runner = FakeRunner({
                    ("gh", "auth", "status", "--hostname", "github.com"): "",
                    ("gh", "api", "user"): {"login": "alex"},
                    ("gh", "api", "repos/org/repo"): {
                        "full_name": "org/repo",
                        "permissions": {"push": permission},
                    },
                })
                report = audit.build_report(runner, [], ["org/repo"])
                self.assertFalse(report.ready_for_unattended_writes)
                self.assertFalse(report.repositories[0].contents_write)
                self.assertFalse(report.repositories[0].merge_permission)

    def test_true_push_access_does_not_claim_merge_approval(self) -> None:
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            ("gh", "api", "repos/org/repo"): {
                "full_name": "org/repo", "permissions": {"push": True},
            },
        })
        report = audit.build_report(runner, [], ["org/repo"])
        self.assertTrue(report.ready_for_unattended_writes)
        self.assertTrue(report.repositories[0].contents_write)
        self.assertFalse(report.repositories[0].merge_permission)

    def test_string_organization_write_flags_are_not_accepted(self) -> None:
        key = ("gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org")
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            key: {"data": {"organization": {
                "login": "org",
                "viewerCanCreateRepositories": "true",
                "projectsV2": {"totalCount": 1, "nodes": [
                    {"id": "PVT_1", "viewerCanUpdate": "false"}
                ]},
            }}},
        })
        report = audit.build_report(runner, ["org"], [])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertFalse(report.organizations[0].repository_create)
        self.assertFalse(report.organizations[0].project_update)

    def test_unsampled_projects_fail_closed_instead_of_claiming_write(self) -> None:
        key = ("gh", "api", "graphql", "-f", f"query={audit.ORG_QUERY}", "-F", "login=org")
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": "alex"},
            key: {"data": {"organization": {
                "login": "org",
                "viewerCanCreateRepositories": True,
                "projectsV2": {"totalCount": 21, "nodes": [
                    {"id": f"PVT_{i}", "viewerCanUpdate": True} for i in range(20)
                ]},
            }}},
        })
        report = audit.build_report(runner, ["org"], [])
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertTrue(any("pagination required" in x for x in report.blockers))

    def test_invalid_authenticated_login_stops_capability_queries(self) -> None:
        runner = FakeRunner({
            ("gh", "auth", "status", "--hostname", "github.com"): "",
            ("gh", "api", "user"): {"login": {"untrusted": "not a username"}},
        })
        report = audit.build_report(runner, ["org"], ["org/repo"])
        self.assertFalse(report.authenticated)
        self.assertFalse(report.ready_for_unattended_writes)
        self.assertEqual(len(runner.calls), 2)
        self.assertIn("GitHub returned missing or invalid authenticated login.", report.blockers)


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

if __name__ == "__main__":
    unittest.main()
