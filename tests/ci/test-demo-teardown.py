#!/usr/bin/env python3
"""Keep the opt-in demo cleanup scoped and ordered."""

import unittest
import os
import subprocess
import tempfile
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]


class DemoTeardownTest(unittest.TestCase):
    def test_cleanup_requires_confirmation_and_disables_auto_deploy(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/teardown-demo.yml").read_text())
        steps = workflow["jobs"]["teardown"]["steps"]
        guard = next(step["run"] for step in steps if step.get("name") == "Check destructive-action guard")
        self.assertIn("DESTROY-MMIV-DEMO", guard)
        self.assertIn("MMIV_ECS_AUTO_DEPLOY", guard)
        self.assertIn("MMIV_ECS_AUTO_DEPLOY", workflow["jobs"]["teardown"]["env"])

    def test_ecs_is_destroyed_before_data_and_shared_resources_are_not_targeted(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/teardown-demo.yml").read_text())
        steps = workflow["jobs"]["teardown"]["steps"]
        names = [step.get("name", "") for step in steps]
        self.assertLess(names.index("Destroy ECS before managed data"),
                        names.index("Destroy disposable databases without final snapshots"))
        commands = "\n".join(step.get("run", "") for step in steps)
        self.assertIn("-var=teardown_mode=true", commands)
        self.assertNotIn("aws s3 rb", commands)
        self.assertNotIn("aws ecr delete-repository", commands)

    def test_database_snapshots_are_skipped_only_in_explicit_teardown_mode(self):
        source = (ROOT / "infrastructure/aws-managed-data/main.tf").read_text()
        self.assertIn('default     = false', source)
        self.assertEqual(source.count('skip_final_snapshot             = var.teardown_mode'), 2)

    def test_unapplied_ecs_state_does_not_block_partial_data_cleanup(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/teardown-demo.yml").read_text())
        steps = workflow["jobs"]["teardown"]["steps"]
        preparation = next(step["run"] for step in steps if step.get("name") == "Prepare both Terraform states and preview deletion")
        final_check = next(step["run"] for step in steps if step.get("name") == "Check both Terraform states are empty")
        helper = "scripts/cd/terraform-state-list-or-empty.sh"
        self.assertIn(helper + " infrastructure/ecs-fargate", preparation)
        self.assertIn(helper + " infrastructure/ecs-fargate", final_check)

        with tempfile.TemporaryDirectory() as directory:
            terraform = Path(directory) / "terraform"
            terraform.write_text(
                '#!/bin/sh\n'
                'case "$MOCK_RESULT" in\n'
                '  missing) echo "No state file was found!" >&2; exit 1;;\n'
                '  denied) echo "AccessDenied" >&2; exit 1;;\n'
                '  present) echo aws_security_group.example;;\n'
                'esac\n'
            )
            terraform.chmod(0o755)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            for mode, expected_code, expected_output in (
                ("missing", 0, ""),
                ("denied", 1, ""),
                ("present", 0, "aws_security_group.example\n"),
            ):
                with self.subTest(mode=mode):
                    result = subprocess.run(
                        ["bash", str(ROOT / helper), "infrastructure/ecs-fargate"],
                        env=dict(env, MOCK_RESULT=mode), capture_output=True, text=True, check=False,
                    )
                    self.assertEqual(result.returncode, expected_code)
                    self.assertEqual(result.stdout, expected_output)

    def test_docdb_cleanup_uses_supported_status_query(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/teardown-demo.yml").read_text())
        steps = workflow["jobs"]["teardown"]["steps"]
        protection = next(step["run"] for step in steps if step.get("name") == "Disable deletion protection on this demo's resources")
        self.assertNotIn("aws docdb wait db-cluster-available", protection)
        self.assertIn("aws docdb describe-db-clusters", protection)
        self.assertIn("DeletionProtection", protection)


if __name__ == "__main__":
    unittest.main()
