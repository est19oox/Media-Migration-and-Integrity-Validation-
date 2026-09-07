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

Assert-FileContains 'scripts/jenkins-up.ps1' @(
    'docker info',
    'jenkins\\\.env',
    'replace-with-a-strong-local-password',
    'compose',
    '--build',
    'jenkins-smoke-test\.ps1'
)
Assert-FileContains 'scripts/jenkins-down.ps1' @(
    '\[switch\]\s*\$Purge',
    '--volumes',
    'compose',
    'down'
)
Assert-FileContains 'scripts/jenkins-smoke-test.ps1' @(
    'TimeoutSeconds',
    'http://localhost:8080/login',
    'Start-Sleep',
    'PASS:'
)
Assert-FileContains 'docs/jenkins/local-setup.md' @(
    'Docker Desktop',
    'jenkins-up\.ps1',
    'Docker socket',
    'mmiv-pipeline',
    'jenkins-down\.ps1'
)
Assert-FileContains 'README.md' @('Jenkins', 'docs/jenkins/local-setup\.md')

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: Jenkins helper and documentation contract is present.'
exit 0
