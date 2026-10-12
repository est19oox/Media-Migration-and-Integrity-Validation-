# Login, signup, protected Transfer and automated verification

Redirect correction: protected pages now return HTTP 302 with an explicit relative Location: /login.html. This keeps browser navigation on the public Gateway origin instead of allowing the servlet container to expand the redirect into an internal backend URL.

Includes the earlier security corrections. Default verification now creates one normal random ci_smoke account through signup per run and derives its user ID through login, so an initially empty Oracle database does not require a manual smoke-account secret. Supplied existing credentials remain supported and are never overwritten. Test fixtures remain in the persistent Oracle database as documented.

Changed files:

- .github/workflows/cicd.yml
- README.md
- docs/ci-cd/gateway-access.md
- docs/ci-cd/login-access.md
- infrastructure/service-registry/compose-scaled.yml
- infrastructure/service-registry/compose.yml
- login-validation.txt
- scripts/cd/docker-stack-remote.py
- scripts/cd/load-runtime-config.py
- scripts/cd/login-for-verification.py
- scripts/cd/redact-verification-token.py
- scripts/cd/run-dast-ssm.sh
- scripts/cd/test-endpoints.py
- scripts/cd/verify-deployed-services.py
- services/api-gateway/src/main/resources/application.yml
- services/api-gateway/src/test/java/mmiv/gateway/GatewayRoutingTest.java
- services/transfer-service/UPLOAD-README.md
- services/transfer-service/pom.xml
- services/transfer-service/src/main/java/mmiv/transfer/config/TransferSecurityConfig.java
- services/transfer-service/src/main/java/mmiv/transfer/controller/BrowserSessionController.java
- services/transfer-service/src/main/resources/application.yml
- services/transfer-service/src/main/resources/static/auth.js
- services/transfer-service/src/main/resources/static/files.html
- services/transfer-service/src/main/resources/static/upload.html
- services/transfer-service/src/test/java/mmiv/transfer/config/NonWebSecurityContextTest.java
- services/transfer-service/src/test/java/mmiv/transfer/config/TransferSecurityIntegrationTest.java
- services/user-access-service/pom.xml
- services/user-access-service/src/main/java/mmiv/user_access_service/config/SecurityConfig.java
- services/user-access-service/src/main/java/mmiv/user_access_service/controller/AuthExceptionHandler.java
- services/user-access-service/src/main/java/mmiv/user_access_service/controller/SignupController.java
- services/user-access-service/src/main/java/mmiv/user_access_service/dto/SignupRequest.java
- services/user-access-service/src/main/java/mmiv/user_access_service/dto/SignupResponse.java
- services/user-access-service/src/main/java/mmiv/user_access_service/repository/InMemoryUserRepository.java
- services/user-access-service/src/main/java/mmiv/user_access_service/repository/OracleUserRepository.java
- services/user-access-service/src/main/java/mmiv/user_access_service/repository/UserRepository.java
- services/user-access-service/src/main/java/mmiv/user_access_service/service/DuplicateUsernameException.java
- services/user-access-service/src/main/java/mmiv/user_access_service/service/InvalidSignupException.java
- services/user-access-service/src/main/java/mmiv/user_access_service/service/RegistrationService.java
- services/user-access-service/src/main/resources/static/login.html
- services/user-access-service/src/main/resources/static/signup.html
- services/user-access-service/src/test/java/mmiv/user_access_service/controller/SignupControllerTest.java
- services/user-access-service/src/test/java/mmiv/user_access_service/repository/OracleUserRepositoryIT.java
- services/user-access-service/src/test/java/mmiv/user_access_service/service/RegistrationServiceTest.java
- tests/api/endpoints.json
- tests/api/transfer-openapi.json
- tests/api/zap-upload-hook.py
- tests/ci/test-ci-runtime.sh
- tests/ci/test-dast-tunnel.py
- tests/ci/test-session-recovery.cjs
- tests/ci/test-stack-lifecycle.py
- tests/ci/test-transfer-auth-pages.cjs
- tests/ci/test-upload-page.cjs
- tests/ci/test-verification-login.py

Setup and defaults: docs/ci-cd/login-access.md. Checks and limits: login-validation.txt.
