#!/usr/bin/env python3
"""Keep the shared Trivy installer pinned and wired into every CI job."""

import hashlib
import os
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
INSTALLER = ROOT / "scripts/ci/install-trivy.sh"


class TrivyInstallerTest(unittest.TestCase):
    def test_only_verified_package_is_installed(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            (directory / "curl").write_text(
                '#!/bin/sh\nwhile [ "$#" -gt 0 ]; do\n'
                '  if [ "$1" = --output ]; then shift; output=$1; fi\n'
                '  shift\ndone\nprintf package > "$output"\n'
            )
            (directory / "sudo").write_text(
                '#!/bin/sh\nprintf "%s\\n" "$*" >> "$MMIV_TEST_INSTALL_LOG"\n'
            )
            (directory / "docker").write_text('#!/bin/sh\nexit 1\n')
            for name in ("curl", "sudo", "docker"):
                (directory / name).chmod(0o755)
            log = directory / "install.log"
            env = os.environ | {
                "PATH": f"{directory}:{os.environ['PATH']}",
                "TRIVY_VERSION": "0.74.0",
                "TRIVY_SHA256": hashlib.sha256(b"package").hexdigest(),
                "MMIV_TEST_INSTALL_LOG": str(log),
            }
            subprocess.run(["bash", str(INSTALLER)], env=env, check=True, capture_output=True)
            self.assertIn("apt-get install --yes", log.read_text())
            log.unlink()
            env["TRIVY_SHA256"] = "0" * 64
            failure = subprocess.run(["bash", str(INSTALLER)], env=env, capture_output=True)
            self.assertNotEqual(failure.returncode, 0)
            self.assertFalse(log.exists())

    def test_official_image_and_binary_are_pinned(self):
        installer = INSTALLER.read_text()
        self.assertIn('ghcr.io/aquasecurity/trivy@sha256:ee940acbf1f58ebadb42d01434ce4609530bf1b52536afbd1eee66cd7123c5c9', installer)
        self.assertIn('d89bcc6510a267f11b773398cbf1be5520ce39f9e8b6633178c4487f05b7d791', installer)
        self.assertLess(installer.index('sha256sum --check --strict'), installer.index('sudo install'))

    def test_every_workflow_uses_shared_installer(self):
        import yaml

        for name in ("build-service.yml", "cicd.yml", "validate.yml"):
            workflow = yaml.safe_load((ROOT / ".github/workflows" / name).read_text())
            installs = [step for job in workflow["jobs"].values()
                        for step in job.get("steps", []) if step.get("name") == "Install Trivy"]
            self.assertTrue(installs, name)
            self.assertTrue(all("bash scripts/ci/install-trivy.sh" in step["run"] for step in installs), name)


if __name__ == "__main__":
    unittest.main()
