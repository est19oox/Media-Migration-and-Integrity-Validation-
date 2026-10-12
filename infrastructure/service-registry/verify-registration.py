#!/usr/bin/env python3
"""Wait for gateway and backend clients to appear UP in a running Eureka registry."""
import argparse
import json
import sys
import time
import urllib.request
import urllib.error

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('url', nargs='?', default='http://localhost:8761')
parser.add_argument('--include-discovery', action='store_true',
                    help='Require Discovery when its optional Compose profile is enabled')
args = parser.parse_args()
url = args.url.rstrip('/') + '/eureka/apps'
expected = {"TRANSFER-SERVICE", "INTEGRITY-SERVICE", "USER-ACCESS-SERVICE", "REPORTING-SERVICE", "API-GATEWAY"}
if args.include_discovery:
    expected.add('DISCOVERY-SERVICE')
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    try:
        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            data = json.load(response)
        applications = data.get("applications", {}).get("application", [])
        if isinstance(applications, dict):
            applications = [applications]
        available = set()
        for app in applications:
            instances = app.get("instance", [])
            if isinstance(instances, dict):
                instances = [instances]
            if any(instance.get("status") == "UP" for instance in instances):
                available.add(app["name"].upper())
        if expected <= available:
            print("PASS: All requested clients are registered UP")
            sys.exit(0)
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError):
        pass
    time.sleep(3)
print("FAIL: enabled clients did not register UP within 180 seconds", file=sys.stderr)
sys.exit(1)
