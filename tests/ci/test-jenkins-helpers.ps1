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
    'jenkins\\\.env',
    '/job/mmiv-ci/api/json',
    'Authorization',
    'Basic',
    'Start-Sleep',
    'PASS:'
)
Assert-FileContains 'docs/jenkins/local-setup.md' @(
    'Docker Desktop',
    'jenkins-up\.ps1',
    'Docker socket',
    'mmiv-ci',
    'GitHub push',
    'two minutes',
    'Scan Multibranch Pipeline Now',
    'jenkins-down\.ps1'
)
Assert-FileContains 'README.md' @('Jenkins', 'automatic', 'docs/jenkins/local-setup\.md')

$setupGuide = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'docs/jenkins/local-setup.md')
if ($setupGuide -match 'mmiv-pipeline') {
    $failures.Add('Jenkins setup guide still refers to the obsolete mmiv-pipeline job.')
}
if ($setupGuide -match 'Copy the local project snapshot') {
    $failures.Add('Jenkins setup guide still describes local snapshot checkout.')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: Jenkins helper and documentation contract is present.'
exit 0
