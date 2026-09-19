# DAST Integration

## Purpose

Dynamic Application Security Testing (DAST) is performed against a running test environment after the application has passed the required CI checks and has been deployed for integration testing.

OWASP ZAP baseline scan is used as the initial DAST mechanism.

## Responsibility Boundary

The DAST integration does not provision infrastructure or deploy the application.

A running test environment and target URL must be provided by the deployment/CD process.

The CI security workflow consumes the target URL and performs the DAST scan.

## Interface

The DAST wrapper accepts the target URL as its first argument:

```text
./scripts/ci/run-dast.sh <target-url>
Example:

./scripts/ci/run-dast.sh https://test.example.internal

## Reports

Generated reports are written to:

.ci/reports/

The initial reports are:

- zap-report.html
- zap-report.json

These files are generated CI evidence and should not be committed to source control.

## Pipeline Behaviour

The intended integration flow is:

Build and Unit Test
        |
        v
SAST / SCA
        |
        v
Test Deployment
        |
        v
DAST Target URL
        |
        v
OWASP ZAP Baseline Scan
        |
        +---- Pass ----> Continue
        |
        +---- Fail ----> Stop Pipeline

The DAST wrapper propagates the scanner exit status so that the CI pipeline can use the result as a security gate.

## Future Integration

When the test deployment interface is available, the deployment stage should provide the running application URL to the DAST stage.

The Jenkins pipeline can then invoke the DAST wrapper using the supplied target URL and archive the generated reports as build evidence.
