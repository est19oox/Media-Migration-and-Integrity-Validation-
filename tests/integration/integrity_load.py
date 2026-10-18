"""Repeatable Integrity validation benchmark, called by the disposable stack harness.

One client, identical payload/concurrency/request count in each phase. The second
phase starts a real JVM registered under the same Eureka service ID. This measures
local replica scaling, not separate-host availability or cloud S3 performance.
"""
import json
import math
import os
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def samples(url):
    with urllib.request.urlopen(url + '/actuator/prometheus', timeout=15) as response:
        text = response.read().decode()
    return sum(float(line.rsplit(' ', 1)[1]) for line in text.splitlines()
               if line.startswith('mmiv_integrity_validation_seconds_count{'))


def compare(request, gateway, token, payload, headers, primary, start_replica, artifacts):
    count = int(os.environ.get('MMIV_LOAD_REQUESTS', '60'))
    concurrency = int(os.environ.get('MMIV_LOAD_CONCURRENCY', '8'))
    repeats = int(os.environ.get('MMIV_LOAD_REPEATS', '3'))
    if not (10 <= count <= 10000 and 1 <= concurrency <= 64 and 1 <= repeats <= 10):
        raise ValueError('Load settings outside supported bounds')
    endpoints = [primary]
    rows = []
    def invoke(_):
        started = time.perf_counter()
        try:
            status, body = request(gateway + '/api/integrity/validate', 'POST', payload, token, headers)
            valid = status == 200 and json.loads(body).get('valid') is True
            return time.perf_counter() - started, valid, status
        except Exception as error:
            return time.perf_counter() - started, False, type(error).__name__
    for instances in (1, 2):
        if instances == 2:
            endpoints.append(start_replica())
            # Functional warm-up must prove Gateway discovery has picked up replica 2.
            deadline = time.monotonic() + 120
            baseline = samples(endpoints[1])
            while samples(endpoints[1]) == baseline:
                if time.monotonic() >= deadline:
                    raise AssertionError('Second Integrity replica received no validation requests')
                invoke(0)
                time.sleep(1)
        for _ in range(10):
            if not invoke(0)[1]:
                raise AssertionError('Load warm-up failed')
        for repeat in range(repeats):
            before = [samples(url) for url in endpoints]
            started = time.perf_counter()
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                results = list(pool.map(invoke, range(count)))
            elapsed = time.perf_counter() - started
            latencies = sorted(result[0] * 1000 for result in results)
            def percentile(p):
                return round(latencies[max(0, math.ceil(p * len(latencies)) - 1)], 2)
            row = {'instances': instances, 'repeat': repeat + 1, 'requests': count,
                   'concurrency': concurrency, 'seconds': round(elapsed, 3),
                   'successfulRequestsPerSecond': round(sum(r[1] for r in results) / elapsed, 2),
                   'errors': sum(not r[1] for r in results),
                   'p50Ms': percentile(.50), 'p95Ms': percentile(.95), 'p99Ms': percentile(.99),
                   'perInstanceValidations': [samples(url) - initial for url, initial in zip(endpoints, before)]}
            rows.append(row)
            print('LOAD ' + json.dumps(row), flush=True)
            (artifacts / 'integrity-load.json').write_text(json.dumps({
                'workload': 'Gateway -> load-balanced Integrity -> Metadata authorization + S3 fixture SHA-256 + audit + outbox',
                'limitations': 'Same host; local S3 fixture; fixed one-then-two order; no cloud latency. No throughput gain assumed.',
                'results': rows}, indent=2) + '\n')
            if row['errors'] or any(n <= 0 for n in row['perInstanceValidations']):
                raise AssertionError('Load test failed or a replica received no work')
