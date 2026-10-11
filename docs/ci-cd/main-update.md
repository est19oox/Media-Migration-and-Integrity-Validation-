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
integration tests. Database files live in the external named volume
`mmiv-oracle-data`; Compose release recovery never deletes it. Additional services
can later receive their own schema accounts and passwords in this Oracle server,
with explicit initialization and permissions added when those services are ready.

The nonsecret repository variables `USER_ACCESS_JWT_ISSUER` and
`USER_ACCESS_JWT_KEY_ID` remain optional, with defaults `https://auth.mmiv.example`
and `mmiv-user-access-key-1`. Set an issuer appropriate for your deployment and use
the same issuer in future Metadata JWT verification. OIDC supplies AWS credentials;
queue URL, EC2 ID and image identities are obtained automatically.

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

Terraform now requests `t3.large` (8 GiB RAM) and a 40 GiB encrypted gp3 root disk
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
