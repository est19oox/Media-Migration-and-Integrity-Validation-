#!/usr/bin/env python3
"""Check bounded permission reconciliation without calling AWS."""
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sqs_policy', ROOT/'scripts/cd/ensure-terraform-sqs-policy.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ACCOUNT = '275069781906'
IDENTITY = {'Account': ACCOUNT, 'Arn': f'arn:aws:sts::{ACCOUNT}:assumed-role/{module.ROLE}/GitHubActions'}
TEMPLATE = ROOT/'infrastructure/bootstrap/policies/file-log-queue-provisioning.json'
DESIRED = json.loads(TEMPLATE.read_text().replace('${aws_region}', 'ap-southeast-1').replace('${aws_account_id}', ACCOUNT))

def response(value=None, code=0, error=''):
    return SimpleNamespace(returncode=code, stdout=json.dumps(value), stderr=error)

class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {'AWS_REGION':'ap-southeast-1'})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_missing_policy_is_created_only_for_named_queues(self):
        with patch.object(module, 'aws', side_effect=[response(IDENTITY),response(code=1,error='NoSuchEntity'),response({})]) as aws:
            module.ensure()
        args = aws.call_args_list[-1].args
        self.assertEqual(args[:6], ('iam','put-role-policy','--role-name',module.ROLE,'--policy-name',module.POLICY_NAME))
        policy = json.loads(args[-1])
        self.assertEqual(policy, DESIRED)
        statement = policy['Statement'][0]
        self.assertEqual(statement['Resource'], [f'arn:aws:sqs:ap-southeast-1:{ACCOUNT}:mmiv-file-logs',
                                                f'arn:aws:sqs:ap-southeast-1:{ACCOUNT}:mmiv-file-logs-dead-letter'])
        self.assertEqual(set(statement['Action']), {'sqs:CreateQueue','sqs:DeleteQueue','sqs:GetQueueAttributes',
            'sqs:SetQueueAttributes','sqs:GetQueueUrl','sqs:TagQueue','sqs:UntagQueue','sqs:ListQueueTags'})

    def test_current_policy_does_not_write_again(self):
        with patch.object(module, 'aws', side_effect=[response(IDENTITY),response({'PolicyDocument':DESIRED})]) as aws:
            module.ensure()
        self.assertEqual(aws.call_count, 2)

    def test_outdated_policy_is_replaced(self):
        with patch.object(module,'aws',side_effect=[response(IDENTITY),response({'PolicyDocument':{'Version':'2012-10-17','Statement':[]}}),response({})]) as aws:
            module.ensure()
        self.assertEqual(aws.call_args.args[1], 'put-role-policy')

    def test_read_access_denied_stops_before_write(self):
        with patch.object(module,'aws',side_effect=[response(IDENTITY),response(code=1,error='AccessDenied')]) as aws:
            with self.assertRaisesRegex(RuntimeError,'iam:GetRolePolicy'): module.ensure()
        self.assertEqual(aws.call_count,2)

    def test_write_access_denied_identifies_required_admin_permission(self):
        with patch.object(module,'aws',side_effect=[response(IDENTITY),response(code=1,error='NoSuchEntity'),response(code=1,error='AccessDenied')]):
            with self.assertRaisesRegex(RuntimeError,'iam:PutRolePolicy'): module.ensure()

    def test_wrong_role_is_rejected_before_iam_calls(self):
        identity = dict(IDENTITY, Arn=f'arn:aws:sts::{ACCOUNT}:assumed-role/OtherRole/session')
        with patch.object(module,'aws',return_value=response(identity)) as aws:
            with self.assertRaisesRegex(RuntimeError,'existing GitHubActions'): module.ensure()
        self.assertEqual(aws.call_count,1)

    def test_policy_preparation_precedes_plan_and_uses_bootstrap_template(self):
        workflow = yaml.safe_load((ROOT/'.github/workflows/cicd.yml').read_text())
        steps = workflow['jobs']['terraform']['steps']
        setup = next(i for i,s in enumerate(steps) if 'ensure-terraform-sqs-policy.py' in s.get('run',''))
        plan = next(i for i,s in enumerate(steps) if 'terraform plan' in s.get('run',''))
        self.assertLess(setup,plan)
        self.assertIn('templatefile("${path.module}/policies/file-log-queue-provisioning.json"',
                      (ROOT/'infrastructure/bootstrap/github.tf').read_text())

if __name__ == '__main__':
    unittest.main()
