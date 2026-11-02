# MMIV API and Event Contracts

MMIV services communicate through explicit HTTP interfaces and a versioned RabbitMQ event contract. The contracts are implemented and maintained alongside their owning services.

## HTTP APIs

Each service owns its public endpoints and validates authentication and authorization for operations it performs. The Gateway provides browser-facing routes; service-to-service clients use service APIs rather than shared internal Java models or direct cross-service database access.

Current communication boundaries include:

| Provider → consumer | Contract purpose |
| --- | --- |
| User Access → Discovery | Permission, profile, and folder checks |
| Discovery → Transfer | Plan allocation and resolution |
| Transfer → Discovery | Transfer execution response |
| Integrity → Transfer | Validation request and response |
| Discovery → Integrity | Authorized plan resolution |

See [service communication](../architecture/service-communication.md) for adapter boundaries and security behavior. Service-specific endpoint details are documented with the relevant service; the transfer API test contract is in [`tests/api/transfer-openapi.json`](../../tests/api/transfer-openapi.json).

## Event contract

Discovery, Transfer, and Integrity publish lifecycle events consumed by Reporting. The shared [v2 event guide](../../contracts/events/v2/README.md) documents event ownership, identifiers, delivery rules, and security constraints. The [JSON Schema](../../contracts/events/v2/mmiv-event.schema.json) and examples are the canonical machine-readable contract.

## Change management

Keep request/response formats, status codes, identifiers, and error behavior explicit. Update the owning service documentation and relevant contract tests when an interface changes. Incompatible event changes require a new schema version; do not make service-specific copies of the shared event schema.
