# User Access Service database migrations

These manually executed Oracle migrations create and extend the database objects
used by the User Access Service.

## Execution order

Run the migrations as the `USER_ACCESS` schema owner against the `FREEPDB1`
service, in this order:

1. `migrations/001_create_schema.sql` creates the `APP_USERS` and
   `USER_PERMISSIONS` tables and their related constraints and index.
2. `migrations/002_add_password_hash.sql` adds the `PASSWORD_HASH` column to
   `APP_USERS`.

These scripts are currently executed manually with SQL*Plus. Databases that
have already applied these migrations must not rerun them unnecessarily. The
scripts are guarded for their existing objects, but rerunning database changes
should always be an intentional operation against the correct database.

Future migrations should use the next sequential number, describe one
database change, and be documented here with their required execution order.
