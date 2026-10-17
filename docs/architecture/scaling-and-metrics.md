# Integrity scaling and observability

The EC2 release manifest now includes `integrity-service` (8082) and
`integrity-service-2` (8086), using the same built image. Both register with Eureka as
`integrity-service`. The Gateway and Transfer's Feign client load-balance that logical
name. Deployment waits for both physical instances to be healthy and registered;
rollback tracks their containers independently. This is fixed horizontal replication,
not an autoscaler, and it requires enough EC2 memory for the additional JVM.
The current Terraform host is an 8 GiB t3.large. Configured container maxima total
approximately 7.4 GiB with the new components, leaving limited host headroom.
Measure RSS under load and review capacity before production deployment; this change
does not resize an existing instance.

Prometheus is configured by `scripts/cd/docker-stack-remote.py`. Its UI binds only to
host loopback port 9090. Use your existing authenticated host tunnel to access it.
It retains up to seven days / 512 MB of samples in `mmiv-prometheus-data`.
Backend JVMs expose `/actuator/prometheus` on their private ports. Gateway metrics
remain authenticated and are not anonymously scraped by this initial configuration.

Useful PromQL:

```promql
sum(up{service="integrity-service"})
sum by (job) (rate(mmiv_integrity_validation_seconds_count[5m]))
sum by (outcome) (rate(mmiv_integrity_validation_seconds_count[5m]))
sum(rate(mmiv_integrity_validation_seconds_sum[5m])) / sum(rate(mmiv_integrity_validation_seconds_count[5m]))
jvm_memory_used_bytes
process_cpu_usage
```

## Repeatable comparison

Build the frontend, then all seven service JARs with Java 25 and Maven:

```sh
(cd frontend && npm ci && npm run build)
for service in services/*; do
  [ ! -f "$service/pom.xml" ] || mvn -B -f "$service/pom.xml" verify
done
MMIV_LOAD_TEST=true python3 tests/integration/test-service-stack.py
```

The harness creates disposable Oracle, RabbitMQ, MongoDB and Redis containers and a
local HTTP S3 fixture. It runs real services, authenticates a test account, creates an
authorized metadata plan and validates a 4 MiB object through Gateway. It first measures
one Integrity JVM, then starts another real JVM and waits until it receives validations.
Each phase uses warm-up followed by three batches of 60 requests at concurrency eight.
Override `MMIV_LOAD_REQUESTS`, `MMIV_LOAD_CONCURRENCY`, `MMIV_LOAD_REPEATS`.

Results go to `.ci/reports/integration/integrity-load.json`: successful requests/sec,
p50/p95/p99 latency, errors and the measured validation-count delta for each instance.
Each phase must produce successful requests and send work to every available instance.
The fixture raises admission limits during capacity measurement so it measures services,
not the intentionally configured production throttle. Rate-limit behavior has separate tests.

All processes and disposable containers are cleaned in `finally`. The test uses no AWS
resources and does not create production accounts or S3 data. Results describe this
machine, shared dependencies and a local S3 fixture. They must not be represented as
production capacity or a guaranteed twofold speedup; one-then-two phase ordering can
also benefit the second phase through warm shared caches.

## Measured development run

The recorded comparison is in `docs/verification/integrity-load-2026-10-16.json`.
Both phases completed 180 measured requests without errors. The two-instance phase
distributed 89 validations to the first JVM and 91 to the second.

| Integrity instances | Measured requests | Errors | Aggregate successful requests/sec | Batch p95 range |
| ------------------- | ----------------- | ------ | --------------------------------- | --------------- |
| 1                   | 180               | 0      | 12.77                             | 628–1212 ms     |
| 2                   | 180               | 0      | 13.08                             | 686–1154 ms     |

The approximately 2.4% throughput difference is smaller than the batch variation.
This demonstrates real instance replication and traffic distribution, not a statistically
proven throughput gain or high availability. Both JVMs share the same host and dependencies.

To additionally test live Prometheus scraping, set `MMIV_PROMETHEUS_BINARY` to a local
Prometheus executable when running the integration harness. It starts a temporary
Prometheus process, waits for all six private backend targets to report `up=1`, writes
`prometheus-query.json`, and stops the process with the rest of the fixture.
