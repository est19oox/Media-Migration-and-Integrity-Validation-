# MMIV Architecture

> **Status: Draft**
>
> This document summarises the current MMIV architecture and the key decisions that still require team agreement.

## 1. Decisions Required

Before finalising cross-service APIs, the team needs to agree on:

1. What is the end-to-end Media Migration flow?
2. Which service calls which?
3. Does Discovery require User Access `READ` authorization?
4. Does Transfer require User Access `READ` / `WRITE` authorization?
5. What identifiers are shared between services?
6. Where does `userId` come from?
7. Who coordinates Transfer → Integrity → Reporting?
8. What are the respective roles of GitHub Actions and Jenkins?

## 2. Current Services

| Service | Port | Responsibility |
| --- | ---: | --- |
| Transfer | 8081 | Transfer media between storage locations |
| Discovery | 8085 | Discover media/resources |
| User Access | 8083 | Check user access permissions |
| Reporting | 8084 | Generate migration/validation reports |
| Integrity | 8082 | Validate migrated media integrity |

Each service is independently built and containerized.

The current team/service grouping is:

```text id="ruxmgu"
Discovery ────── User Access

Transfer ────── Integrity ────── Reporting
```

This grouping does **not** define the final runtime call flow.

## 3. Draft Domain Contexts

| Context | Responsibility |
| --- | --- |
| Discovery | Discover media resources from a source |
| Access Control | Decide whether a user can perform an action on a resource |
| Migration | Transfer media from source to destination |
| Integrity | Validate migrated media integrity |
| Reporting | Produce migration and validation reports |

Potential relationships that still require agreement:

```text id="hrmbku"
Discovery ── READ? ─────────► Access Control

Migration ── READ/WRITE? ───► Access Control

Migration ──────────────────► Integrity

Integrity ──────────────────► Reporting
```

These arrows represent relationships to discuss, **not final service call directions**.

## 4. Shared Language

The following terms should have consistent meanings across services:

| Term | Meaning |
| --- | --- |
| User / Subject | Identity performing an operation |
| Resource | Object protected by access control |
| Source | Location from which media is read |
| Destination | Location to which media is written |
| Media Asset | Media/file being discovered or migrated |
| READ | Read/access a resource |
| WRITE | Write to a resource |

## 5. API Principle

Services should communicate through explicit API contracts rather than depending on another service's internal Java classes.

Agreed cross-service contracts should define:

- endpoint and HTTP method;
- request and response;
- shared identifiers;
- HTTP status and error behaviour.

Confirmed contracts will be maintained under `docs/api/`.

Significant architecture decisions can be recorded under `docs/decisions/`.
