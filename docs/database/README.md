# Local Oracle database

Requirements: Docker running with Linux containers and Docker Compose v2.

From the repository root on Windows:

```powershell
.\database\setup-db.ps1
```

On Linux/macOS:

```bash
bash database/setup-db.sh
```

Both scripts use the Compose file next to the script, regardless of the current directory. They ask for a password unless ORACLE_PWD is already set, validate configuration without printing the password, and start Oracle. Wait for the image's health check to report healthy before connecting.

- Host: localhost
- Port: 1521
- Service: FREEPDB1
- Administrative user: SYSTEM (use the initialization password)
- Container: media-migration-oracle
- Persistent volume: media-migration-oracle-data

The password is required but is not committed to source. On an existing database volume, entering another initialization password does not reset the database password. Use the existing password. The default image matches the previous setup script; set ORACLE_IMAGE to a compatible version tag/digest when you need a pinned image. Do not upgrade the database image against existing data without following Oracle's upgrade procedure.

The port binds to localhost for local development. The volume survives ordinary container removal; do not use `docker compose down -v` unless you intend to delete the database data.

If you previously used the old PowerShell script, a standalone container with the same name may already exist. You can continue using it with `docker start media-migration-oracle`. Compose cannot adopt that container automatically. Before migrating, back up the database and inspect its mounts and image. Only after verifying that its data is in the named volume above, stop and remove the old container WITHOUT deleting its volume, then run the new setup script with the same compatible image and database password. The scripts do not automatically delete an existing container.

This is local database setup; it is not added to the EC2 CD workflow.
