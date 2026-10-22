#!/usr/bin/env python3
"""Keep the opt-in demo cleanup scoped and ordered."""

import unittest
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


if __name__ == "__main__":
    unittest.main()
