# Local development

[English](local-setup.md) | [简体中文](local-setup.zh-CN.md)

## Requirements

- JDK 25 and Maven 3.9.16. The repository Maven Wrapper is in
  `services/user-access-service/`.
- Node.js 22.12+ and npm for Vite and Prettier. Vite also supports Node.js
  20.19+.
- Docker Compose v2 when using local Oracle, RabbitMQ, MongoDB, or integrated
  runtime tests.
- Python 3 for selected CI and deployment scripts.

The seven Java services can be compiled and unit-tested independently. Running
the complete browser workflow requires the supporting services and runtime
configuration described below; starting the frontend alone does not start the
backend.

## Build and test a service

From the repository root, choose a service directory:

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

On Windows PowerShell:

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

Replace `transfer-service` with the directory to build. `clean verify` runs
that service's Maven lifecycle and tests. The repository CI helper iterates over
the Maven projects:

```sh
bash scripts/ci/run-tests.sh .
```

This helper requires Maven and Python 3 on `PATH`; use WSL or another POSIX
shell on Windows.

## Run the frontend

The frontend package is separate from the root formatting package:

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite serves the frontend on its default local development address and proxies
API requests to `http://localhost:8080` as configured in
`frontend/vite.config.js`. A reachable API Gateway and its dependencies are
needed for login, uploads, reports, and other API-backed flows.

The production frontend is copied into the API Gateway's
`META-INF/resources` during the Gateway Maven build. It does not run as a
separate production service.

## Supporting services and configuration

The local Oracle helper starts the database container only; it does not
configure every application schema or provision AWS resources:

```powershell
.\database\setup-db.ps1
```

```sh
bash database/setup-db.sh
```

See [database setup](../database/README.md) for the local connection details
and persistent-volume precautions. The helper starts an Oracle instance; it
does not create the application database users/schemas. The repository does
not provide a complete local Oracle user/schema provisioning workflow.
The integrated Compose definition is
`infrastructure/service-registry/compose.yml`. Before starting it, provision
the Oracle database users/schemas required by Discovery and User Access and
prepare these required Compose inputs:

| Variable                               | Purpose                                                                |
| -------------------------------------- | ---------------------------------------------------------------------- |
| `RABBITMQ_PASSWORD`                    | Local RabbitMQ service credentials                                     |
| `MONGODB_PASSWORD`                     | Local MongoDB root credential used by Reporting                        |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Discovery's Oracle JDBC connection                                     |
| `USER_ACCESS_ENV_FILE`                 | Private raw environment file for User Access database and JWT settings |
| `USER_ACCESS_JWT_DIRECTORY`            | Directory containing `private.pem` and `public.pem`, mounted read-only |

The `DB_URL` host must be reachable from the Discovery container. The Oracle
helper binds port 1521 to host loopback, and the integrated Compose file does
not include an Oracle container or configure host-gateway routing. Container-
to-host connectivity differs by Docker Engine and operating system; configure
and verify a supported route rather than assuming `localhost` works.

The User Access environment file must provide its Oracle settings and JWT
issuer/key ID. Its key paths must resolve to the mounted
`/run/mmiv-jwt/private.pem` and `/run/mmiv-jwt/public.pem` files. Compose can
also receive `AWS_REGION`, `MMIV_BUCKET_NAME`, `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `USER_ACCESS_JWT_ISSUER`,
`JWT_COOKIE_SECURE`, and `DISCOVERY_MEDIA_PATH`; use values appropriate to a
non-production environment. Never commit passwords, signing keys, or private
configuration files.

The `USER_ACCESS_ENV_FILE` must provide `ORACLE_JDBC_URL`, `ORACLE_USER`,
`ORACLE_PASSWORD`, `JWT_ISSUER`, and `JWT_KEY_ID`. Signing-key paths are set
by the deployment configuration and refer to the mounted PEM files above.
Those database values must describe an initialized User Access schema;
starting the local Oracle container does not create that schema.

Integrity uses in-memory H2 by default; its audit records do not survive a
restart. For Oracle-backed Integrity, activate the `oracle` profile and set
`INTEGRITY_DB_URL` and `INTEGRITY_DB_PASSWORD`; `INTEGRITY_DB_USERNAME`
defaults to `INTEGRITY_SERVICE`. The Oracle schema and audit table must
already exist because Hibernate validates rather than creates them in this
profile. The integrated Compose file leaves Integrity on its H2 default.

Build and test the service JARs before Docker builds the images:

```sh
bash scripts/ci/run-tests.sh .
```

Start Oracle first, then validate the Compose configuration without printing
its resolved secrets:

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml config --quiet
```

If validation succeeds, start the integrated stack:

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml up --build -d
```

Compose waits for RabbitMQ, MongoDB, and Registry health where configured;
backend services then register with Eureka. Gateway is started after the
backends, but its Compose dependency checks do not replace the deployment
workflow's full readiness barrier. Verify backend health, Eureka registration,
and Gateway routes before relying on API-backed browser flows. For a clean
application stop without deleting persistent volumes:

```sh
docker compose --env-file /absolute/path/to/mmiv-compose.env -f infrastructure/service-registry/compose.yml down
```

For deployment-side configuration and access behavior, see
[administrator access](../admin/administrator-access.md) and
[login and protected access](../deployment/login-access.md).

Transfer and Integrity require credentials and permissions for the configured
S3 bucket when performing actual storage operations. Application defaults are
not a substitute for AWS access. Use a non-production bucket for manual or
DAST uploads.

## Formatting

The root npm package installs the pinned Prettier version:

```sh
npm ci
npm run format:check
```

The repository formatting workflow also checks Maven Spotless and Terraform
formatting. On Windows, run the complete check with:

```powershell
.\scripts\format\check-all.ps1
```

See [testing and formatting](../testing/README.md) for CI checks and test
scope.
