# DAST Integration

## Purpose

Dynamic Application Security Testing (DAST) is performed against a *running*
application target after the application has passed the required CI checks.

`scripts/ci/run-dast.sh` invokes an OWASP ZAP baseline scan against a reachable
URL and translates the scanner result into a stable CI gate decision. The
wrapper is the DAST interface for this project: it owns the scanner invocation,
the target URL interface, exit-code interpretation, report generation, and
CI-compatible output.

The wrapper is pipeline-independent. It requires only Docker and a reachable
target URL, so Jenkins, GitHub Actions, or a local shell can all invoke it
without changes to this component.

## Responsibility Boundary

This component owns:

- scanner invocation (OWASP ZAP baseline via Docker)
- the target URL interface
- exit-code interpretation and gate policy
- report generation under `.ci/reports/`
- CI-compatible console output

This component does **not** own:

- the application runtime
- deployment
- AWS infrastructure
- creation of the HTTP target
- Jenkins orchestration

A running test environment and a reachable target URL must be provided by
whoever owns deployment. The DAST stage never provisions infrastructure and
never deploys the application.

## Interface

```text
scripts/ci/run-dast.sh <target-url>
DAST_TARGET_URL=<url> scripts/ci/run-dast.sh
```

The positional argument takes precedence over `DAST_TARGET_URL` when both are
supplied. The target must be an `http://` or `https://` URL.

Examples:

```sh
./scripts/ci/run-dast.sh https://test.example.internal
DAST_TARGET_URL=https://test.example.internal ./scripts/ci/run-dast.sh
DAST_FAIL_ON_WARN=true ./scripts/ci/run-dast.sh https://test.example.internal
```

Running the script with no target prints the usage block and exits with code 3.

## Docker Dependency

The scan runs `zap-baseline.py` from the image named by `ZAP_IMAGE`. The default
is pinned (`ghcr.io/zaproxy/zaproxy:2.17.0`) so that scans are reproducible.
The wrapper verifies that Docker is installed and that the daemon is reachable
before scanning, and exits with code 3 if either check fails.

The container is started with `--user "$(id -u):$(id -g)"` so that the ZAP
process writes reports as the same user that owns the mounted directory. The ZAP
image otherwise runs as its own `zap` user (UID 1000), which cannot write into a
bind-mounted directory owned by the Jenkins agent on Linux. This is the reason
the flag is required rather than cosmetic.

The report directory is bind-mounted into the container. Because the path is
rooted at the repository, the mount is valid regardless of the caller's current
directory.

## Environment Variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `DAST_TARGET_URL` | *(none)* | Target URL when no positional argument is given. |
| `DAST_FAIL_ON_WARN` | `false` | When exactly `true`, a WARN result becomes blocking. Any other value is treated as false. |
| `ZAP_IMAGE` | `ghcr.io/zaproxy/zaproxy:2.17.0` | Scanner image override. |

## Reports

Reports are always written to `.ci/reports/` relative to the repository root:

- `zap-report.html`
- `zap-report.json`

The location is fixed and not configurable. This is deliberate: the Jenkins
pipeline archives `.ci/reports/**/*`, and the other CI security stages write
their JSON evidence to the same directory. A configurable output directory could
produce a successful scan whose reports are never archived.

Generated reports are CI evidence and are not committed to source control.

## Exit-Code Semantics

The wrapper distinguishes two things: what the scanner reported, and what the
wrapper returns to the caller.

Interpretation of the raw ZAP exit code:

| Raw ZAP exit | Result | Blocking |
| --- | --- | --- |
| 0 | PASS | no |
| 1 | FAIL | yes |
| 2 | WARN | only when `DAST_FAIL_ON_WARN=true` |
| anything else | ERROR | yes |

Wrapper exit codes — this is the stable contract callers should depend on:

| Wrapper exit | Meaning |
| --- | --- |
| 0 | Non-blocking success (PASS, or WARN with warnings non-blocking). |
| 1 | The baseline reported FAIL alerts. |
| 2 | Warnings were reported and `DAST_FAIL_ON_WARN=true`. |
| 3 | Configuration, Docker, or scanner error. |

Docker-level failures (for example 125, 126, 127) are reported as ERROR and
normalized to wrapper exit code 3, so infrastructure failures never masquerade
as ZAP alert semantics.

The result block always prints the **raw command exit code** for diagnosis,
alongside the interpreted result and the wrapper's own exit code — including on
failure, so a failed stage still shows where its evidence was written and what
the underlying tool actually returned.

WARN is non-blocking by default because a baseline scan of a typical web
application almost always emits informational warnings (missing response
headers, server banner disclosure) that do not warrant failing every build.
Note that ZAP treats all alerts as WARN by default, so without
`DAST_FAIL_ON_WARN=true` a baseline scan will rarely block a pipeline.
Enabling it turns the wrapper into a stricter gate once the team has reviewed
the baseline findings for the real target.

## Rule Overrides

`security/zap/zap-rules.tsv` is the ZAP baseline rule override file. It does not
currently define any active rules, and **it is not currently consumed by
`scripts/ci/run-dast.sh`** — there is no `-c` argument in the ZAP invocation, so
nothing in that file affects scan results today.

Adding approved overrides and wiring `-c` into the invocation is future work,
and both belong in the same change. Suppressing an alert or downgrading its
severity is a security decision that needs review, not a build fix.

## Current Limitation

**The repository does not currently provide a confirmed runnable HTTP
application target for DAST.** The DAST integration is therefore
target-agnostic and is activated when a deployment or test environment provides
a reachable URL.

This is why the wrapper is designed to accept any URL and why no local target is
manufactured inside this component: inventing a runtime would make the DAST
stage appear end-to-end while testing nothing meaningful.

Note also that report writing has been exercised under macOS Docker Desktop
only. The `--user` flag is intended to make the same write succeed on the Linux
Jenkins agent, but that path has not yet been confirmed on the real agent.

## Expected CI Behaviour

```text
Build and Unit Test
        |
        v
SAST / SCA
        |
        v
Container Build and Scan
        |
        v
Test Deployment  (owned elsewhere; supplies a reachable URL)
        |
        v
run-dast.sh <target-url>
        |
        +---- PASS ----> Continue
        |
        +---- WARN ----> Continue (unless DAST_FAIL_ON_WARN=true)
        |
        +---- FAIL ----> Stop pipeline
        |
        +---- ERROR ---> Stop pipeline
```

Reports under `.ci/reports/` are archived as build evidence by the pipeline that
invokes the wrapper.

## Future Jenkins Integration

Documentation only. The Jenkinsfile belongs to the CI pipeline owner and is not
modified by this component.

When a reachable target exists, the pipeline can add an optional, gated stage
that calls the wrapper unchanged:

```groovy
stage('DAST') {
    when {
        expression { env.DAST_TARGET_URL?.trim() }
    }
    steps {
        sh './scripts/ci/run-dast.sh'
    }
}
```

The stage is skipped whenever `DAST_TARGET_URL` is unset, so the pipeline stays
green until a target is genuinely available, and the reports are picked up by
the existing `archiveArtifacts` step.
