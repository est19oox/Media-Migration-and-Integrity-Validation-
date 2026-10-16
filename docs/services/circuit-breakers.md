# Service circuit breakers

[English](circuit-breakers.md) | [简体中文](circuit-breakers.zh-CN.md)

Internal HTTP clients use Resilience4j core 2.3.0, without Spring AOP or a Boot-specific starter. The shared implementation is `common/src/main/java/mmiv/resilience/DependencyCircuits.java`. Each running process keeps a separate breaker for each dependency and configured endpoint; Metadata and User Access failures do not share a breaker. The Transfer Integrity Feign client uses the same policy with its Eureka service identity.

## Covered calls

- Transfer → Metadata: assignments, allocations, ownership and verification cleanup.
- Transfer → User Access: current account, folder, permission and administrator checks.
- Transfer → Integrity: integrity validation, inside the existing bounded retry workflow.
- Transfer → Reporting: detailed administrator logs and personal history.
- Metadata → User Access, Transfer and Reporting, including verification cleanup.
- Integrity → Metadata: validate the caller and exact assigned object.
- Reporting → Metadata and User Access: report access, administrator logs, personal history and cleanup authorization.

Gateway routing retains its existing connection/response timeouts. RabbitMQ keeps its durable outbox, publisher confirms and consumer retry policies. S3 keeps its SDK handling and guarded integrity-failure cleanup policy.

## Policy and recovery

The breaker observes the last 10 completed availability calls. After at least 5 calls, a failure rate of 50% or greater opens it. It blocks calls for 30 seconds, then permits 2 recovery probes. Two successful probes close it; failed recovery opens it again. Timing starts after the circuit opens. The slow-call threshold is 10 minutes, above the existing client deadlines, so a healthy large transfer is not classified as an outage merely for taking time. State is local to each process and resets when that process restarts. Fixed policy values are defined in the shared class.

HTTP connection errors, read timeouts (including body reads), 5xx responses, 408 and 429 count as availability failures. Ordinary 400/401/403/404 responses are excluded from availability metrics. Checksum mismatch is a completed integrity result, not a dependency outage. Circuit transitions are logged using only the fixed dependency label and transition, without tokens, user data or URLs.

An open REST circuit raises the existing service-unavailable path (HTTP 503). Authorization remains mandatory: no permission is granted when a dependency is unavailable. An open Integrity circuit returns validation `UNAVAILABLE` and immediately stops further validation attempts for that operation; Transfer retains the existing policy of guarded cleanup when integrity cannot be confirmed.

The breaker never replays a mutation. Existing bounded integrity checks can retry; browser uploads, allocation requests and transfer-start requests are not automatically repeated by this layer. Circuit protection does not introduce asynchronous cancellation: a caller timeout can still occur after a remote operation has started. Check upload results before retrying, because uploads currently have no general idempotency key. Existing timeouts remain configured on each client.

## PWA status

PWA registration is disabled; retained manifest and service-worker assets are not part of the deployed application. Phone-responsive browsing remains available through the normal website.
