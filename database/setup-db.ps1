$ErrorActionPreference = "Stop"

$ContainerName = "media-migration-oracle"
$ImageName     = "container-registry.oracle.com/database/free:latest"
$VolumeName    = "media-migration-oracle-data"

Write-Host "========================================"
Write-Host " Media Migration - Oracle Setup"
Write-Host "========================================"

# Check Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not installed."
    exit 1
}

Write-Host "Checking Docker..."

docker info *> $null

if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker Desktop is not running."
    exit 1
}

Write-Host "Docker is running."

# Check whether Oracle already exists
$ExistingContainer = docker ps -a `
    --filter "name=^/$ContainerName$" `
    --format "{{.Names}}"

if ($ExistingContainer -eq $ContainerName) {

    Write-Host ""
    Write-Host "Oracle already exists."
    Write-Host "Starting Oracle..."

    docker start $ContainerName | Out-Null
}
else {

    # Ask developer for local Oracle password
    $OraclePassword = Read-Host "Enter Oracle development password"

    Write-Host ""
    Write-Host "Downloading Oracle..."

    docker pull $ImageName

    Write-Host ""
    Write-Host "Creating Oracle..."

    docker run `
        --name $ContainerName `
        -p 1521:1521 `
        -e "ORACLE_PWD=$OraclePassword" `
        -v "${VolumeName}:/opt/oracle/oradata" `
        -d `
        $ImageName
}

Write-Host ""
Write-Host "========================================"
Write-Host " Oracle Setup Complete"
Write-Host "========================================"

docker ps --filter "name=$ContainerName"

Write-Host ""
Write-Host "Connection Details:"
Write-Host "Host         : localhost"
Write-Host "Port         : 1521"
Write-Host "Service Name : FREEPDB1"
Write-Host ""
Write-Host "To stop Oracle:"
Write-Host "docker stop $ContainerName"
Write-Host ""
Write-Host "To start Oracle again:"
Write-Host "docker start $ContainerName"