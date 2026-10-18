$ErrorActionPreference = "Stop"
$ComposeFile = Join-Path $PSScriptRoot "docker-compose.yml"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is not installed."
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not running." }
docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Compose v2 is required." }

$PreviousPassword = $env:ORACLE_PWD
try {
    if ([string]::IsNullOrEmpty($env:ORACLE_PWD)) {
        $SecurePassword = Read-Host "Enter Oracle development password" -AsSecureString
        $PasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecurePassword)
        try {
            $env:ORACLE_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($PasswordPointer)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($PasswordPointer)
        }
    }
    if ([string]::IsNullOrEmpty($env:ORACLE_PWD)) { throw "Password cannot be empty." }

    docker compose -f $ComposeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Invalid Compose configuration." }
    docker compose -f $ComposeFile up -d
    if ($LASTEXITCODE -ne 0) { throw "Failed to start Oracle. See database/README.md for existing containers." }
    docker compose -f $ComposeFile ps
    if ($LASTEXITCODE -ne 0) { throw "Failed to inspect Oracle container." }

    Write-Host "Oracle container started. Database initialization may take several minutes."
    Write-Host "Connection: localhost:1521/FREEPDB1"
    Write-Host 'Check readiness: docker inspect --format="{{.State.Health.Status}}" media-migration-oracle'
    Write-Host "Stop: docker stop media-migration-oracle"
    Write-Host "Start again: docker start media-migration-oracle"
}
finally {
    $env:ORACLE_PWD = $PreviousPassword
}
