# Discovery Service

Build: `mvn -B clean verify`

Run: `mvn spring-boot:run`

Health: `GET /actuator/health` on port 8085.

Ports: the service listens on 8085 by default; override it with `SERVER_PORT`.
Through the API Gateway the service is reachable under `/discovery/**`.
