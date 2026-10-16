# MMIV API Gateway

API Gateway is the only public application entry point. It serves the browser
frontend, authenticates both Bearer JWTs and the browser's `MMIV_SESSION`
cookie, enforces CSRF for cookie-authenticated mutations, and relays a verified
Bearer token to backend services. Backends remain independently responsible
for authorization of their own data.

The Gateway listens on port 8080 and uses Eureka plus Spring Cloud LoadBalancer
for explicit backend routes. It is an application load balancer, not an AWS
ALB. The integrated Compose stack binds every other service port to
`127.0.0.1`; only Gateway is reachable on all host interfaces.

## Browser authentication

1. The public login page calls `POST /user-access/api/auth/login`.
2. The browser exchanges the returned Bearer token at `POST /api/v1/session`.
3. Gateway stores the JWT in a short-lived, HttpOnly, SameSite session cookie.
4. `GET /api/v1/session` returns the current identity and CSRF token without
   exposing the JWT.
5. Frontend requests use the same Gateway origin. Gateway validates the cookie,
   removes it before proxying, and sends a verified Bearer header downstream.

`JWT_COOKIE_SECURE=false` is required by the current local and AWS development
HTTP endpoints. Set it to `true` only after the Gateway is placed behind HTTPS.
Gateway also requires `USER_ACCESS_JWT_ISSUER` and
`USER_ACCESS_JWK_SET_URI`; it never signs tokens itself.

## Main routes

| Gateway path                                                                 | Owner       | Notes                                           |
| ---------------------------------------------------------------------------- | ----------- | ----------------------------------------------- |
| `/`, `/pages/**`, `/css/**`, `/js/**`, `/assets/**`                          | API Gateway | Frontend packaged in the Gateway image          |
| `/api/v1/session`                                                            | API Gateway | Browser session and CSRF lifecycle              |
| `/user-access/**`, `/api/access/**`                                          | User Access | Login/account and permission APIs               |
| `/api/v1/transfers/**`, `/api/v1/files/**`, `/api/v1/portal/**`              | Transfer    | Migration/file operations                       |
| `/api/metadata/**`, `/api/discovery/**`, `/discovery/**`                     | Discovery   | Metadata and discovery operations               |
| `/api/integrity/**`, `/integrity/**`                                         | Integrity   | Integrity validation                            |
| `/api/reports/**`, `/api/admin/reports/**`, `/api/history`, `/api/dashboard` | Reporting   | Owner and administrator report queries          |
| `/reporting/**`                                                              | Reporting   | Compatibility prefix with `/reporting` stripped |

Unknown routes are not dynamically exposed. A missing discovered backend gives
a clean 503 after Eureka observes the outage. Gateway does not retry mutation
requests because a retry could duplicate an upload.

## Build and test

Use Java 25 and the repository Maven wrapper from the repository root:

```sh
services/user-access-service/mvnw -B -f services/api-gateway/pom.xml clean verify
```

The Maven build tests the frontend and copies its production resources into
`META-INF/resources`; there is no separate frontend container. See
[`../../docs/integration/services.md`](../../docs/integration/services.md) for
the complete local stack command and required private environment files.

## Runtime and outage acceptance

The active local stack is `infrastructure/service-registry/compose.yml`. The
scaled file demonstrates multiple Transfer and Integrity replicas but does not
change route ownership. AWS CD builds and deploys a complete immutable
seven-service application release; RabbitMQ and MongoDB are supporting
containers.

After the integrated stack is healthy, verify that reports do not depend on
Transfer:

```sh
bash tests/api/verify-reporting-with-transfer-down.sh
```

The test creates a temporary user and owned report fixture, establishes a real
Gateway cookie session, stops only Transfer, checks the reports page and all
owner report APIs, confirms the file route returns 503, restarts Transfer, and
removes its temporary account/report data. Tokens and cookies are kept only in
a restricted temporary directory.

Deployment smoke/DAST reachability uses `/pages/login.html`, not the removed
Transfer-owned `/upload.html`. Authenticated endpoint verification also covers
the report list through Gateway.
