# Administrator access

Managed EC2 deployments create a default `admin` account once, before User Access becomes ready. The host generates a random 48-character initial password and keeps it in `/etc/mmiv/managed-oracle/bootstrap-admin.json` (owner-only permissions). Oracle stores only its BCrypt hash. The account and password survive application updates, rollbacks, and ordinary restarts. Bootstrap history prevents automatically recreating an account after deletion or demotion. An existing administrator or a previously issued first invitation prevents default-account bootstrap; an occupied ordinary username is never silently promoted.

## Sign in as the default administrator

After the deployment succeeds, open an authorized EC2 Systems Manager shell and retrieve the initial password privately:

```sh
sudo python3 -c "import json; print(json.load(open('/etc/mmiv/managed-oracle/bootstrap-admin.json'))['password'])"
```

Open `/admin.html` through the Gateway and sign in as `admin` using that password. Use HTTPS for external administrator access. Alternatively, an operator can use an encrypted SSM port-forwarding session and open the local Gateway port:

```sh
aws ssm start-session --target i-YOUR_INSTANCE \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}'
```

The page keeps its Bearer JWT only in memory. Closing, refreshing, or signing out ends that page's session. Admin API authorization checks the current Oracle role and active status on every request; a JWT role claim does not grant administrator access by itself. Changing destination grants takes effect on the user's next operation, including existing login sessions.

For an externally managed Oracle deployment, default-account creation is opt-in: configure `MMIV_BOOTSTRAP_ADMIN_ENABLED=true`, `MMIV_BOOTSTRAP_ADMIN_USERNAME`, and a private `MMIV_BOOTSTRAP_ADMIN_PASSWORD` of at least 16 characters and at most 72 UTF-8 bytes before first startup. Never put this password in tracked configuration. Managed EC2 injects these values only into User Access, not other services.

## Invite an external administrator

1. The external administrator registers a normal account at `/signup.html` and chooses their own password.
2. Sign in to `/admin.html`, enter their exact registered username under **Invite another administrator**, and create an invitation. The website address must be reachable by the recipient; if you administer through a local SSM tunnel, replace the local address with the public Gateway origin before creating the link.
3. Copy the generated link and send it privately using your email or chat application. The system does not send email automatically; no mail provider is configured.
4. The recipient opens `/admin.html#invite=...` and signs in with their own username and password.
5. The server checks the signed login token, account ownership, active status, token hash, expiry, and unused state. It consumes the invitation and promotes that account to ADMIN in one transaction. Other pending invitations for that account are invalidated too.

Invitations contain 256 bits of randomness, expire after 24 hours, and store only SHA-256 hashes in Oracle. The fragment is removed from the address bar when the page loads. The secret is sent only in the authenticated redemption request body; audit records omit invitation secrets, passwords, and JWTs. Merely opening a link does not grant privileges, and a different account cannot consume it.

Once admitted, an external administrator can manage destination permissions and invite other registered accounts without operator commands or manual SQL. Each administrator uses an individual password. Ordinary signup cannot select an administrator role. Verification accounts cannot become administrators through invitations.

## Manage destination permissions

Find a user by exact username, select READ or WRITE, then save or revoke their permission. The page uses the configured `MMIV_DESTINATION_ID` (default: `MMIV_BUCKET_NAME`) and refuses arbitrary destinations. WRITE includes READ. Administrator role itself does not grant file access or bypass metadata ownership checks; administrators can assign destination access using this page.

Gateway API routes are:

- `GET /user-access/api/admin/me`
- `GET /user-access/api/admin/users?username=...`
- `PUT /user-access/api/admin/users/{id}/destinations` with `{"destination":"...","permission":"READ"}` or WRITE
- `DELETE /user-access/api/admin/users/{id}/destinations?destination=...`
- `POST /user-access/api/admin/invitations` with `{"username":"..."}`
- `POST /user-access/api/admin/invitations/accept` with `{"token":"..."}`; requires the invited user's authenticated session

Permission and invitation actions emit RabbitMQ audit events after database commit. Lookup responses exclude password hashes. Administrator endpoints require Bearer authentication and do not accept browser cookies.

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
