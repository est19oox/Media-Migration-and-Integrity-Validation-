[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'jenkins\docker-compose.yml'
$environmentFile = Join-Path $repositoryRoot 'jenkins\.env'
$smokeTest = Join-Path $PSScriptRoot 'jenkins-smoke-test.ps1'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not running or the Docker Engine is unavailable.'
}

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw 'Create jenkins/.env from jenkins/.env.example and set a strong local password.'
}

$environmentContent = Get-Content -Raw -LiteralPath $environmentFile
if ($environmentContent -match 'replace-with-a-strong-local-password') {
    throw 'Replace the example JENKINS_ADMIN_PASSWORD in jenkins/.env before starting Jenkins.'
}

docker compose --env-file $environmentFile -f $composeFile up --detach --build
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose could not build or start Jenkins.'
}

& $smokeTest
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output 'Jenkins is available at http://localhost:8080'
exit 0
