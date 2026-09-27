[CmdletBinding()]
param(
    [ValidateRange(10, 600)]
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$environmentFile = Join-Path $repositoryRoot 'jenkins\.env'

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw 'Create jenkins/.env before running the Jenkins smoke test.'
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$adminId = $settings['JENKINS_ADMIN_ID']
$adminPassword = $settings['JENKINS_ADMIN_PASSWORD']
if ([string]::IsNullOrWhiteSpace($adminId) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
    throw 'jenkins/.env must define JENKINS_ADMIN_ID and JENKINS_ADMIN_PASSWORD.'
}

$portMapping = @(& docker port mmiv-jenkins 8080/tcp 2>$null) | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($portMapping) -or $portMapping -notmatch ':(\d+)$') {
    throw 'Cannot determine the published Jenkins port from the mmiv-jenkins container.'
}
$jenkinsPort = [int] $matches[1]
if ($jenkinsPort -lt 1 -or $jenkinsPort -gt 65535) {
    throw 'The published Jenkins port is outside the valid TCP port range.'
}
$healthUrl = "http://localhost:$jenkinsPort/login"
$jobUrl = "http://localhost:$jenkinsPort/job/mmiv-ci/api/json"

$credentialBytes = [Text.Encoding]::UTF8.GetBytes("${adminId}:${adminPassword}")
$headers = @{
    Authorization = "Basic $([Convert]::ToBase64String($credentialBytes))"
}
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    try {
        $healthResponse = Invoke-WebRequest -Uri $healthUrl -Method Get -TimeoutSec 5
        $jobResponse = Invoke-WebRequest -Uri $jobUrl -Method Get -Headers $headers -TimeoutSec 5
        if ($healthResponse.StatusCode -eq 200 -and $jobResponse.StatusCode -eq 200) {
            Write-Output 'PASS: Jenkins health endpoint and mmiv-ci job are ready.'
            exit 0
        }
    }
    catch {
        Write-Verbose 'Jenkins health endpoint or mmiv-ci job is not ready yet.'
    }

    Start-Sleep -Seconds 2
}

Write-Error "Jenkins health endpoint or mmiv-ci job did not become ready within $TimeoutSeconds seconds."
exit 1
