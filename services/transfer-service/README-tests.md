# Transfer-service tests

Run from repository root with Java 25 and Maven:

```sh
mvn -B -f services/transfer-service/pom.xml test
```

Tests use JUnit Jupiter, Mockito and standalone MockMvc. S3Client is mocked; no AWS account, credentials, running server or Docker is required. Temporary files are cleaned up by JUnit. The symbolic-link case skips only when the operating system cannot create symbolic links.

- TransferRequestValidatorTest: required fields, valid paths, missing files/directories, invalid paths, traversal, sibling-prefix escape, symlinks.
- TransferStrategyFactoryTest: matching, first-match precedence, unsupported media, empty strategies.
- TransferServiceTest: orchestration order, early termination, error propagation.
- S3TransferStrategyTest: extensions, upload metadata and exact body bytes, content-type detection, empty files, missing files, S3 failures.
- TransferControllerTest: request/response mapping, 400/502, malformed or missing body, unsupported media type and HTTP method.
- TransferServiceApplicationTest: existing Spring context smoke test, separate from the isolated unit tests.

Mockito uses `mock-maker-subclass` so these tests do not require JVM agent attachment. Final classes and static methods are not mocked.

## Verified result

Executed with Java 25.0.4.1 and Maven 3.9.11: **57 tests passed; zero failures, errors, or skipped cases**. This includes 56 isolated unit/controller cases and one Spring context test.

| Class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| TransferServiceApplicationTest | 1 | 0 | 0 | 0 |
| TransferControllerTest | 7 | 0 | 0 | 0 |
| TransferStrategyFactoryTest | 4 | 0 | 0 | 0 |
| TransferServiceTest | 4 | 0 | 0 | 0 |
| S3TransferStrategyTest | 20 | 0 | 0 | 0 |
| TransferRequestValidatorTest | 21 | 0 | 0 | 0 |
