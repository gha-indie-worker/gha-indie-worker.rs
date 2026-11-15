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


if __name__ == "__main__":
    unittest.main()
