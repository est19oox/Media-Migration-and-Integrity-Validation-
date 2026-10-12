# Login, signup and Transfer protection

Gateway root now opens login. Create an account links to signup; successful registration returns to login. Login establishes an HttpOnly JWT cookie and redirects to Transfer. Transfer pages and upload/read APIs require verified User Access JWTs. Cookie mutations use CSRF protection. Sign out clears the session cookie. New accounts persist in Oracle, with hashed passwords and no elevated privileges.

Modified or added files:

- .github/workflows/cicd.yml
- README.md
- docs/ci-cd/login-access.md
- infrastructure/service-registry/compose-scaled.yml
- infrastructure/service-registry/compose.yml
- login-validation.txt
- scripts/cd/docker-stack-remote.py
- scripts/cd/login-for-verification.py
- scripts/cd/redact-verification-token.py
- scripts/cd/run-dast-ssm.sh
- scripts/cd/test-endpoints.py
- services/api-gateway/src/main/resources/application.yml
- services/transfer-service/UPLOAD-README.md
- services/transfer-service/pom.xml
- services/transfer-service/src/main/java/mmiv/transfer/config/TransferSecurityConfig.java
- services/transfer-service/src/main/java/mmiv/transfer/controller/BrowserSessionController.java
- services/transfer-service/src/main/resources/application.yml
- services/transfer-service/src/main/resources/static/auth.js
- services/transfer-service/src/main/resources/static/files.html
- services/transfer-service/src/main/resources/static/upload.html
- services/transfer-service/src/test/java/mmiv/transfer/config/TransferSecurityIntegrationTest.java
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
- tests/ci/test-dast-tunnel.py
- tests/ci/test-stack-lifecycle.py
- tests/ci/test-transfer-auth-pages.cjs
- tests/ci/test-upload-page.cjs

Configuration: docs/ci-cd/login-access.md. Local checks and unexecuted Java/Docker/Ansible checks: login-validation.txt.
