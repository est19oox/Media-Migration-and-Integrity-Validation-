# Oracle integration test discovery

The uploaded source includes AuthLoginIT (3 tests) and OracleUserRepositoryIT (6 tests). The reported failed run discovered no integration tests and only 37 unit tests, whereas this ZIP executes 125 unit tests. The exact cause of that different checkout/test selection cannot be established from the supplied log.

Changes:

- Make Failsafe discovery of \**/*IT.java explicit, retaining failIfNoTests=true.
- Disable RabbitMQ event publishing and its health indicator for these Oracle-focused tests, matching unit test isolation.
- Before CI builds, require both Oracle test source files.
- After CI builds, require passing, non-skipped results from both Oracle suites.

Validation: Maven -Poracle-it clean verify passed with Java 25 and a disposable real Oracle database: 125 unit tests and 9 integration tests, zero failures/errors/skips. The report validator rejects missing, empty, skipped and failing suites. The shell script passes sh -n.

Start a new Actions run on the commit containing this fix; rerunning an older run uses its older source. No AWS deployment was performed.
