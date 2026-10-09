#!/usr/bin/env python3
"""Render and execute rollout/rollback commands against a fake Docker CLI."""
from pathlib import Path
import base64
import json
import os
import shlex
import subprocess
import tempfile
import unittest
import yaml
from jinja2 import Environment

ROOT = Path(__file__).resolve().parents[2]
ROLE = ROOT / "infrastructure/ansible/roles/microservice/tasks/main.yml"

class DeploymentCommandsTest(unittest.TestCase):
    def test_network_registry_bind_and_image_on_every_path(self):
        role = yaml.safe_load(ROLE.read_text())
        block = next(task for task in role if "block" in task)
        deploy = next(t for t in block["block"] if t["name"] == "Run microservice")
        rescue = next(t for t in block["rescue"] if t["name"] == "Restore previous microservice image")
        rollback = next(t for t in yaml.safe_load(
            (ROOT / "infrastructure/ansible/rollback.yml").read_text())[0]["tasks"]
            if t["name"] == "Restore previous image")
        self.assertTrue(rollback["changed_when"])
        self.assertNotIn("changed_when:", rollback["ansible.builtin.shell"])
        environment = Environment(trim_blocks=True)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            log = directory / "docker.jsonl"
            docker = directory / "docker"
            docker.write_text("#!/usr/bin/env python3\n".replace("\\n", "\n") +
                "import json, os, sys\n".replace("\\n", "\n") +
                "with open(os.environ['MMIV_DOCKER_TEST_LOG'], 'a') as f: f.write(json.dumps(sys.argv[1:]) + chr(10))\n".replace("\\n", "\n"))
            docker.chmod(0o755)
            previous = directory / "previous-image"
            previous.write_text("test:previous")
            shell_env = {**os.environ, "PATH": str(directory) + os.pathsep + os.environ["PATH"],
                         "MMIV_DOCKER_TEST_LOG": str(log)}
            for name, port in [("service-registry", 8761), ("api-gateway", 8080),
                               ("transfer-service", 8081), ("integrity-service", 8082)]:
                ctx = dict(microservice_name=name, microservice_image="test:new",
                           microservice_host_port=port, microservice_container_port=port,
                           microservice_bind_address=environment.from_string(yaml.safe_load(
                               (ROOT / "infrastructure/ansible/roles/microservice/defaults/main.yml").read_text())["microservice_bind_address"]).render(microservice_name=name),
                           mmiv_docker_network="mmiv-backend",
                           aws_region="ap-southeast-1", microservice_bucket="test",
                           microservice_eureka_url="http://service-registry:8761/eureka/",
                           microservice_previous_image_file=str(previous),
                           previous_image={"content": base64.b64encode(b"test:previous").decode()})
                for task, kind, image in [(deploy, "ansible.builtin.command", "test:new"),
                                          (rescue, "ansible.builtin.shell", "test:previous"),
                                          (rollback, "ansible.builtin.shell", "test:previous")]:
                    with self.subTest(service=name, task=task["name"]):
                        raw = task[kind]
                        raw = raw["cmd"] if isinstance(raw, dict) else raw
                        rendered = environment.from_string(raw).render(**ctx)
                        if kind.endswith("command"):
                            subprocess.run(shlex.split(rendered), env=shell_env, check=True)
                        else:
                            subprocess.run(["bash", "-c", rendered], env=shell_env, check=True)
                        args = json.loads(log.read_text().splitlines()[-1])
                        self.assertEqual(args[0], "run")
                        self.assertEqual(args[-1], image)
                        self.assertEqual(args[args.index("--network")+1], "mmiv-backend")
                        self.assertEqual(args[args.index("-p")+1], f"{'0.0.0.0' if name == 'api-gateway' else '127.0.0.1'}:{port}:{port}")
                        self.assertIn("EUREKA_DEFAULT_ZONE=http://service-registry:8761/eureka/", args)
                        self.assertNotIn("/etc/mmiv/redis.env", args)
                        self.assertEqual("/etc/mmiv/integrity.env" in args, name == "integrity-service")

    def test_every_deployed_service_has_functional_configuration(self):
        config = json.loads((ROOT / "tests/api/endpoints.json").read_text())
        workflow = yaml.safe_load((ROOT / ".github/workflows/cicd.yml").read_text())
        services = ["service-registry", "api-gateway"] + [
            service["name"] for service in workflow["jobs"]["deploy-backends"]["strategy"]["matrix"]["service"]]
        for service in services:
            self.assertIn(service, config["services"])
            self.assertTrue(config["common"] + config["services"][service])
        paths = {case["path"] for case in config["services"]["api-gateway"]}
        self.assertIn("/api/v1/transfers", paths)
        self.assertIn("/api/v1/transfers/folder", paths)
        self.assertIn("/upload.html", paths)

if __name__ == "__main__":
    unittest.main()
