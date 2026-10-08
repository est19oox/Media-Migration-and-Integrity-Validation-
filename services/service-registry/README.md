# MMIV Service Registry

A standalone Eureka server in `services/service-registry`, on port 8761.
Transfer registers as `transfer-service` (8081), and Integrity registers as
`integrity-service` (8082). The existing `services/discovery-service` is untouched
and is not used by these clients. Java 25 and Spring Cloud 2025.0.3 match the
existing Java 25 / Spring Boot 3.5.14 project.

## Run locally with Java

Build each service with `mvn -B clean verify`, starting from its own folder:

```bash
mvn -B -f services/service-registry/pom.xml clean verify
mvn -B -f services/transfer-service/pom.xml clean verify
mvn -B -f services/integrity-service/pom.xml clean verify
```

Then run each JAR in a separate terminal, starting the registry first:

```bash
java -jar services/service-registry/target/service-registry.jar
java -jar services/transfer-service/target/transfer-service.jar
java -jar services/integrity-service/target/integrity-service.jar
```

Clients default to `http://localhost:8761/eureka/`. For another registry host,
set `EUREKA_DEFAULT_ZONE` before starting a client. Configure the existing
AWS region, bucket, and AWS credentials for actual Transfer S3 uploads.

Open `http://localhost:8761/` to see the registry dashboard. Eureka normally
shows uppercase names `TRANSFER-SERVICE` and `INTEGRITY-SERVICE`.
Registration is asynchronous; allow time for startup, heartbeats and caches.
Verify registration with:

```bash
python3 infrastructure/service-registry/verify-registration.py
```

Or query `GET http://localhost:8761/eureka/apps` with `Accept: application/json`.
Check that each application has at least one instance whose status is `UP`.

## Run all three using Docker Compose

Build the three JARs above first, then run:

```bash
docker compose -f infrastructure/service-registry/compose.yml up --build -d
python3 infrastructure/service-registry/verify-registration.py
```

This is a separate Compose setup; it does not use the existing root Compose file.
The existing root setup maps another container onto 8081, so stop that container
before running this setup. The new clients use a shared Compose network and
`http://service-registry:8761/eureka/`. Compose waits for registry health before
starting the clients. Registry host port 8761 binds to localhost; 8081 and 8082
expose the two client services. The registry can reach their advertised container
IPs on the shared network.

For local S3 uploads, export `AWS_REGION`, `MMIV_BUCKET_NAME`,
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN` when
using temporary AWS credentials before starting Compose. Do not put credentials
in source control. Stop this setup using:

```bash
docker compose -f infrastructure/service-registry/compose.yml down
```

## Existing CI/CD and Ansible

The existing `cicd.yml` deploy matrix now includes registry (8761), Transfer
(8081), and Integrity (8082). Terraform still precedes the deployment job.
All use the existing build, image, Ansible, smoke, DAST and rollback steps.

The Ansible role ensures the `mmiv-backend` Docker network exists independently
of Redis, attaches each service, and passes `EUREKA_DEFAULT_ZONE`. The default
is `http://service-registry:8761/eureka/`. Both normal deployment and rollback
preserve this network and configuration. All three containers must run on the
same EC2 instance for this default address to work. If running on different
hosts, supply a reachable private registry URL and routable advertised client
addresses instead of container-local IPs.

The matrix limits concurrency to one but does not guarantee execution order.
Clients automatically retry registration if the registry is not yet available.
On a first deployment, verify both entries after all three matrix runs complete.
Actuator health/smoke success alone does not prove Eureka registration.

Registry port 8761 is published only to EC2 loopback; containers access it over
the shared network. View its dashboard remotely through an SSM or SSH port
forward. No new public Terraform ingress rule is needed for registry access.

## Scope

Registration and fetching registry entries are enabled. The existing Feign
Integrity API integration remains commented because the Integrity service in
this project does not yet implement that API. Registration does not itself
cause Transfer to call Integrity or perform integrity validation.

The existing Integrity application is a placeholder with health endpoints;
it can register normally. Its validation logic remains the Integrity team's work.
