# User Access Service

Internal guide for MMIV developers working with authentication and destination
authorization.

## 1. What This Service Does

- Checks usernames and passwords using BCrypt.
- Issues signed RS256 JWTs and publishes the verification key through JWKS.
- Stores users and destination-specific READ/WRITE assignments in Oracle.
- Checks current destination permissions and denies access to inactive users.
- Loads assignments from the repository for each access request, so permission
  changes and revocations take effect on the next request.

## 2. How Authentication Works

1. A user sends a username and password to the login API.
2. The service looks up the user, checks the BCrypt password hash, and rejects
   unknown, inactive, or invalid accounts.
3. On success, the service issues an RS256-signed JWT. The token's `sub` claim is
   the user's ID; tokens are valid for 30 minutes.
4. Other services can retrieve the public signing key from the JWKS endpoint and
   use it to validate tokens.
5. A service making an access decision uses JWT `sub` as the user identity.

JWTs may contain identity-level permissions, but do not contain destination
assignments. Current destination permissions come from `UserRepository`.

## 3. Available APIs

All paths below are direct service paths. See [How Other Services Integrate](#5-how-other-services-integrate)
for their Gateway paths.

### `POST /api/auth/login`

Request:

```json
{
  "username": "alice",
  "password": "your-local-password"
}
```

Success (`200`):

```json
{
  "accessToken": "<signed-jwt>",
  "tokenType": "Bearer"
}
```

Missing, blank, or invalid request fields return `400`. Invalid credentials,
unknown users, and inactive users return `401` with:

```json
{
  "error": "Invalid username or password"
}
```

### `GET /.well-known/jwks.json`

Returns `200` with a JSON Web Key Set containing the public RSA signing key. Key
values and the key ID come from the running service; private key material is not
published.

```json
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "<configured-key-id>",
      "use": "sig",
      "alg": "RS256",
      "n": "<public-modulus>",
      "e": "<public-exponent>"
    }
  ]
}
```

### `POST /api/access/check`

Requires a valid bearer JWT. The request `userId` must exactly match the JWT
subject; a mismatch or blank subject returns `403`. Missing, invalid, or expired
JWTs return `401`.

Request:

```json
{
  "userId": "U001",
  "destination": "project-A",
  "action": "WRITE"
}
```

Response (`200`) for an allowed operation:

```json
{
  "authorised": true,
  "reason": "Access granted"
}
```

Access denials also return `200`, with `authorised: false` and a reason such as
`User not found`, `User account is inactive`, `No access to destination`, or
`Insufficient permission`. A non-blank but unsupported action returns `200`
with `reason: "Invalid action"`. Invalid/missing required request fields return
`400`. Unexpected server or repository errors return a 5xx response.

### `GET /api/access/destinations`

Requires a valid bearer JWT. The authenticated JWT `sub` selects the user; no
request body or user ID parameter is used. Missing, invalid, or expired JWTs
return `401`. A blank subject, unknown user, or inactive user returns `403`.
Repository failures return a sanitized 5xx response.

Success (`200`):

```json
{
  "destinations": [
    {
      "destination": "project-A",
      "permission": "WRITE"
    },
    {
      "destination": "project-B",
      "permission": "READ"
    }
  ]
}
```

The list is sorted by destination identifier. An active user with no assignments
gets `"destinations": []`.

### `GET /api/health`

Returns `200` with the plain-text body:

```text
User Access Service is running
```

### `GET /actuator/health`

Returns the Spring Boot health response. A healthy service returns `200` with
`{"status":"UP"}`. This endpoint is allowed without a bearer JWT.

## 4. How Folder Permissions Work

- `READ` permits reads.
- `WRITE` permits reads and writes.
- No destination assignment means no access.
- Inactive users are denied.
- Global JWT permissions do not grant access to any destination.
- The JWT `sub` is the authoritative user ID; caller-supplied identity is not
  accepted by the destinations-list API and must match the subject for access
  checks.
- Destination assignments are loaded from the repository for each request.
  Revocations and permission changes therefore apply on the next request.

Use `GET /api/access/destinations` to display the folders assigned to a user.
Before a sensitive operation, use `POST /api/access/check` to enforce current
authorization. A destination list shown in a frontend is not proof of access.

## 5. How Other Services Integrate

The contract is:

- **Gateway** routes requests to User Access Service. It does not make the
  destination authorization decision.
- **Transfer** (or another service performing a protected operation) must
  validate the bearer JWT and check the current user, destination, and action
  before the operation. Do not rely on frontend visibility.
- **Discovery** or the service that owns folder inventory returns the actual
  folders it knows about. It can combine that inventory with the destination
  assignments from User Access Service where appropriate.
- Other services must validate JWTs using the published public key and use the
  subject as the identity. Coordinate any integration work with those service
  owners.

The current Gateway configuration has two relevant route patterns:

| Use | Gateway URL | Path forwarded to User Access Service |
| --- | --- | --- |
| Login | `POST /user-access/api/auth/login` | `POST /api/auth/login` |
| JWKS | `GET /user-access/.well-known/jwks.json` | `GET /.well-known/jwks.json` |
| Health | `GET /user-access/actuator/health` | `GET /actuator/health` |
| API health | `GET /user-access/api/health` | `GET /api/health` |
| Access check | `POST /api/access/check` or `POST /user-access/api/access/check` | `POST /api/access/check` |
| Destination list | `GET /api/access/destinations` or `GET /user-access/api/access/destinations` | `GET /api/access/destinations` |

The `/api/access/**` route preserves its path. The `/user-access/**` route strips
the `/user-access` prefix. These are Gateway routes, not proof that the other
services have integrated JWT validation or authorization.

## 6. Local Development

- The service listens on port `8083` by default (`SERVER_PORT` can override it).
- Oracle defaults to `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` and user
  `USER_ACCESS`. Run Oracle Database Free with the `FREEPDB1` pluggable database
  and configure `ORACLE_PASSWORD`. On normal (non-test) startup, the service runs
  the bundled idempotent schema blocks from `database/migrations` before it is
  ready.
- Configure these environment variables for a normal local run:
  - `ORACLE_PASSWORD` — local database password; required because no default is
    configured.
  - `JWT_PRIVATE_KEY_PATH` — path to a local PKCS#8 RSA private PEM.
  - `JWT_PUBLIC_KEY_PATH` — path to the matching X.509 RSA public PEM.
  - Optional: `ORACLE_JDBC_URL`, `ORACLE_USER`, `JWT_ISSUER`, `JWT_KEY_ID`,
    `SERVER_PORT`, `EUREKA_CLIENT_ENABLED`, and `EUREKA_DEFAULT_ZONE`.
- Keep keys, passwords, and `.env` files out of Git. The default local issuer is
  `https://auth.mmiv.example`; the default key ID is `mmiv-user-access-key-1`.
- `JwtConfig` loads key paths as Spring `Resource` values. This checkout's
  local development keys are expected at
  `services/user-access-service/.local/keys/jwt-private.pem` and
  `services/user-access-service/.local/keys/jwt-public.pem`; `.local/` is ignored
  by Git, so provide your own local keys if these files are absent. For absolute
  Windows paths, include the `file:` prefix and use forward slashes, for example
  `file:C:/Users/<you>/mmiv-keys/jwt-private.pem`. Set the public-key variable
  the same way with the matching public PEM path.
- Eureka can be disabled for local work with `EUREKA_CLIENT_ENABLED=false`.

From this directory, use the Maven wrapper:

```powershell
.\mvnw.cmd spring-boot:run
```

## 7. Testing

Run formatting and the regular test/build suite:

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd clean verify
```

Run the destination API security tests on their own:

```powershell
.\mvnw.cmd "-Dtest=AccessibleDestinationsSecurityIntegrationTest" test
```

These destination tests use generated RSA keys and the in-memory repository,
with the real Spring Security filters enabled.

Maven Surefire runs the regular `*Test` suite. Oracle-backed tests use the `*IT`
naming pattern and run through the `oracle-it` Maven profile:

```powershell
.\mvnw.cmd -Poracle-it verify
```

That profile requires a live, authorized Oracle database. Do not treat a passing
default `clean verify` as proof that Oracle integration tests ran.

## 8. Current Limitations / Team Follow-ups

- This service returns authorization assignments, not a physical folder
  inventory. The appropriate folder-owning service remains responsible for the
  actual folder list.
- Other services must enforce current repository-backed permissions before
  sensitive operations; the frontend destination list is for visibility only.
- Live Oracle-backed integration verification is pending unless separately run
  against an authorized database.
- Cross-service JWT validation and authorization require coordination with the
  Gateway, Transfer, Discovery, and other service owners.
