# MMIV API Gateway and LoadBalancer

API Gateway listens on 8080. It uses Eureka service-registry on 8761 and Spring
Cloud LoadBalancer to select registered backend instances with default round
robin. It is a software load balancer inside Gateway, not an AWS ALB.

Transfer (8081), User Access (8083), Reporting (8084), and Integrity (8082)
register with Eureka. Main's real Discovery Service (8085) is preserved but
requires Oracle configuration and has no Eureka client yet. Its route is reserved.

## Routes

| Gateway path                                 | Backend             | Forwarded path      |
| -------------------------------------------- | ------------------- | ------------------- |
| /api/v1/transfers and /api/v1/transfers/\*\* | transfer-service    | Unchanged           |
| /api/v1/files and /api/v1/files/\*\*         | transfer-service    | Unchanged           |
| /upload.html, /files.html                    | transfer-service    | Unchanged           |
| /                                            | transfer-service    | /upload.html        |
| /discovery/\*\*                              | discovery-service   | Remove /discovery   |
| /user-access/\*\*                            | user-access-service | Remove /user-access |
| /api/access and /api/access/\*\*             | user-access-service | Unchanged           |
| /reporting/\*\*                              | reporting-service   | Remove /reporting   |
| /integrity/\*\*                              | integrity-service   | Remove /integrity   |
| /actuator/health                             | Gateway itself      | Local endpoint      |

Examples:

- POST http://localhost:8080/api/v1/transfers/folder uses the existing multipart folder API.
- POST http://localhost:8080/api/access/check uses the existing User Access request DTO.
- /discovery/\*\* is reserved until Discovery registration is implemented.
- GET http://localhost:8080/user-access/actuator/health checks User Access.
- GET http://localhost:8080/reporting/actuator/health checks Reporting.
- GET http://localhost:8080/integrity/actuator/health checks Integrity.

Open http://localhost:8080/upload.html for file/folder uploads. Its relative
requests and links remain on the gateway origin. Business endpoints that do not
yet exist still return 404; these routes do not implement validation/reporting
logic or enable the reserved Transfer Feign integration.

## Build and run

Use Java 25 and Maven. See ../service-registry/README.md for the seven-service
build and Compose commands. For local Java processes, start the registry JAR
first, then each backend JAR and Gateway JAR. The default registry URL is
http://localhost:8761/eureka/; override EUREKA_DEFAULT_ZONE when needed.

The active Compose file exposes Gateway on 8080; backend and registry ports
bind to localhost. Discovery is available with the optional discovery profile.
Stop any conflicting root Compose setup before starting it. Actual S3 uploads
still require AWS credentials, region and bucket configuration.

## Test multiple replicas

After building every JAR, stop the single-instance setup and start two Transfer
and Integrity replicas:

```bash
docker compose -f infrastructure/service-registry/compose.yml down
docker compose -f infrastructure/service-registry/compose-scaled.yml up --build -d --scale transfer-service=2 --scale integrity-service=2
python3 infrastructure/service-registry/verify-registration.py
```

The scaled file exposes only Gateway and loopback registry. Replicas share
service names and internal ports but have distinct IPs and Eureka instance IDs.
Check multiple UP entries at http://localhost:8761/. One instance has no workload
to distribute; multiple instances receive requests by round robin. A whole folder
request stays on one selected backend. All Transfer replicas must use the same
S3 bucket and credentials with suitable access. The legacy server-side source
path API also requires consistent media mounts across replicas; Compose is set
up for local/browser multipart uploads.

## Failure behavior and deployment

Gateway connect timeout is 3 seconds; response timeout defaults to 300 seconds,
configurable with GATEWAY_RESPONSE_TIMEOUT. Uploads are streamed. RequestSize
rejects declared Content-Length above 101 MiB, while Transfer validates actual
multipart and file sizes, including chunked requests. No automatic POST retry
is added because it can duplicate S3 objects. No discovered backend yields 503;
allow time for registration and Eureka cache updates after startup or shutdown.

Authentication, rate limiting and cross-origin frontend CORS policies are not
configured here. The provided UI uses the same origin.

The CI/CD workflow enables six services; Discovery deployment is commented out
pending database and media configuration. These services run on one EC2 instance with the
shared Ansible network. Deploy/rollback use the Eureka URL. Terraform precedes
deployment; matrix ordering is not guaranteed and clients retry registration.
CI/CD deploys one container per service; scaled Compose is a replica example,
not automatic EC2 scaling. Gateway health alone does not prove every route works.
Terraform allows public TCP 8080 and Ansible binds Gateway to 0.0.0.0.
Open http://<EC2-public-IP>:8080/upload.html after deployment.
See ../../docs/ci-cd/gateway-access.md for direct and optional SSM access.

GatewayRoutingTest uses two local HTTP backends and static discovery to verify
round robin, multipart forwarding, all backend routes, pages and gateway health.
Its static discovery replaces external Eureka only during tests.

This merged project is complete. Keep FolderUploadService under src/main.
