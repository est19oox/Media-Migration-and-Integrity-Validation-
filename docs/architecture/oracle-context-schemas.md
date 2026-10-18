# Oracle schemas by bounded context

The managed Oracle instance contains three application-owned schemas:

| Schema              | Owner              | Tables                                                                            |
| ------------------- | ------------------ | --------------------------------------------------------------------------------- |
| `USER_ACCESS`       | User Access        | accounts, permissions, destinations, invitations and password resets              |
| `DISCOVERY`         | Discovery/Metadata | transfer plans, name reservations, verification runs, file metadata and media log |
| `INTEGRITY_SERVICE` | Integrity          | checksum validation audit log                                                     |

Transfer, Gateway and Service Registry do not own Oracle tables. Reporting keeps its MongoDB store. The two Integrity replicas share the same Integrity schema.

For a managed EC2 deployment, the host creates distinct persistent passwords in `/etc/mmiv/managed-oracle/credentials.json`. The Oracle startup hook creates missing schema users without dropping existing users or data. Discovery receives only its own database credentials. Integrity uses its Oracle profile and its own credentials. Existing `USER_ACCESS` credentials and signing keys remain unchanged.

On the first Discovery startup after this change, Discovery creates its tables in `DISCOVERY`, then copies any legacy Discovery-owned tables from `USER_ACCESS`. Each table gets a migration marker in `DISCOVERY.MMIV_SCHEMA_MIGRATIONS`; restarting after a completed copy does not duplicate rows. The old tables are retained for rollback and should only be removed after checking the migrated row counts and taking a database backup. `FILE_METADATA` and `MEDIA_LOG` receive new generated row IDs because their IDs are not referenced by application relations; their other columns are copied. The managed Oracle hook grants Discovery read access only to the six legacy tables that exist. Keep the old schema and grants until the upgrade has been verified.

For an externally managed Oracle database, the runtime JSON must provide separate `userAccess`, `discovery` and, when Integrity uses Oracle, `integrity` connection sections. Provision those users and the Integrity audit table before deployment. Give the Discovery user `SELECT` on the six old Discovery tables in `USER_ACCESS` for the one-time copy. The managed-instance hook does not modify an external database.

# Integrity metrics

Prometheus scrapes both Integrity containers separately. The `replica` label identifies `integrity-service` and `integrity-service-2`; Prometheus also supplies its normal `instance` target label. The `mmiv_integrity_validation_seconds` histogram records each validation's duration and `outcome` (`PASSED`, `FAILED` or `ERROR`). The Grafana dashboard plots request rate, mean duration, p95 duration and outcomes for each replica.

Grafana listens on EC2 loopback port 3000. Forward that port with SSM or SSH and open `http://127.0.0.1:3000/d/mmiv-integrity/mmiv-integrity-validation` locally. Grafana provides read-only anonymous access because the port is not published on the EC2 public interface. Its Prometheus datasource and dashboard are provisioned from `infrastructure/monitoring/grafana/`.

The **MMIV Services overview** dashboard at `http://127.0.0.1:3000/d/mmiv-services/mmiv-services-overview`
shows every Java service, including API Gateway and both Integrity replicas. Use the Service dropdown
to select one or several services. Panels show scrape availability, HTTP throughput, mean response
duration, 4xx/5xx responses, process CPU, JVM heap, threads and uptime. HTTP panels may have no data
before requests arrive; `up=1` means scraping works, not that every business dependency is healthy.
Oracle, MongoDB, RabbitMQ and Redis do not expose these JVM metrics and are not included in this dashboard.

Gateway metrics are accessible without a user session only from loopback and private network peers.
Override `mmiv.monitoring.scrape-cidrs` if your Docker network uses a different CIDR. Gateway ignores
forwarded address headers so client-supplied headers cannot grant metrics access. The release command
packages every dashboard JSON in `infrastructure/monitoring/grafana/dashboards/`; both dashboards and
the datasource are provisioned automatically. This change takes effect on the next deployment.
