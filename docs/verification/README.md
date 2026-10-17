# Verification — 17 October 2026

The final full-stack regression passed using real application JVMs and disposable
Oracle, MongoDB, RabbitMQ and Redis containers, plus a local S3 HTTP fixture.
See `summary.json`, `maven-tests.json` and `prometheus-query.json`.

Validated browser sessions/CSRF, invitations, folder permissions, collision-safe
ASCII filename suffixes, uploader-only deletion, administrator folder deletion,
report ownership, permission revocation, scoped verification cleanup, all-service
audit delivery, RabbitMQ outage recovery, password reset and token revocation,
account deactivation and generated-account cleanup. Oracle-specific User Access
and Metadata persistence tests also passed. Redis exhaustion returned 429 and
requests recovered after the counter was removed. Prometheus scraped all six
private backend targets; the public Gateway remains authenticated.

Additional passing checks: 113 frontend/controller tests, nine React pages at
390 px mobile width, 42 deployment lifecycle tests, six deployment configuration
tests, two real Docker/Ansible rollout/rollback tests, three frontend ownership
tests, seven CI/CD contract tests and nine endpoint-runner tests. Prettier validation
includes JSX; Java sources were formatted using the configured Spotless plugin.

The separate Integrity comparison completed 360 measured requests without errors
and distributed 89/91 requests across two JVMs. See `integrity-load-2026-10-16.json`
and `../architecture/scaling-and-metrics.md` for measurements and limitations.
The small throughput difference does not establish a meaningful performance gain.

This is local development evidence, not an AWS deployment or a production load
certification. No GitHub push was performed. React renders the shared pages while
preserving the team's existing JavaScript controllers; this is an incremental
migration rather than a fully React-state-driven SPA. Backend RestClient adapters
still use configured URLs except for the documented Eureka-aware clients.
