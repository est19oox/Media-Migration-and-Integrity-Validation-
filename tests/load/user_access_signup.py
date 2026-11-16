#!/usr/bin/env python3
"""Bounded User Access signup and login load with verification-account cleanup.

Use --gateway-url on the temporary no-rate-limit demo branch to run from a
computer with Internet access. Otherwise run from an MMIV VPC Task. No account
credentials are written to the report or stdout.
"""

import argparse
import ipaddress
import json
import secrets
import threading
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


DEFAULT_EUREKA = (
    "http://service-registry.mmiv.local:8761/eureka/apps/USER-ACCESS-SERVICE"
)


def parse_instances(document):
    entries = document.get("application", {}).get("instance", [])
    if isinstance(entries, dict):
        entries = [entries]
    targets = set()
    for entry in entries:
        try:
            address = ipaddress.ip_address(entry["ipAddr"])
            port = int(entry["port"]["$"])
            if (
                entry["status"] == "UP"
                and address.version == 4
                and address.is_private
                and not address.is_link_local
                and not address.is_loopback
                and port == 8083
            ):
                targets.add(f"http://{address}:{port}")
        except (KeyError, TypeError, ValueError):
            continue
    return sorted(targets)


class EurekaTargets:
    def __init__(self, url):
        self.url = url
        self.lock = threading.Lock()
        self.targets = []
        self.refreshed_at = 0.0
        self.observed = set()

    def choose(self, number):
        with self.lock:
            if not self.targets or time.monotonic() - self.refreshed_at >= 10:
                request = urllib.request.Request(
                    self.url, headers={"Accept": "application/json"}
                )
                with urllib.request.urlopen(request, timeout=5) as response:
                    targets = parse_instances(json.load(response))
                if not targets:
                    raise RuntimeError("No reachable User Access instances in Eureka")
                self.targets = targets
                self.observed.update(targets)
                self.refreshed_at = time.monotonic()
                print(f"discovered_instances={len(targets)}", flush=True)
            return self.targets[number % len(self.targets)]

    def refresh_after_failure(self):
        with self.lock:
            self.refreshed_at = 0.0

    @staticmethod
    def path(path):
        return path


class GatewayTarget:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.observed = set()

    def choose(self, _number):
        return self.url

    def refresh_after_failure(self):
        pass

    @staticmethod
    def path(path):
        return "/user-access" + path


def send(base, path, method, data=None, token=None):
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(
        base + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read()
        return response.status, json.loads(body) if body else {}


def cleanup_account(catalog, number, account):
    username, password = account
    for attempt in range(3):
        try:
            base = catalog.choose(number + attempt)
            status, login = send(
                base,
                catalog.path("/api/auth/login"),
                "POST",
                {"username": username, "password": password},
            )
            token = login.get("accessToken")
            if status != 200 or login.get("tokenType", "").lower() != "bearer" or not token:
                raise RuntimeError("Verification account login failed")
            status, _ = send(
                base, catalog.path("/api/auth/verification-account"), "DELETE", token=token
            )
            if status != 204:
                raise RuntimeError("Verification account deletion failed")
            return True
        except (OSError, ValueError, RuntimeError, urllib.error.HTTPError):
            catalog.refresh_after_failure()
            if attempt < 2:
                time.sleep(attempt + 1)
    return False


def run(args):
    catalog = GatewayTarget(args.gateway_url) if getattr(args, "gateway_url", None) else EurekaTargets(args.eureka_url)
    accounts = []
    results = Counter()
    latencies = []
    lock = threading.Lock()
    number = 0
    deadline = time.monotonic() + args.minutes * 60
    started = time.monotonic()

    def worker():
        nonlocal number
        while time.monotonic() < deadline:
            with lock:
                if number < args.max_accounts:
                    current = number
                    number += 1
                    account = None
                elif accounts:
                    current = number
                    number += 1
                    account = accounts[current % len(accounts)]
                else:
                    return
            try:
                base = catalog.choose(current)
            except (OSError, ValueError, RuntimeError, urllib.error.HTTPError):
                with lock:
                    results["discovery_error"] += 1
                time.sleep(1)
                continue
            if account is None:
                username = "mmiv_ci_" + uuid.uuid4().hex
                password = secrets.token_urlsafe(24)
                path = catalog.path("/api/auth/signup")
            else:
                username, password = account
                path = catalog.path("/api/auth/login")
            began = time.monotonic()
            try:
                status, _ = send(
                    base,
                    path,
                    "POST",
                    {"username": username, "password": password},
                )
            except urllib.error.HTTPError as error:
                status = error.code
            except (OSError, ValueError):
                status = "network_error"
                catalog.refresh_after_failure()
            with lock:
                results[f"{'signup' if account is None else 'login'}_{status}"] += 1
                latencies.append((time.monotonic() - began) * 1000)
                if account is None and status == 201:
                    accounts.append((username, password))

    try:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(worker) for _ in range(args.workers)]
            for future in futures:
                future.result()
    finally:
        elapsed = time.monotonic() - started
        ordered = sorted(latencies)
        report = {
            "phase": "signup_and_login",
            "seconds": round(elapsed, 2),
            "requests": len(ordered),
            "status_counts": dict(results),
            "successful_per_second": round(
                (results["signup_201"] + results["login_200"]) / elapsed, 2
            ),
            "p95_ms": round(ordered[max(0, int(len(ordered) * 0.95) - 1)], 2)
            if ordered else None,
            "observed_instance_count": len(catalog.observed) if isinstance(catalog, EurekaTargets) else None,
        }
        print(json.dumps(report, sort_keys=True), flush=True)
        with ThreadPoolExecutor(max_workers=min(4, args.workers)) as pool:
            cleaned = list(pool.map(
                lambda item: cleanup_account(catalog, item[0], item[1]),
                enumerate(accounts),
            ))
        report["cleanup_succeeded"] = sum(cleaned)
        report["cleanup_failed"] = len(cleaned) - sum(cleaned)
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        print(f"cleanup_succeeded={sum(cleaned)} cleanup_failed={report['cleanup_failed']}")
    if (report["cleanup_failed"] or results["signup_201"] == 0 or
            results["signup_429"] or results["login_429"]):
        raise SystemExit("Load test failed, hit a rate limit, or left verification accounts to clean up")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eureka-url", default=DEFAULT_EUREKA)
    parser.add_argument("--gateway-url", help="HTTPS CloudFront base URL for the temporary no-rate-limit branch")
    parser.add_argument("--minutes", type=int, default=8)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--max-accounts", type=int, default=600)
    parser.add_argument("--report", type=Path, default=Path("user-access-signup-report.json"))
    args = parser.parse_args()
    if not (1 <= args.minutes <= 15 and 1 <= args.workers <= 32 and
            1 <= args.max_accounts <= 2000):
        parser.error("Load settings are outside supported bounds")
    if args.gateway_url:
        parsed = urllib.parse.urlsplit(args.gateway_url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or
                parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
            parser.error("--gateway-url must be an HTTPS origin without a path or credentials")
    run(args)


if __name__ == "__main__":
    main()
