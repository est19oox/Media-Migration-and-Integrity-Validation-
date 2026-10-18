#!/usr/bin/env python3
"""Regression checks for the GitHub Actions AWS OIDC trust subjects."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
GITHUB_TF = ROOT / "infrastructure" / "bootstrap" / "github.tf"
EXPECTED_SUBJECT = (
    "repo:est19oox/Media-Migration-and-Integrity-Validation-:ref:refs/heads/*"
)


class GitHubOidcTrustTest(unittest.TestCase):
    def test_both_deployment_roles_trust_the_real_github_oidc_subject(self):
        source = GITHUB_TF.read_text(encoding="utf-8")

        self.assertEqual(source.count(EXPECTED_SUBJECT), 2)
        self.assertNotIn("est19oox@", source)
        self.assertNotIn("Media-Migration-and-Integrity-Validation-@", source)


if __name__ == "__main__":
    unittest.main()
