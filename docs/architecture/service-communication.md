# Service communication and context boundaries

## Ownership

User Access owns accounts, roles, grants, invitations and password reset policy.
Metadata (the `discovery-service` deployment) owns transfer plans, destination assignment,
name reservations and verification-run tombstones. Transfer owns storage execution,
completion and compensating cleanup. Integrity owns checksum comparison and its audit
record. Reporting owns event projections and report visibility. Gateway owns browser
cookies, CSRF, routing and request admission. Infrastructure components are not domain aggregates.

Do not move authorization enforcement out of a service that performs an operation:
Transfer validates its JWT and checks the current grant before reading or mutating storage.
The permission decision is obtained from User Access through the appropriate adapter.
The frontend's hidden buttons are convenience, not security enforcement.

## Context map

| Provider → consumer    | Published interface                        | Consumer boundary                                                                                                                        |
| ---------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| User Access → Metadata | Access-check/profile/folder HTTP contracts | `PermissionClient`: HTTP adapter; uses the published permission vocabulary                                                               |
| Metadata → Transfer    | Plan allocation/resolution contracts       | `MetadataClient` and `MetadataTransferFacade`: validate and adapt plans before storage execution                                         |
| Transfer → Metadata    | Transfer execution response                | `TransferClient`: HTTP adapter; currently forwards its public response rather than pretending to be a full ACL                           |
| Integrity → Transfer   | Validation HTTP request/response schema    | `IntegrityValidationGateway`: lightweight ACL; validates identity and semantic consistency and maps to Transfer-owned validation outcome |
| Metadata → Integrity   | Authorized plan resolution                 | `IntegrityMetadataGuard`: verifies object and plan identity before validation                                                            |
| Producers → Reporting  | Versioned RabbitMQ event schema            | Contract validation and projector translate events into `ReportChange`; the report aggregate applies state transitions                   |

The public contracts are Open Host Service / Published Language boundaries when they
are maintained as documented, stable interfaces. A REST client alone is not an ACL.
There is no shared domain model or direct cross-service database access for normal business calls.
The common library contains transport/infrastructure support, not shared aggregates.

## HTTP rules

- GET reads resources; it must not grant access, delete data or initiate a transfer.
- POST creates resources or executes explicit commands (login, validation, transfer).
  Command endpoints such as `/plans/{id}/transfer` are pragmatic HTTP APIs, not a claim
  that every existing path is a perfectly resource-oriented REST design.
- PUT grants/replaces a destination permission; DELETE revokes access or removes a resource.
- Authentication failures use 401; authorization failures use 403. Missing resources use
  404 where appropriate. User deletion is deactivation; records remain for audit.
- No automatic retry of non-idempotent writes. A connection failure can occur after a
  remote commit; blindly replaying creation or deletion could duplicate effects.
- JWTs travel in Authorization headers internally. Browser JWTs remain in HttpOnly
  cookies. Gateway checks CSRF and relays a verified token.
- Circuit breakers protect dependency availability. Business rejections are not evidence
  that a dependency is down. HTTP client deadlines bound individual calls.

Gateway routes the legacy account-management URLs directly to User Access. Transfer
retains storage-facing folder operations because they coordinate S3 changes as well as grants.

## Discovery and scaling

Gateway routes and Transfer's Integrity Feign client use Eureka plus Spring Cloud
LoadBalancer. Other RestClient adapters still use configured URLs: Eureka registration
alone does not enable load balancing for them. Integrity replicas register under the same
logical name and have different ports/instance IDs and separate durable event outboxes.
Both use the same release image. Two containers on one host are not host-level high availability.

## Rate limiting

Gateway uses atomic Redis counters with 60-second expiry, shared across Gateway instances.
Defaults: 10 authentication requests per IP per window, 30 upload requests per user,
and 120 other API requests per user. Configure `MMIV_RATE_LIMIT_AUTH_PER_MINUTE`,
`MMIV_RATE_LIMIT_UPLOADS_PER_MINUTE`, and `MMIV_RATE_LIMIT_API_PER_MINUTE`.
Authentication endpoints share one IP bucket; clients behind NAT share that allowance.
It uses the socket address, not arbitrary X-Forwarded-For headers. A reverse proxy needs
an explicit trusted-forwarding deployment design before using forwarded client addresses.
Fixed windows permit boundary bursts; these are not concurrency limits or byte quotas.

Exhaustion returns 429, `Retry-After: 60` and remaining-limit headers. Redis errors/timeouts
return 503 with `Retry-After: 3`, without invoking a backend. Static pages and health probes
remain available. Redis is private to the Docker backend network and has no published port.
Counters are ephemeral; a Redis restart resets windows. No JWT or plaintext user ID is stored
in a counter key. Tests can disable the filter; production defaults to enabled.
