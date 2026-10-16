#!/usr/bin/env python3
"""Pin frontend build and deployment ownership to API Gateway."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


class GatewayFrontendOwnershipTest(unittest.TestCase):
    def test_workflow_documents_and_builds_gateway_owned_frontend(self):
        workflow_path = ROOT / '.github/workflows/cicd.yml'
        source = workflow_path.read_text()
        workflow = yaml.safe_load(source)
        validate_source = (ROOT / '.github/workflows/validate.yml').read_text()
        self.assertIn('frontend/**', workflow[True]['push']['paths'])
        self.assertIn('frontend is packaged into the API Gateway image', source)
        self.assertNotIn('frontend is packaged into the Transfer Service image', source)
        self.assertIn('python3 tests/ci/test-gateway-frontend-ownership.py', source)
        self.assertIn('python ../../tests/ci/test-gateway-frontend-ownership.py', validate_source)
        matrix = workflow['jobs']['build-images']['strategy']['matrix']['service']
        self.assertIn({'name': 'api-gateway', 'path': 'services/api-gateway', 'port': '8080'}, matrix)

    def test_gateway_build_runs_after_checkout_and_packages_frontend(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/build-service.yml').read_text())
        steps = workflow['jobs']['build']['steps']
        checkout = next(index for index, step in enumerate(steps)
                        if str(step.get('uses', '')).startswith('actions/checkout@'))
        build = next(index for index, step in enumerate(steps) if step.get('name') == 'Build and test')
        self.assertLess(checkout, build)
        pom = (ROOT / 'services/api-gateway/pom.xml').read_text()
        self.assertIn('<id>frontend</id>', pom)
        self.assertIn('${project.basedir}/../../frontend', pom)
        self.assertIn('<targetPath>META-INF/resources</targetPath>', pom)

    def test_transfer_outage_acceptance_is_scoped_and_self_cleaning(self):
        script = ROOT / 'tests/api/verify-reporting-with-transfer-down.sh'
        self.assertTrue(script.exists(), 'Transfer-outage acceptance script is required')
        source = script.read_text()
        for required in (
            'login-for-verification.py',
            '/api/v1/session',
            '/pages/reports.html',
            '/api/reports/mine',
            '/files',
            '/timeline',
            'stop transfer-service',
            'start transfer-service',
            'trap cleanup EXIT',
        ):
            self.assertIn(required, source)
        self.assertNotIn('echo "$API_TEST_TOKEN"', source)
        self.assertNotIn('set -x', source)


if __name__ == '__main__':
    unittest.main()
