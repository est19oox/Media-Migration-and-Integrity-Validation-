# Administrator access

Managed EC2 deployments create a default `admin` account once, before User Access becomes ready. The host generates a random 48-character initial password and keeps it in `/etc/mmiv/managed-oracle/bootstrap-admin.json` (owner-only permissions). Oracle stores only its BCrypt hash. The account and password survive application updates, rollbacks, and ordinary restarts. Bootstrap history prevents automatically recreating an account after deletion or demotion. An existing administrator or a previously issued first invitation prevents default-account bootstrap; an occupied ordinary username is never silently promoted.

## Sign in as the default administrator

After the deployment succeeds, open an authorized EC2 Systems Manager shell and retrieve the initial password privately:

```sh
sudo python3 -c "import json; print(json.load(open('/etc/mmiv/managed-oracle/bootstrap-admin.json'))['password'])"
```

Open `/login.html` through the Gateway and sign in as `admin` using that password. Home then links to Administration. Use HTTPS for external administrator access. Alternatively, an operator can use an encrypted SSM port-forwarding session and open the local Gateway port:

```sh
aws ssm start-session --target i-YOUR_INSTANCE \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}'
```

The administration page reuses the same HttpOnly browser cookie as Home, Upload, Files and Logs; it has no second login form. Login lasts up to 30 minutes and survives page navigation or refresh. Signing out clears the shared cookie. Admin API authorization checks the current Oracle role and active status on every request; a JWT role claim does not grant administrator access by itself. Changing destination grants takes effect on the user's next operation, including existing login sessions.

For an externally managed Oracle deployment, default-account creation is opt-in: configure `MMIV_BOOTSTRAP_ADMIN_ENABLED=true`, `MMIV_BOOTSTRAP_ADMIN_USERNAME`, and a private `MMIV_BOOTSTRAP_ADMIN_PASSWORD` of at least 16 characters and at most 72 UTF-8 bytes before first startup. Never put this password in tracked configuration. Managed EC2 injects these values only into User Access, not other services.

## Invite an external administrator

1. The external administrator registers a normal account at `/signup.html` and chooses their own password.
2. Sign in at `/login.html`, open Administration from Home, and enter their registered username under **Invite another administrator**, and create an invitation. The website address must be reachable by the recipient; if you administer through a local SSM tunnel, replace the local address with the public Gateway origin before creating the link.
3. Copy the generated link and send it privately using your email or chat application. The system does not send email automatically; no mail provider is configured.
4. The recipient opens `/invite.html#invite=...`. Their existing login is reused; otherwise the normal login page preserves the invitation in the URL fragment and returns to the invitation page after sign-in. Click Accept invitation to open Administration.
5. The server checks the signed login token, account ownership, active status, token hash, expiry, and unused state. It consumes the invitation and promotes that account to ADMIN in one transaction. Other pending invitations for that account are invalidated too.

Invitations contain 256 bits of randomness, expire after 24 hours, and store only SHA-256 hashes in Oracle. The fragment is removed from the address bar when the page loads. The secret is sent only in the authenticated redemption request body; audit records omit invitation secrets, passwords, and JWTs. Merely opening a link does not grant privileges, and a different account cannot consume it.

Once admitted, an external administrator can manage destination permissions and invite other registered accounts without operator commands or manual SQL. Each administrator uses an individual password. Ordinary signup cannot select an administrator role. Verification accounts cannot become administrators through invitations.

## Manage destination permissions

See the [folder workspace guide](folder-workspace.md) for the shared home page, folder creation, browser flows and deployment requirements.

Create a top-level folder such as `SW001` or `semester2` through Administration. The portal creates an empty S3 folder marker without overwriting an existing marker, then registers the folder in Oracle `MMIV_FOLDERS`. Search users and choose one from the dropdown (up to 100 matching accounts); filter folders and choose one from the folder dropdown. Select View and download (READ) or Upload, view and download (WRITE), then save or revoke permission. WRITE includes READ. Active administrators can manage and access all registered folders. Ordinary users see only their current registered folder grants. Bucket-level grants do not authorize new folder uploads.

