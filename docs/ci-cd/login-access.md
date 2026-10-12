# Login and protected Transfer access

This complete project includes the earlier login-page update. Rebuild and redeploy User Access, Transfer and API Gateway together.

## Browser flow

1. Open http://EC2_PUBLIC_IP:8080/ or http://localhost:8080/ through the Gateway tunnel.
2. Sign in using a User Access account, or select Create an account to register. Signup returns to login before opening Transfer.
3. Login calls /user-access/api/auth/login, then sends the returned Bearer token to POST /api/v1/session. Transfer verifies its signature, issuer, subject and expiry before setting the MMIV_SESSION cookie.
4. Login confirms the browser cookie with GET /api/v1/session before redirecting to /upload.html. Uploads and file previews/downloads use the HttpOnly SameSite=Strict cookie. The token is no longer stored in sessionStorage.
5. Sign out clears the browser cookie and returns to /login.html. Expired sessions return to login. Clearing a cookie does not revoke a separately retained JWT; it remains valid until expiry.

Anonymous /upload.html and /files.html requests redirect to /login.html. Anonymous file APIs and uploads are rejected. Cookie-authenticated mutations require a CSRF token from GET /api/v1/session; auth.js handles this. Direct backend API access also checks JWTs. Health endpoints remain available for deployment checks.

Any authenticated account can currently use Transfer and read objects whose IDs it knows. This change enforces login; it does not add per-user ownership or destination permission enforcement.

## Configuration

Deployed Transfer retrieves public keys at http://user-access-service:8083/.well-known/jwks.json. USER_ACCESS_JWT_ISSUER must match the issuer used by User Access (default https://auth.mmiv.example). Both local Compose files include the corresponding Transfer configuration. Direct JVM runs default the JWKS URL to localhost:8083. Browser login must use the Gateway on port 8080.

The current development Gateway uses HTTP, so JWT_COOKIE_SECURE defaults to false. When placing the deployment behind HTTPS, set JWT_COOKIE_SECURE=true in Transfer's environment (and in its deployment configuration). No private signing key is copied to Transfer.

## Authenticated CD verification

The existing anonymous upload smoke tests and DAST have been updated to log in through the Gateway. By default verification signs up one randomly named account, reuses it for the run, and deletes it through the authenticated cleanup endpoint after all checks, including failed checks. To use a supplied account instead, add credentials to the MMIV_RUNTIME_CONFIG GitHub secret:

```json
{
  "userAccessSmoke": {
    "username": "YOUR_EXISTING_TEST_USERNAME",
    "password": "YOUR_EXISTING_TEST_PASSWORD",
    "userId": "THE_ACCOUNT_USER_ID"
  }
}
```

If MMIV_RUNTIME_CONFIG already includes external database or Integrity settings, add userAccessSmoke to that JSON rather than replacing the other settings. Supplied accounts are never created, reset, or deleted by verification. If no credentials are supplied, verification calls the real signup API with a unique `mmiv_ci_` name and a random password, then logs in normally. Partial credentials fail instead of falling back to signup. `userId` is optional; checks use the actual JWT subject returned by login. The account cache is private, shared across scans, and kept outside uploaded reports. Cleanup failure fails verification and records evidence; the persistent Oracle volume is preserved. Only the caller’s generated `USER` account can be removed by `DELETE /api/auth/verification-account`; regular and privileged accounts are rejected. Transfer's DAST now targets its Gateway routes, while Transfer itself still verifies the JWT. Scanner tokens are masked and redacted from saved text evidence.

## Verification

Spring integration tests cover anonymous access, real RSA signatures, wrong issuer, expired/forged tokens, browser cookie establishment, CSRF and logout. Run mvn -B clean verify with Java 25 from services/transfer-service. Run the Python verification-account, runtime-configuration, tunnel, and lifecycle tests under tests/ci for the deployment path.

## Signup

Open /signup.html using the Create an account link on login. POST /user-access/api/auth/signup accepts username and password. Successful signup returns HTTP 201 and a userId; duplicates return 409. Usernames are case-sensitive, 3–40 ASCII letters/numbers/dots/underscores/hyphens. Passwords require at least 8 characters and at most 72 UTF-8 bytes; they are hashed using the existing BCrypt encoder.

New accounts are active with role USER and no global or destination permissions. Clients cannot select roles or privileges. As documented above, the current Transfer gate checks login only, so these accounts can use Transfer; destination/ownership checks are a separate integration. Accounts persist in Oracle using the existing APP_USERS schema. Unique username constraints and an INSERT-only repository method prevent duplicate requests from overwriting existing credentials. No new migration or signup secret is needed.

Signup does not include email verification or password reset. It returns to login after successful creation.

The scanner obtains a fresh JWT after functional checks, so those checks do not consume its 30-minute authentication window. JWTs past their expiration time are rejected by Transfer even within the default validator clock-skew allowance.

Session timing now uses expiresInSeconds calculated by the server, so an incorrect browser clock does not immediately log out a valid session. If another tab rotates the CSRF cookie, the browser refreshes the token and retries only once after the exact CSRF_INVALID response from CsrfFilter. General authorization failures are never replayed. Verification login accepts only a strict loopback tunnel URL and a Bearer JWT, refuses redirects, and keeps its temporary environment file private.
