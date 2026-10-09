# Integrity Service

Build: `mvn -B clean verify`

Run: `mvn spring-boot:run`

Health: `GET /actuator/health` on port 8082.


## Optional database configuration

No GitHub database secrets are required by default. Embedded H2 creates the audit schema and table on startup; records are lost on restart. For durable Oracle audit storage, supply both INTEGRITY_DB_URL and INTEGRITY_DB_PASSWORD in the deployment workflow (username defaults to INTEGRITY_SERVICE). Direct runs also need SPRING_PROFILES_ACTIVE=oracle. The Oracle schema and table must be prepared in advance.
