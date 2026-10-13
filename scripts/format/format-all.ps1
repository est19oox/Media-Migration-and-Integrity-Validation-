$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$prettier = Join-Path $root 'node_modules\.bin\prettier.cmd'
$terraformRoot = Join-Path $root 'infrastructure'
$servicesRoot = Join-Path $root 'services'
$hasTerraform = $false

if (-not (Test-Path $prettier)) {
    throw "Pinned Prettier is not installed. Run 'npm ci' from $root."
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven (mvn) is required to format Java sources.'
}
if (-not (Test-Path $servicesRoot)) {
    throw "Services directory is missing: $servicesRoot"
}

$servicePoms = @(Get-ChildItem -Path $servicesRoot -Directory | ForEach-Object {
    $pom = Join-Path $_.FullName 'pom.xml'
    if (Test-Path $pom) { $pom }
})
if ($servicePoms.Count -eq 0) {
    throw "No Maven services found under $servicesRoot."
}

$hasTerraform = @(
    Get-ChildItem -Path $terraformRoot -Filter '*.tf' -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
).Count -gt 0
if ($hasTerraform) {
    if (-not (Get-Command terraform -ErrorAction SilentlyContinue)) {
        throw 'Terraform is required because infrastructure Terraform files exist.'
    }
}

Push-Location $root
try {
    & $prettier --write '**/*.html' '**/*.css' '**/*.js' '**/*.json' '**/*.md' '**/*.yml' '**/*.yaml'
    if ($LASTEXITCODE -ne 0) { throw "Prettier failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

foreach ($pom in $servicePoms) {
    Write-Host "Formatting Java sources for $pom"
    & mvn --file $pom spotless:apply
    if ($LASTEXITCODE -ne 0) { throw "Spotless failed for $pom with exit code $LASTEXITCODE." }
}

if ($hasTerraform) {
    & terraform -chdir="$terraformRoot" fmt -recursive
    if ($LASTEXITCODE -ne 0) { throw "terraform fmt failed with exit code $LASTEXITCODE." }
}