Gateway API routes are:

- `GET /user-access/api/admin/me`
- `GET /user-access/api/admin/users?username=...`
- `PUT /user-access/api/admin/users/{id}/destinations` with `{"destination":"...","permission":"READ"}` or WRITE
- `DELETE /user-access/api/admin/users/{id}/destinations?destination=...`
- `POST /user-access/api/admin/invitations` with `{"username":"..."}`
- `POST /user-access/api/admin/invitations/accept` with `{"token":"..."}`; requires the invited user's authenticated session

Permission and invitation actions emit RabbitMQ audit events after database commit. Lookup responses exclude password hashes. User Access endpoints require Bearer authentication. Browser calls use the fixed `/api/v1/portal/admin/**` routes at Transfer, which validates the shared cookie and CSRF token and forwards the JWT internally. User search responses exclude password hashes.

## Optional operator bootstrap invitation

If automatic default-account bootstrap is disabled, an authorized operator can issue the first invitation for an existing normal account with the AWS CLI and SSM access to the managed Oracle host:

```sh
python3 scripts/admin/bootstrap-first-admin.py \
  --instance-id i-YOUR_INSTANCE \
  --user-id UUID_RETURNED_BY_SIGNUP \
  --base-url https://YOUR_GATEWAY \
  --region ap-southeast-1
```

The command prints the invitation link only after the database accepts it. Only its hash reaches SSM. Concurrent bootstrap commands are serialized with database locks; existing ADMIN accounts or bootstrap history block another first invitation. `--replace-expired` replaces only an expired, unused first invitation while no administrators exist. Consumed invitations never reopen bootstrap. Normal delegated invitations are created through the page instead.

Local validation uses temporary databases. EC2 performs the configured, one-time initialization when the deployment runs.

## Password-reset links and account deletion

In Administration, search for an account and choose it from the user dropdown. **Create password-reset link** asks for confirmation and displays a private link for that named account. Set the website address to an origin the recipient can reach, copy the link, and send it privately. Delivery is manual; no email integration is configured. Use HTTPS when sharing credentials and reset links externally.

The recipient opens `/reset-password.html#reset=<token>`, enters a new password twice, and confirms. The link contains 256 bits of randomness; Oracle stores its SHA-256 hash, not the raw token. Links expire after one hour, are single-use, and issuing a replacement invalidates older links. Resetting requires at least 8 characters and no more than 72 UTF-8 bytes; the new password is stored as a BCrypt hash. Completing a reset invalidates existing JWT/browser sessions using an incremented `AUTH_VERSION` and a checked JWT `ver` claim. Old tokens without `ver` remain compatible only while the account's version is zero.

Administrators can reset ordinary active users or their own account, while other administrators are protected. Promotion to administrator invalidates reset links previously issued by someone else. Password reset also invalidates pending administrator invitations. The initial EC2 bootstrap password file is not updated after a reset; use the new password chosen by the account holder. Bootstrap never overwrites that new password on redeployment.

**Deactivate selected user** sets `APP_USERS.ACTIVE=0` and increments `AUTH_VERSION`. The user row, username, password hash, folder permissions, uploaded files, metadata assignments and audit history remain. Pending reset and invitation links issued by or for that account are expired, with their rows retained. Login and existing sessions are denied. The username remains reserved. A current administrator can deactivate their own account, including the last administrator, but cannot deactivate another administrator. Self-deactivation clears the shared session cookie and returns to sign in after acknowledgement. If the last administrator is deactivated, an authorized operator must restore administrator access; bootstrap history prevents automatic recreation. The existing DELETE account API performs this soft deletion. Temporary verification-account cleanup remains a separate hard-delete operation.

The invitation form has its own **Find account** search and eligible-user dropdown. It does not depend on the permissions search. No match displays an English account-creation prompt; inactive users, existing administrators and generated CI accounts are excluded.
