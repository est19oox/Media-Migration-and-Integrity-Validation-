# Persistent Oracle for User Access

CD now creates one Oracle container with a dedicated `USER_ACCESS` schema/account.
Integrity's source code, database mode and existing environment are preserved.
Reporting and Discovery remain disabled and are left to their owners.

## Default deployment: no runtime GitHub secret required

Leave `MMIV_RUNTIME_CONFIG` unset to use managed Oracle for User Access. During the
locked EC2 deployment, CD generates independent Oracle administrator and User Access
passwords and a 2048-bit RSA JWT key pair. These values are generated once and reused
across releases. They stay in root-only files under `/etc/mmiv/managed-oracle`;
passwords and private keys are absent from SSM command arguments, Compose content,
release manifests and downloadable evidence.

User Access connects to `jdbc:oracle:thin:@//oracle:1521/FREEPDB1` as `USER_ACCESS`.
The image initializes that application account on first database initialization.
User Access's existing idempotent migrations then create its tables before startup
readiness. No administrator password is passed to the application container.
Oracle port 1521 is available only on the internal Docker network, with no host
port published. Integrity does not connect to this Oracle container.

Oracle uses `gvenzl/oracle-free:23.26.3-slim-faststart`, also used by User Access CI
integration tests. The startup hook directly reclaims unused tails of User Access
`USERS` datafiles: the target is the highest allocated extent plus 16 MiB, with a
32 MiB minimum per file. It only shrinks files larger than that target and retains
a file when Oracle reports occupied blocks or a minimum-file constraint. The PDB's
`TEMP` tablespace shrinks toward a 64 MiB minimum when currently larger. Allocated
space can keep either target higher. Growth remains enabled where already enabled,
with 8 MiB increments. USERS datafiles have a 512 MiB maximum per file and TEMP
files a 128 MiB maximum per file; existing larger occupied files retain a sufficient
maximum instead of forcing data below the budget. These limits are shared PDB file
budgets, not additional per-service databases.

This is not a limit on the whole Oracle database. SYSTEM, SYSAUX, UNDO, redo logs,
container image layers and other databases are untouched. Savings depend on unused
allocated space; the deployment cannot promise a fixed total size. CI exercises the
same SQL against Oracle, checks 20,000 sample rows, retained system files, new
quotas/growth budgets and repeat execution, and reports before/after bytes. No live EC2 database
has been resized by this code update.

Oracle Docker output logs rotate at 10 MB with three files; this does not limit
internal Oracle logs. The Terraform root disk target is 30 GiB. Database files live in the
external named volume `mmiv-oracle-data`; recovery never deletes it. Additional services
can later receive their own schema accounts and passwords in this Oracle server,
with explicit initialization and permissions added when those services are ready.

The nonsecret repository variables `USER_ACCESS_JWT_ISSUER` and
`USER_ACCESS_JWT_KEY_ID` remain optional, with defaults `https://auth.mmiv.example`
and `mmiv-user-access-key-1`. Set an issuer appropriate for your deployment and use
the same issuer in future Metadata JWT verification. OIDC supplies AWS credentials;
queue URL, EC2 ID and image identities are obtained automatically.

## Resource budget for up to 20,000 records

| Component | Configured budget |
| --- | --- |
| Managed Oracle container | 3 GiB RAM; memory+swap limit also 3 GiB |
| User Access container | 512 MiB RAM; memory+swap limit also 512 MiB |
| User Access JVM | 64 MiB initial heap, 256 MiB maximum heap, 64 MiB maximum direct buffers |
| User Access JDBC pool | 4 maximum connections, 1 minimum idle |
| User Access Tomcat | 24 maximum threads, 2 minimum spare |
| USER_ACCESS schema allocation on USERS | 128 MiB, including its tables and indexes |

The 128 MiB quota is a working budget for small account/permission records. At
2 KiB average allocated storage per logical record, 20,000 records use about
39 MiB, leaving room for indexes and variation. Actual usage depends on related
permission rows and path lengths. Exceeding the byte quota rejects further space
allocation; no hard 20,000-row rule is added. An existing schema already allocating
more than 128 MiB stops the rollout rather than deleting records or silently
limiting an over-budget database. Remove a directly granted UNLIMITED TABLESPACE
privilege so it cannot bypass the schema quota.

The Oracle limit bounds the container's total memory; it does not change SGA/PGA
parameters or reserve 3 GiB continuously. CI starts Oracle with that same limit.
Its storage check inserts 20,000 indexed sample rows with 80-character payloads,
verifies retention/reclamation/quotas, and repeats the routine. Actual User Access
startup at 512 MiB is checked by deployed container health and functional tests;
concurrent production load has not been measured. A record-count assumption alone
does not determine concurrent request capacity.

Existing data, persistent volume, signing keys, faststart image, Integrity and the
EC2 8 GiB RAM configuration is preserved; the root disk target is now 30 GiB. Oracle resource/storage
budgets persist through application rollback because the database is retained.
When future services join this Oracle, revisit the shared file and connection
budgets and provision their own schema quotas. Optional external databases are
not resized or assigned quotas by this managed-Oracle hook.

## Optional external configuration

If you keep a `userAccess` section in the existing `MMIV_RUNTIME_CONFIG` secret,
CD continues using that external database and supplied keys. It does not start the
managed Oracle container in this mode:

```json
{
  "userAccess": {
    "dbUrl": "jdbc:oracle:thin:@//db.internal:1521/FREEPDB1",
    "dbUsername": "USER_ACCESS",
    "dbPassword": "YOUR_DATABASE_PASSWORD",
    "jwtPrivateKey": "-----BEGIN PRIVATE KEY-----\nYOUR_PKCS8_KEY_CONTENT\n-----END PRIVATE KEY-----"
  }
}
```

