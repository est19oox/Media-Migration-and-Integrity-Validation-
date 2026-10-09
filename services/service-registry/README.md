# MMIV Service Registry

The Eureka server is `services/service-registry`, on port 8761. It does not
register itself. Gateway and all five backend services register automatically.
Registration, Maven dependencies, gateway routes and deployment entries are active.

| Service | Port | Role |
| --- | --- | --- |
| service-registry | 8761 | Standalone Eureka server |
| api-gateway | 8080 | Gateway and LoadBalancer; Eureka client |
| transfer-service | 8081 | Eureka client |
| discovery-service | 8085 | Eureka client; separate from registry server |
| user-access-service | 8083 | Eureka client |
| reporting-service | 8084 | Eureka client |
| integrity-service | 8082 | Eureka client |

Each client has an active Spring Cloud 2025.1.3 BOM and Eureka client dependency,
its own spring.application.name and server.port, and active Eureka configuration.
No registration controller is required. Instance IDs include IP and port to
support distinct replicas. All clients fetch the registry and send heartbeats.

## Build and start

From the project root, with Java 25 and Maven:

```bash
for service in service-registry api-gateway transfer-service discovery-service user-access-service reporting-service integrity-service; do
  mvn -B -f "services/$service/pom.xml" clean verify || exit 1
done
docker compose -f infrastructure/service-registry/compose.yml up --build -d
python3 infrastructure/service-registry/verify-registration.py
```

The verification script waits for all six clients to appear UP. Open
http://localhost:8761/ to inspect Eureka; http://localhost:8080/upload.html
uses Gateway. Registration is asynchronous. Health alone does not prove that
all clients have registered.

Local Java processes default to http://localhost:8761/eureka/. Docker and
Ansible use http://service-registry:8761/eureka/ on a shared Docker network.
Set EUREKA_DEFAULT_ZONE for a different registry location. The EC2 defaults
assume all containers run on one instance. Normal deployment and rollback
preserve registry configuration independently of Redis.

Compose maps host and container ports identically for all five backend services.
The scaled Compose file publishes only Gateway and loopback registry; backend
replicas keep their service ports on different container IPs. Use the gateway
README for replica testing and routes. AWS credentials and bucket configuration
are needed for actual S3 transfers.

The existing CI/CD matrix now includes all seven services with active entries.
Terraform still precedes deployment. Matrix ordering is not guaranteed; clients
retry registration while the registry starts. Business endpoints in placeholder
services still need their own implementation. Registering a service does not
implement those endpoints.

Unit/context tests disable external Eureka connections only in test properties.
Production registration remains enabled. Registry port 8761 binds to host
loopback; access it remotely using authorized SSM or SSH forwarding.
