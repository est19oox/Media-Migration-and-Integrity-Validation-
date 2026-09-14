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
Assert-FileContains 'scripts/ci/run-tests.sh' @('/opt/jdk-25', 'JAVA_HOME')
Assert-FileContains 'scripts/ci/run-sast.sh' @(
    'semgrep',
    'security/semgrep/semgrep\.yml',
    'p/java',
    '--error',
    '--json',
    'semgrep\.json'
)
Assert-FileContains 'scripts/ci/run-sca.sh' @('trivy', 'HIGH,CRITICAL')
Assert-FileContains 'scripts/ci/run-terraform-checks.sh' @('terraform', 'fmt', 'validate')
Assert-FileContains 'scripts/ci/run-iac-scan.sh' @('checkov', 'infrastructure')
Assert-FileContains 'scripts/ci/build-image.sh' @('docker', '\.ci/image-ref')
Assert-FileContains 'scripts/ci/scan-image.sh' @('trivy', '\.ci/image-ref')

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: CI script contract is present.'
exit 0
