#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('bootstrap',ROOT/'scripts/admin/bootstrap-first-admin.py')
bootstrap=importlib.util.module_from_spec(spec);spec.loader.exec_module(bootstrap)

class BootstrapTest(unittest.TestCase):
    def test_payload_contains_only_hash_not_plaintext_invitation(self):
        token='a'*64
        digest=hashlib.sha256(token.encode()).hexdigest()
        sql=bootstrap.invitation_sql('00000000-0000-0000-0000-000000000001',digest)
        self.assertNotIn(token,sql)
        self.assertIn(digest,sql)
        self.assertIn('LOCK TABLE APP_USERS IN EXCLUSIVE MODE WAIT 10',sql)
        self.assertIn('ADMIN_BOOTSTRAP_STATE',sql)
        self.assertIn('CONSUMED_AT',sql)
        self.assertIn("ROLE='ADMIN'",sql)
        self.assertIn("INTERVAL '24' HOUR",sql)
        self.assertNotIn(token,bootstrap.remote_command(sql))
    def test_invalid_id_or_hash_never_reaches_sql(self):
        with self.assertRaises(ValueError):bootstrap.invitation_sql("x' OR 1=1",'a'*64)
        with self.assertRaises(ValueError):bootstrap.invitation_sql('00000000-0000-0000-0000-000000000001',"x' OR 1=1")
    def test_expired_invitation_replacement_is_explicit_and_guarded(self):
        sql=bootstrap.invitation_sql('00000000-0000-0000-0000-000000000001','a'*64,True)
        self.assertIn('IF 1 != 1',sql)
        self.assertIn('previous_consumed IS NOT NULL OR previous_expiry>SYSTIMESTAMP',sql)
        self.assertIn('Only an expired unused first invitation can be replaced',sql)

if __name__=='__main__':unittest.main()
