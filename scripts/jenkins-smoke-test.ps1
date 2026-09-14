[CmdletBinding()]
param(
    [ValidateRange(10, 600)]
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$healthUrl = 'http://localhost:8080/login'
$jobUrl = 'http://localhost:8080/job/mmiv-ci/api/json'
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
