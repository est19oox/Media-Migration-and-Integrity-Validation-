#!/usr/bin/env python3
"""Validate the Reporting Service runtime wiring without starting Docker."""

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
LOCAL_COMPOSE = ROOT / "services" / "reporting-service" / "compose.yml"
FULL_STACKS = (
    ROOT / "infrastructure" / "service-registry" / "compose.yml",
    ROOT / "infrastructure" / "service-registry" / "compose-scaled.yml",
)
APPLICATION_CONFIG = (
    ROOT
    / "services"
    / "reporting-service"
    / "src"
    / "main"
    / "resources"
    / "application.yml"
)


def load_compose(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        return yaml.safe_load(stream)


class ReportingRuntimeContractTest(unittest.TestCase):

    def test_spring_boot_4_uses_current_mongodb_uri_property(self) -> None:
        application = load_compose(APPLICATION_CONFIG)

        self.assertIn("mongodb", application["spring"])
        self.assertNotIn("data", application["spring"])
        self.assertIn(
            "${MONGODB_URI:",
            application["spring"]["mongodb"]["uri"],
        )

    def test_local_stack_has_separate_healthy_dependencies(self) -> None:
        compose = load_compose(LOCAL_COMPOSE)
        services = compose["services"]

        self.assertTrue(
            {"reporting-service", "mongodb", "rabbitmq"}
            <= set(services)
        )

        reporting = services["reporting-service"]
        self.assertEqual(
            "service_healthy",
            reporting["depends_on"]["mongodb"]["condition"],
        )
        self.assertEqual(
            "service_healthy",
            reporting["depends_on"]["rabbitmq"]["condition"],
        )

        environment = reporting["environment"]
        self.assertIn("MONGODB_URI", environment)
        self.assertIn("SPRING_RABBITMQ_HOST", environment)
        self.assertIn("SPRING_RABBITMQ_USERNAME", environment)
        self.assertIn("SPRING_RABBITMQ_PASSWORD", environment)

        self.assertIn("healthcheck", reporting)
        self.assertIn("healthcheck", services["mongodb"])
        self.assertIn("healthcheck", services["rabbitmq"])

    def test_local_stack_is_pinned_loopback_bound_and_persistent(self) -> None:
        compose = load_compose(LOCAL_COMPOSE)
        services = compose["services"]

        for dependency in ("mongodb", "rabbitmq"):
            image = services[dependency]["image"]
            self.assertIn(":", image)
            self.assertNotIn(":latest", image)

        for name in ("reporting-service", "mongodb", "rabbitmq"):
            for port in services[name].get("ports", []):
                self.assertTrue(
                    str(port).startswith("127.0.0.1:"),
                    f"{name} port is not loopback-bound: {port}",
                )

        self.assertIn("mongo-data", compose["volumes"])
        self.assertIn("rabbit-data", compose["volumes"])
        self.assertIn("mongo-data:/data/db", services["mongodb"]["volumes"])
        self.assertIn(
            "rabbit-data:/var/lib/rabbitmq",
            services["rabbitmq"]["volumes"],
        )

    def test_full_stacks_wire_reporting_to_mongo_and_rabbit(self) -> None:
        for path in FULL_STACKS:
            with self.subTest(compose=path.name):
                compose = load_compose(path)
                services = compose["services"]
                self.assertTrue(
                    {"reporting-service", "mongodb", "rabbitmq"}
                    <= set(services)
                )

                reporting = services["reporting-service"]
                self.assertEqual(
                    "service_healthy",
                    reporting["depends_on"]["mongodb"]["condition"],
                )
                self.assertEqual(
                    "service_healthy",
                    reporting["depends_on"]["rabbitmq"]["condition"],
                )
                self.assertIn("MONGODB_URI", reporting["environment"])
                self.assertEqual(
                    "rabbitmq",
                    reporting["environment"]["SPRING_RABBITMQ_HOST"],
                )

                self.assertIn("mongo-data", compose["volumes"])
                self.assertIn("rabbit-data", compose["volumes"])

    def test_validate_workflow_runs_this_contract(self) -> None:
        workflow = load_compose(
            ROOT / ".github" / "workflows" / "validate.yml"
        )
        commands = "\n".join(
            str(step.get("run", ""))
            for job in workflow["jobs"].values()
            for step in job.get("steps", [])
        )
        self.assertIn(
            "python3 tests/ci/test-reporting-runtime.py",
            commands,
        )


if __name__ == "__main__":
    unittest.main()
