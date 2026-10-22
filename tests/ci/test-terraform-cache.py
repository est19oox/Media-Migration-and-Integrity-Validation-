#!/usr/bin/env python3
"""Exercise shared-cache wiring and failure propagation without cloud access."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args=sys.argv[1:]; cache=Path(os.environ['TF_PLUGIN_CACHE_DIR'])
assert cache.is_dir(), 'cache must exist before Terraform runs'
with open(os.environ['TF_TEST_LOG'], 'a') as f:
 f.write(json.dumps({'args':args,'cache':str(cache)})+'\\n')
root=Path(args[0].split('=',1)[1]); action=args[1]
if action=='init':
 if os.environ.get('TF_TEST_FAIL')==str(root): sys.exit(9)
 provider=cache/'fixture-provider'
 if not provider.exists():
  provider.write_text('provider fixture')
  with open(os.environ['TF_TEST_LOG'], 'a') as f: f.write(json.dumps({'download':True})+'\\n')
 local=root/'.terraform/providers'; local.mkdir(parents=True,exist_ok=True)
 link=local/'fixture-provider'
 if not link.exists(): link.symlink_to(provider)
if action=='validate': assert (root/'.terraform/providers/fixture-provider').is_file()
'''

class CacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base/'repository with spaces'
        self.roots = [self.root/'infrastructure'/p for p in
                      ['bootstrap', 'terraform', 'terraform/environments/dev', 'ecs-fargate', 'aws-managed-data']]
        for p in self.roots:
            p.mkdir(parents=True, exist_ok=True)
            (p/'main.tf').write_text('terraform {}\n')
        bin_dir = self.base/'bin'; bin_dir.mkdir()
        fake = bin_dir/'terraform'; fake.write_text(FAKE); fake.chmod(0o755)
        self.log = self.base/'calls.jsonl'
        self.env = dict(os.environ, PATH=str(bin_dir)+os.pathsep+os.environ['PATH'],
                        TF_TEST_LOG=str(self.log))
        self.env.pop('TF_PLUGIN_CACHE_DIR', None)
        self.env.pop('TF_TEST_FAIL', None)

    def run_check(self):
        return subprocess.run(['sh', str(ROOT/'scripts/ci/run-terraform-checks.sh'),
                               str(self.root)], env=self.env, text=True, capture_output=True)

    def calls(self):
        return [json.loads(s) for s in self.log.read_text().splitlines()]

    def assert_shared(self, cache):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        records = self.calls()
        self.assertEqual(sum(bool(r.get('download')) for r in records), 1)
        commands = [r for r in records if 'args' in r]
        self.assertEqual([r['args'][1] for r in commands],
                         ['fmt','init','validate','init','validate','init','validate','init','validate','init','validate'])
        self.assertEqual({r['cache'] for r in commands}, {str(cache)})
        for p in self.roots:
            self.assertEqual((p/'.terraform/providers/fixture-provider').resolve(),
                             cache/'fixture-provider')
        for r in commands:
            if r['args'][1]=='init': self.assertIn('-backend=false', r['args'])

    def test_default_cache_shared_across_all_roots(self):
        self.assert_shared(self.root/'.ci/cache/terraform-providers')

    def test_explicit_cache_with_spaces_is_preserved_on_repeat(self):
        cache = self.base/'shared cache'; cache.mkdir()
        sentinel = cache/'keep'; sentinel.write_text('existing cache entry')
        self.env['TF_PLUGIN_CACHE_DIR'] = str(cache)
        self.assert_shared(cache)
        self.assertEqual(self.run_check().returncode, 0)
        self.assertEqual(sum(bool(r.get('download')) for r in self.calls()), 1)
        self.assertEqual(sentinel.read_text(), 'existing cache entry')

    def test_init_failure_stops_before_later_root(self):
        self.env['TF_TEST_FAIL'] = str(self.roots[1])
        self.assertEqual(self.run_check().returncode, 9)
        self.assertFalse(any(str(self.roots[2]) in str(r) for r in self.calls()))

    def test_no_configuration_skips_without_cache(self):
        for p in self.roots: (p/'main.tf').unlink()
        self.assertEqual(self.run_check().returncode, 0)
        self.assertFalse(self.log.exists())
        self.assertFalse((self.root/'.ci/cache').exists())

if __name__ == '__main__':
    unittest.main()