An optional X509 `jwtPublicKey` can be supplied; otherwise OpenSSL derives it.
Malformed supplied configuration fails with a generic error that hides secret input.
The existing optional `integrity` section (`dbUrl`, `dbUsername`, `dbPassword`)
remains supported for deliberate changes to Integrity's configuration. If absent,
CD preserves an existing Integrity environment and creates its usual H2 environment
only when none exists. This update does not migrate Integrity to Oracle.

An optional `userAccessSmoke` section containing `username`, `password`, `userId`
allows tests against an existing dedicated account. Without it, account-dependent
smoke tests skip; JWKS, invalid login and invalid-token tests still run. Database
creation does not create application users. Seed accounts using User Access's
existing BCrypt schema and administration process.

An external database is not automatically copied into the new managed database.
Keep the external configuration until a deliberate data migration has completed.
When moving to managed Oracle, CD retains keys from the deployed immutable User
Access bundle. It refuses to silently replace missing keys while an existing
application or database is present.

## Deployment and recovery

There is one forward `docker compose up` invocation per new release. Compose health
checks start Oracle and Registry first, each backend after Registry, User Access
after Oracle too, and Gateway after all enabled backends are healthy. Current
container Eureka registration and Gateway routes are checked before verification
starts. Scanner tools are prepared once per verification job and reused.

A repeated successful release performs readiness checks without another deployment.
Unchanged images and settings preserve containers on a new release. Rollback is a
separate recovery action and may run additional Compose commands for changed
application services. It restores their previous images/configuration, retains
Oracle and does not roll back schema changes or database records.

Back up Oracle data consistently and protect `/etc/mmiv/managed-oracle` plus the
immutable bundles under `/etc/mmiv/user-access/versions`. They are required to
recover passwords and JWT keys. If a database volume exists but credentials or the
signing key are lost, deployment fails and requires backup restoration; it never
resets or deletes the existing database. An external Docker volume survives
container removal, but not loss of the EC2 disk. No automatic backup job is added.

Terraform now requests `t3.large` (8 GiB RAM) and a 30 GiB encrypted gp3 root disk
for the service stack plus Oracle. This increases AWS cost, may stop/start the
existing instance for its size change, and grows its disk. `prevent_destroy` and
launch-setting protections remain. No AWS changes have been applied in this update.

## Other services

Transfer still publishes timestamped events to SQS through its persistent outbox.
See `services/transfer-service/SQS-LOGGING.md`. Reporting remains a placeholder;
its owner must implement the consumer and storage. Discovery/Metadata destinations,
upload grants and folder-access integration remain deferred. Local development
Compose continues accepting explicit User Access environment and key files; this
managed setup is the EC2 CD path.

Targeted validation is recorded in `changed-files.md`. Live Oracle startup,
Terraform apply, SSM and DAST must be verified by the GitHub workflow.

## Root disk budget

The root disk target is reduced from 40 GiB to 30 GiB for the current small-record,
light-load demo. This leaves space for the retained Oracle faststart image, Oracle
system/data files, operating system, service images and release recovery. It is a
planning budget, not a measured minimum. A 20,000-row count does not cap image,
Oracle system/log or application-file storage. Older Docker images and internal
Oracle logs can accumulate; this update does not prune images or database volumes.
Monitor `df -h /` and `docker system df` on the deployed host before treating 30 GiB
as sufficient for sustained operation.

The last supplied AWS state showed an 8 GiB root volume. If still 8 GiB, applying
this configuration expands it to 30 GiB. If AWS has already expanded the volume
above 30 GiB, EBS cannot shrink it in place: retain the actual larger volume size
in Terraform rather than applying a decrease. No live volume was resized here.
Instance replacement remains blocked by prevent_destroy. Moving an already larger
root volume to a smaller disk requires a separate migration, which is not included.

## Oracle startup memory headroom

The uploaded startup log reports a 1,603,398,928-byte SGA (about 1.49 GiB), then
ORA-03113 immediately after mounting. The previous 2 GiB total container cap left
little room for PGA, Oracle processes, startup work and other container overhead.
OOM is a leading hypothesis, not confirmed by this log alone. CI and managed EC2
Oracle now both use a 3 GiB memory cap, with the same combined memory/swap cap.
User Access remains at 512 MiB; all schema/file storage budgets remain unchanged.

The number of rows is not a reliable measure of Oracle's startup memory demand.
Oracle Free's internal SGA/PGA restriction does not represent every byte charged
to the Docker container. This change provides headroom without altering internal
Oracle memory parameters, deleting database data, or adding another deployment.

If the failure continues, inspect the container OOM state and Oracle alert/trace
logs. A false container OOMKilled flag alone does not exclude a killed child
process; inspect host kernel/cgroup memory events when available. ORA-03113 can
also result from Oracle process errors unrelated to memory. The revised startup
has not been run locally because Docker/Oracle are unavailable in this environment.

## SQS CreateQueue permission repair

The existing Terraform role lacked the SQS provisioning inline policy because the
full bootstrap job remains commented out. A pre-plan step now reconciles the scoped
policy for the two named MMIV queues, using the same template as bootstrap. It
requires the role's existing IAM policy-management authorization and does not run
a full bootstrap apply. Queue resources, EC2 configuration and application code
are unchanged. No live AWS permissions were modified during this code update.

The reported application apply created EC2 before failing on SQS. Retain its remote
state and generate a fresh plan when retrying. Confirm the created instance is in
state; repair state if needed rather than provisioning a second instance.
