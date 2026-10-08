#!/usr/bin/env python3
"""Wait for both configured clients to appear UP in a running Eureka registry."""
import json
import sys
import time
import urllib.request
import urllib.error

url = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8761").rstrip("/") + "/eureka/apps"
expected = {"TRANSFER-SERVICE", "INTEGRITY-SERVICE"}
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
            print("PASS: TRANSFER-SERVICE and INTEGRITY-SERVICE are registered UP")
            sys.exit(0)
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError):
        pass
    time.sleep(3)
print("FAIL: both services did not register UP within 180 seconds", file=sys.stderr)
sys.exit(1)
