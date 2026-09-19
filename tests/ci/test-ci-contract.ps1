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
    Assert-FileContains $script @('SKIPPED:', 'require_command')
}

Assert-FileContains 'scripts/ci/run-tests.sh' @('pyproject\.toml', 'package\.json', 'pom\.xml')
Assert-FileContains 'scripts/ci/run-tests.sh' @(
    '/opt/jdk-25',
    'JAVA_HOME',
    'mvn --batch-mode test',
    'surefire-reports',
    'TEST-\*\.xml',
    'No application tests were executed'
)
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
    'ghcr\.io/zaproxy/zaproxy:stable',
    'zap-baseline\.py',
    '-J zap-report\.json',
    '-r zap-report\.html',
    'zap\.log',
    'tee'
)

$deploymentTriggerInterface = 'scripts/cd/trigger-deployment.sh <full-commit-sha>'
$deploymentTriggerPath = Join-Path $repositoryRoot 'scripts/cd/trigger-deployment.sh'
if (Test-Path -LiteralPath $deploymentTriggerPath -PathType Leaf) {
    $failures.Add("$deploymentTriggerInterface is externally owned and must not be implemented by this CI branch.")
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: CI script contract is present.'
exit 0
