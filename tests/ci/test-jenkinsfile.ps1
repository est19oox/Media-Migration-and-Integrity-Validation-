$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jenkinsfilePath = Join-Path $repositoryRoot 'Jenkinsfile'

if (-not (Test-Path -LiteralPath $jenkinsfilePath -PathType Leaf)) {
    Write-Error 'Missing file: Jenkinsfile'
    exit 1
}

$jenkinsfile = Get-Content -Raw -LiteralPath $jenkinsfilePath
$failures = [System.Collections.Generic.List[string]]::new()

$requiredPatterns = @(
    'pipeline\s*\{',
    'disableConcurrentBuilds',
    'buildDiscarder',
    'timeout\(',
    'timestamps\(\)',
    "stage\('Checkout'\)",
    "stage\('Validate'\)",
    "stage\('Unit Test'\)",
    "stage\('SAST'\)",
    "stage\('SCA'\)",
    "stage\('Terraform Validation'\)",
    "stage\('IaC Scan'\)",
    "stage\('Container Build'\)",
    "stage\('Container Scan'\)",
    'archiveArtifacts',
    'checkout\s+scm',
    'env\.TESTED_COMMIT\s*=\s*sh\s*\(',
    "script:\s*'git rev-parse HEAD'",
    'returnStdout:\s*true',
    'currentBuild\.description',
    'BRANCH_NAME',
    'GIT_COMMIT',
    'scripts/ci/validate-repository\.sh',
    'scripts/ci/run-tests\.sh',
    'scripts/ci/run-sast\.sh',
    'scripts/ci/run-sca\.sh',
    'scripts/ci/run-terraform-checks\.sh',
    'scripts/ci/run-iac-scan\.sh',
    'scripts/ci/build-image\.sh',
    'scripts/ci/scan-image\.sh'
)

foreach ($pattern in $requiredPatterns) {
    if ($jenkinsfile -notmatch $pattern) {
        $failures.Add("Jenkinsfile does not contain required pattern: $pattern")
    }
}

if ($jenkinsfile -match 'terraform\s+apply') {
    $failures.Add('Jenkinsfile must not run terraform apply in the initial pipeline.')
}
if ($jenkinsfile -match '(?i)aws_access_key_id|aws_secret_access_key') {
    $failures.Add('Jenkinsfile must not contain AWS credential fields.')
}
if ($jenkinsfile -match '/project') {
    $failures.Add('Jenkinsfile must not copy source from the local /project mount.')
}
if ($jenkinsfile -match 'tar\s+--exclude') {
    $failures.Add('Jenkinsfile must use SCM checkout instead of creating a local tar snapshot.')
}
if ($jenkinsfile -notmatch 'if\s*\(\s*!env\.TESTED_COMMIT\s*\)') {
    $failures.Add('Jenkinsfile must fail validation when TESTED_COMMIT is empty.')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: Jenkinsfile contract is present.'
exit 0
