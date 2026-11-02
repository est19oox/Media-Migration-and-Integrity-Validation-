# MMIV API Contracts

> **Status: Draft — Team Discussion Required**
>
> This directory is used to document API contracts between MMIV microservices.

## 1. Decisions Required

Before defining a cross-service API, the relevant service owners should agree on:

1. Who calls the API?
2. What is the endpoint and HTTP method?
3. What identifiers are shared between services?
4. What does the request contain?
5. What does the response contain?
6. What HTTP status codes and errors are expected?

## 2. API Contract Principle

Microservices should communicate through explicit API contracts.

A service should not depend directly on another service's internal Java classes or implementation details.

Each agreed contract should define:

- endpoint and HTTP method;
- request format;
- response format;
- shared identifiers;
- allowed values;
- HTTP status codes;
- error behaviour.

## 3. Contracts to Discuss

The following cross-service relationships currently require discussion:

- Discovery ↔ User Access
- Transfer ↔ User Access
- Transfer ↔ Integrity
- Integrity ↔ Reporting

The exact call direction and API contract for these relationships have not yet been finalized.

## 4. Contract Changes

Changes to a shared API should be reviewed by the owners of all affected services.

Breaking changes should not be merged until the affected services have agreed on the updated contract.

## 5. Next Step

After the architecture flow is agreed, each confirmed cross-service API can be documented in this directory using OpenAPI or concise Markdown specifications.

Architecture discussions are maintained under:

`docs/architecture/`

Confirmed architecture decisions are maintained under:

`docs/decisions/`
