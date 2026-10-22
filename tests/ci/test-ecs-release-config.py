#!/usr/bin/env python3
"""Guard automated ECS releases against missing state and stale image revisions."""

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "ecs_release", ROOT / "scripts/cd/render-ecs-release-vars.py"
)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class EcsReleaseConfigTest(unittest.TestCase):
    def setUp(self):
        self.outputs = {name: {"value": name + "-value"} for name in (
            "oracle_jdbc_url", "oracle_master_secret_arn", "rabbit_host", "redis_host", "rabbit_port",
            "redis_port", "kms_key_arn", "rabbit_password_arn",
            "user_access_db_password_arn", "discovery_db_password_arn",
            "integrity_db_password_arn", "mongo_uri_arn", "redis_password_arn",
            "task_security_group_id", "jwt_private_key_secret_arn",
            "jwt_public_key_secret_arn",
        )}
        self.env = {
            "GITHUB_SHA": "a" * 40,
            "AWS_ACCOUNT_ID": "275069781906",
            "AWS_REGION": "ap-southeast-1",
            "MMIV_VPC_ID": "vpc-example",
            "MMIV_PRIMARY_PUBLIC_SUBNET_ID": "subnet-primary",
            "MMIV_SECONDARY_PUBLIC_SUBNET_ID": "subnet-secondary",
            "MMIV_BUCKET_NAME": "landing-example",
            "MMIV_JWT_ISSUER": "https://auth.example",
            "MMIV_JWT_KEY_ID": "key-1",
        }

    def test_renders_current_sha_and_managed_data_outputs(self):
        result = module.render(self.outputs, self.env)
        self.assertEqual(result["image_revision"], "a" * 40)
        self.assertEqual(result["secret_arns"]["mongo_uri"], "mongo_uri_arn-value")
        self.assertEqual(result["secondary_public_subnet_id"], "subnet-secondary")
        self.assertEqual(result["task_security_group_id"], "task_security_group_id-value")
        self.assertTrue(result["demo_single_az"])
        self.assertTrue(result["rabbit_tls"])

    def test_missing_managed_data_output_blocks_release(self):
        del self.outputs["rabbit_host"]
        with self.assertRaisesRegex(ValueError, "initialize it first"):
            module.render(self.outputs, self.env)

    def test_non_commit_image_revision_blocks_release(self):
        self.env["GITHUB_SHA"] = "latest"
        with self.assertRaisesRegex(ValueError, "full Git commit SHA"):
            module.render(self.outputs, self.env)

    def test_multi_az_choice_is_preserved(self):
        self.env["MMIV_DEMO_SINGLE_AZ"] = "false"
        self.assertFalse(module.render(self.outputs, self.env)["demo_single_az"])

    def test_first_release_base_has_no_running_services(self):
        self.env["MMIV_START_SERVICES"] = "false"
        self.assertFalse(module.render(self.outputs, self.env)["start_services"])

    def test_workflow_waits_for_all_images_and_keeps_old_host_disabled(self):
        import yaml

        workflow = yaml.safe_load((ROOT / ".github/workflows/cicd.yml").read_text())
        release = workflow["jobs"]["release-ecs"]
        self.assertEqual(release["needs"], ["build-images", "build-oracle-bootstrap"])
        self.assertEqual(release["with"]["apply"], True)
        self.assertIn("MMIV_ECS_AUTO_DEPLOY", release["if"])
        self.assertIn("refs/heads/main", release["if"])
        self.assertEqual(workflow["jobs"]["deploy-stack"]["if"], False)
        bootstrap = yaml.safe_load((ROOT / ".github/workflows/oracle-bootstrap.yml").read_text())
        self.assertEqual(bootstrap["jobs"]["first-release"]["needs"], ["initialize"])
        self.assertTrue(bootstrap["jobs"]["first-release"]["with"]["first_release"])

    def test_manual_first_deployment_waits_for_checks_and_images_before_spending(self):
        import yaml

        cicd = yaml.safe_load((ROOT / ".github/workflows/cicd.yml").read_text())
        # PyYAML 1.1 parses an unquoted YAML `on` key as boolean True.
        self.assertFalse(cicd[True]["workflow_dispatch"]["inputs"]["deploy_demo"]["default"])
        first = cicd["jobs"]["first-ecs-deployment"]
        self.assertEqual(first["needs"], ["build-images", "build-oracle-bootstrap"])
        self.assertIn("workflow_dispatch", first["if"])
        self.assertIn("MMIV_BUILD_IMAGES", first["if"])
        self.assertTrue(first["with"]["apply"])
        self.assertIn("!inputs.deploy_demo", cicd["jobs"]["release-ecs"]["if"])

        orchestrator = yaml.safe_load((ROOT / ".github/workflows/first-ecs-deployment.yml").read_text())
        jobs = orchestrator["jobs"]
        self.assertIn("oracle-bootstrap", jobs["preflight"]["steps"][-1]["run"])
        self.assertEqual(jobs["managed-data"]["needs"], "preflight")
        self.assertEqual(jobs["ecs-base"]["needs"], "managed-data")
        self.assertEqual(jobs["oracle-and-services"]["needs"], "ecs-base")
        self.assertTrue(jobs["ecs-base"]["with"]["prepare_base"])
        self.assertTrue(jobs["ecs-base"]["with"]["apply"])
        self.assertIn("workflow_call", orchestrator["on"])


if __name__ == "__main__":
    unittest.main()
