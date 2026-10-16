# Login and protected access

[English](login-access.md) | [简体中文](login-access.zh-CN.md)

The API Gateway serves the browser frontend and is the browser's application
origin. User Access authenticates accounts and signs JWTs. The browser
exchanges the login result with Gateway to establish an HttpOnly `MMIV_SESSION`
cookie; it does not keep the access token in browser storage.

## Browser session

1. Sign in or create an account through the Gateway-served frontend.
2. The frontend establishes a Gateway session and confirms it through
   `GET /api/v1/session`.
3. Browser requests use the same-origin session cookie. Mutating requests use
   the CSRF token returned by the session endpoint.
4. Gateway verifies the configured issuer and User Access signing keys, then
   relays verified identity to the routed backend. Each backend remains
   responsible for authorization of its own data.
5. Signing out clears the browser session cookie. Expired or invalid sessions
   must authenticate again.

Gateway uses `USER_ACCESS_JWT_ISSUER` and `USER_ACCESS_JWK_SET_URI` to validate
User Access tokens. `JWT_COOKIE_SECURE` must be enabled when the Gateway is
served over HTTPS; the current local HTTP setup defaults it to `false`.
Never expose the User Access private signing key to Gateway or Transfer.

## Authorization

New accounts have no destination grants by default. Administrators grant
`READ` or `WRITE` for registered destinations. `WRITE` includes read access;
Discovery, Transfer, and Integrity check the current permission and assigned
metadata when performing their operations. Clients cannot use a file ID or
arbitrary object key to bypass destination authorization.

See [administrator access](../admin/administrator-access.md) for account
bootstrap, invitations, password reset, and grant management. See the
[folder workspace guide](../admin/folder-workspace.md) for browser behavior
and the [integrated service flow](../services/integration.md) for API and
service-to-service details.

## Verification

Gateway and protected service checks are part of the deployed verification
workflow. Local Maven tests can be run independently; they do not prove
connectivity to a live EC2 instance, AWS Systems Manager, or S3. See
[API and DAST validation](../testing/api-validation.md).
