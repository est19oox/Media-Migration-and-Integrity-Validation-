#!/usr/bin/env python3
"""Guard automated ECS releases against missing state and stale image revisions."""

import importlib.util
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "ecs_release", ROOT / "scripts/cd/render-ecs-release-vars.py"
)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)
PLAN_SPEC = importlib.util.spec_from_file_location(
    "ecs_plan", ROOT / "scripts/cd/check-ecs-release-plan.py"
)
plan_module = importlib.util.module_from_spec(PLAN_SPEC)
PLAN_SPEC.loader.exec_module(plan_module)
DATA_PLAN_SPEC = importlib.util.spec_from_file_location(
    "managed_data_plan", ROOT / "scripts/cd/check-managed-data-plan.py"
)
data_plan_module = importlib.util.module_from_spec(DATA_PLAN_SPEC)
DATA_PLAN_SPEC.loader.exec_module(data_plan_module)


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

    def test_first_release_accepts_unapplied_ecs_state(self):
        import yaml

        workflow = yaml.safe_load((ROOT / ".github/workflows/ecs-release.yml").read_text())
        steps = workflow["jobs"]["release"]["steps"]
        plan = next(step["run"] for step in steps if step.get("name") == "Plan ECS release")
        self.assertIn(
            "bash scripts/cd/terraform-state-list-or-empty.sh infrastructure/ecs-fargate",
            plan,
        )
        self.assertNotIn("terraform -chdir=infrastructure/ecs-fargate state list", plan)

    def test_image_revision_can_replace_task_definitions_without_destroying_infrastructure(self):
        changes = [
            {"address": 'aws_ecs_task_definition.service["api-gateway"]',
             "change": {"actions": ["delete", "create"]}},
            {"address": "aws_ecs_task_definition.oracle_bootstrap",
             "change": {"actions": ["create", "delete"]}},
            {"address": "aws_ecs_service.service[\"api-gateway\"]",
             "change": {"actions": ["update"]}},
        ]
        self.assertEqual(plan_module.forbidden_deletions({"resource_changes": changes}), [])
        changes.append({"address": "aws_efs_file_system.outbox",
                        "change": {"actions": ["delete", "create"]}})
        self.assertEqual(
            plan_module.forbidden_deletions({"resource_changes": changes}),
            ["aws_efs_file_system.outbox"],
        )
        self.assertEqual(
            plan_module.forbidden_deletions({"resource_changes": [
                {"address": "aws_ecs_task_definition.oracle_bootstrap",
                 "change": {"actions": ["delete"]}}
            ]}),
            ["aws_ecs_task_definition.oracle_bootstrap"],
        )

    def test_registry_configuration_avoids_empty_custom_health_check_drift(self):
        terraform = (ROOT / "infrastructure/ecs-fargate/main.tf").read_text()
        self.assertNotIn("health_check_custom_config {}", terraform)
        changes = {"resource_changes": [
            {"address": "aws_service_discovery_service.registry",
             "change": {"actions": ["delete", "create"]}},
            {"address": "aws_service_discovery_service.registry_peer",
             "change": {"actions": ["delete", "create"]}},
        ]}
        self.assertEqual(
            plan_module.forbidden_deletions(changes),
            ["aws_service_discovery_service.registry", "aws_service_discovery_service.registry_peer"],
        )

    def test_cloudfront_vpc_origin_allows_managed_source_without_replacing_existing_rule(self):
        terraform = (ROOT / "infrastructure/ecs-fargate/main.tf").read_text()
        self.assertIn('name  = "com.amazonaws.global.cloudfront.origin-facing"', terraform)
        rule = re.search(
            r'resource "aws_vpc_security_group_ingress_rule" "alb_cloudfront_prefix" \{(.*?)\n\}',
            terraform,
            re.S,
        )
        self.assertIsNotNone(rule)
        self.assertIn('prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin[0].id',
                      rule.group(1))
        self.assertIn('cidr_ipv4         = data.aws_vpc.mmiv.cidr_block', terraform)

    def test_oracle_schema_password_stays_within_identifier_limit(self):
        terraform = (ROOT / "infrastructure/aws-managed-data/main.tf").read_text()
        bootstrap = (ROOT / "infrastructure/aws-managed-data/bootstrap-oracle.py").read_text()
        configured = re.search(r'resource "random_password" "schema" \{[^}]*length\s*=\s*(\d+)', terraform)
        accepted = re.search(r're.fullmatch\(r"\[A-Za-z0-9\]\{(\d+)\}"', bootstrap)
        self.assertIsNotNone(configured)
        self.assertIsNotNone(accepted)
        self.assertEqual(int(configured.group(1)), int(accepted.group(1)))
        self.assertLessEqual(int(configured.group(1)), 30)

    def test_managed_data_plan_allows_only_schema_password_replacements(self):
        import yaml

        workflow = yaml.safe_load((ROOT / ".github/workflows/managed-data.yml").read_text())
        step = next(step["run"] for step in workflow["jobs"]["managed-data"]["steps"]
                    if step.get("name") == "Validate configuration and inspect plan")
        self.assertIn("python3 scripts/cd/check-managed-data-plan.py", step)
        changes = [
            {"address": address, "change": {"actions": ["delete", "create"]}}
            for address in sorted(data_plan_module.PASSWORD_RESOURCES)
        ]
        self.assertEqual(data_plan_module.forbidden_deletions({"resource_changes": changes}), [])
        changes.extend([
            {"address": "aws_db_instance.oracle", "change": {"actions": ["delete", "create"]}},
            {"address": 'random_password.schema["UNEXPECTED"]',
             "change": {"actions": ["delete", "create"]}},
            {"address": 'aws_secretsmanager_secret_version.schema["USER_ACCESS"]',
             "change": {"actions": ["delete"]}},
        ])
        self.assertEqual(
            data_plan_module.forbidden_deletions({"resource_changes": changes}),
            ["aws_db_instance.oracle", 'random_password.schema["UNEXPECTED"]',
             'aws_secretsmanager_secret_version.schema["USER_ACCESS"]'],
        )

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

    def test_zap_runs_after_healthy_ecs_release_and_archives_reports(self):
        import yaml

        workflow = yaml.safe_load((ROOT / ".github/workflows/ecs-release.yml").read_text())
        release = workflow["jobs"]["release"]
        dast = workflow["jobs"]["dast"]
        self.assertEqual(dast["needs"], "release")
        self.assertIn("inputs.apply", dast["if"])
        self.assertIn("!inputs.prepare_base", dast["if"])
        self.assertIn("steps.health.outputs.browser_url", release["outputs"]["browser_url"])
        health = next(step for step in release["steps"] if step.get("id") == "health")
        self.assertIn("browser_url=$url", health["run"])
        scan = next(step for step in dast["steps"] if step.get("name") == "Scan public HTTPS Gateway")
        self.assertEqual(scan["env"]["MMIV_TEST_NETWORK"], "bridge")
        self.assertIn("run-dast.sh", scan["run"])
        self.assertIn("https://", scan["run"])
        evidence = next(step for step in dast["steps"] if step.get("name") == "Upload ECS DAST evidence")
        self.assertIn("zap-report.json", evidence["with"]["path"])
        self.assertIn("zap-report.html", evidence["with"]["path"])

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
