$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-FileContains {
    param(
        [Parameter(Mandatory)] [string] $RelativePath,
        [Parameter(Mandatory)] [string[]] $Patterns
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $failures.Add("Missing file: $RelativePath")
        return
    }

    $content = Get-Content -Raw -LiteralPath $path
    foreach ($pattern in $Patterns) {
        if ($content -notmatch $pattern) {
            $failures.Add("$RelativePath does not contain required pattern: $pattern")
        }
    }
}

Assert-FileContains 'scripts/ci/common.sh' @(
    'PASS:',
    'SKIPPED:',
    'FAIL:',
    'require_command',
    'find_repository_root'
)

Assert-FileContains 'scripts/ci/validate-repository.sh' @(
    'README\.md',
    'infrastructure',
    '\.tfstate',
    '\.pem'
)

$conditionalScripts = @(
    'scripts/ci/run-tests.sh',
    'scripts/ci/run-sast.sh',
    'scripts/ci/run-sca.sh',
    'scripts/ci/run-terraform-checks.sh',
    'scripts/ci/run-iac-scan.sh',
    'scripts/ci/build-image.sh',
    'scripts/ci/scan-image.sh'
)

foreach ($script in $conditionalScripts) {
    if ($script -eq 'scripts/ci/run-tests.sh') {
        continue
    }
    Assert-FileContains $script @('SKIPPED:', 'require_command')
}

Assert-FileContains 'scripts/ci/run-tests.sh' @(
    '(?m)^\s*JAVA_HOME=/opt/jdk-25\r?$',
    '"\$ROOT"/services/\*/pom\.xml',
    'service_dir=\$\(dirname "\$pom"\)',
    'mvn --batch-mode clean verify',
    'validate_surefire_reports',
    'Missing Surefire report directory',
    'report_dir="\$service_dir/target/surefire-reports"',
    'TEST-\*\.xml',
    'report_count',
    'No Surefire TEST-\*\.xml reports found',
    'No application tests were executed',
    'failure\(s\)',
    'error\(s\)',
    'found_service=false',
    'skip ''No Maven microservices found',
    'skip.*No Maven microservices found'
)
$runTestsScript = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'scripts/ci/run-tests.sh')
foreach ($obsoletePattern in @('\$ROOT/pom\.xml', 'pyproject\.toml', 'package\.json', '\$ROOT/target/surefire-reports', '\$ROOT/src/main')) {
    if ($runTestsScript -match $obsoletePattern) {
        $failures.Add("scripts/ci/run-tests.sh contains obsolete root-application assumption: $obsoletePattern")
    }
}
if ($runTestsScript -match 'transfer-service|integrity-service|reporting-service|discovery-service|user-access-service') {
    $failures.Add('scripts/ci/run-tests.sh must discover services dynamically and must not hard-code service names.')
}
Assert-FileContains 'scripts/ci/run-sast.sh' @(
    'semgrep',
    'semgrep scan',
    'security/semgrep/semgrep\.yml',
    'p/java',
    '--error',
    '--json',
    'prepare_reports_directory',
    'semgrep\.json'
)
Assert-FileContains 'scripts/ci/run-sca.sh' @(
    'trivy fs',
    '--scanners vuln',
    '--severity HIGH,CRITICAL',
    '--exit-code 1',
    '--format json',
    'prepare_reports_directory',
    'trivy-sca\.json'
)
Assert-FileContains 'scripts/ci/run-terraform-checks.sh' @('terraform', 'fmt', 'validate')
Assert-FileContains 'scripts/ci/run-iac-scan.sh' @('checkov', 'infrastructure', 'checkov\.json')
Assert-FileContains 'scripts/ci/build-image.sh' @('docker', 'dockerfile', '--file', '\.ci/image-ref', 'TESTED_COMMIT')
Assert-FileContains 'scripts/ci/scan-image.sh' @('trivy', '\.ci/image-ref')
Assert-FileContains 'scripts/ci/verify-deployment.sh' @(
    'DEPLOYED_COMMIT',
    'DAST_TARGET_URL',
    'https\?://',
    'expected-commit'
)
Assert-FileContains 'scripts/ci/run-dast.sh' @(
    'curl --fail --silent --show-error --max-time 10',
    'ghcr\.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef',
    'zap-baseline\.py',
    '-J zap-report\.json',
    '-r zap-report\.html',
    'zap\.log',
    'tee'
)
$dastScript = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'scripts/ci/run-dast.sh')
if ($dastScript -match 'ghcr\.io/zaproxy/zaproxy:(stable|latest)') {
    $failures.Add('scripts/ci/run-dast.sh must not use a floating ZAP image tag.')
}

Assert-FileContains 'scripts/cd/trigger-deployment.sh' @(
    'commit=\$\{1:\?Usage: trigger-deployment\.sh <full-commit-sha>\}',
    'git rev-parse HEAD',
    '\.ci/image-ref',
    '\.ci/deployment\.env'
)

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: CI script contract is present.'
exit 0
