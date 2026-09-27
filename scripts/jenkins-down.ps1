[CmdletBinding()]
param(
    [switch] $Purge
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'jenkins\docker-compose.yml'
$environmentFile = Join-Path $repositoryRoot 'jenkins\.env'

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw 'jenkins/.env is required so Docker Compose can resolve the local configuration.'
}

$arguments = @('compose', '--env-file', $environmentFile, '-f', $composeFile, 'down')
if ($Purge) {
    $arguments += '--volumes'
    Write-Warning 'Purging the mmiv_jenkins_home volume and all local Jenkins configuration.'
}

& docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose could not stop Jenkins.'
}

if (-not $Purge) {
    Write-Output 'Jenkins stopped. The mmiv_jenkins_home volume was preserved.'
}
exit 0
