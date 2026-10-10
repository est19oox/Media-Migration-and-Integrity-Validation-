# Reporting Service

Build: `mvn -B clean verify`

Run: `mvn spring-boot:run`

Health: `GET /actuator/health` on port 8084.

Scaffold status: `GET /api/reports/status`. This endpoint verifies that the
service is running; Oracle extraction, report generation, and MongoDB storage
are not implemented yet.
