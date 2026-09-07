[CmdletBinding()]
param(
    [ValidateRange(10, 600)]
    [int] $TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$healthUrl = 'http://localhost:8080/login'
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    try {
        $response = Invoke-WebRequest -Uri $healthUrl -Method Get -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-Output 'PASS: Jenkins health endpoint is ready.'
            exit 0
        }
    }
    catch {
        Write-Verbose "Jenkins is not ready yet: $($_.Exception.Message)"
    }

    Start-Sleep -Seconds 2
}

Write-Error "Jenkins did not become ready within $TimeoutSeconds seconds."
exit 1
