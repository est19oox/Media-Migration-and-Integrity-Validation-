$ErrorActionPreference = "Stop"

$serviceDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repoRoot = (Resolve-Path (Join-Path $serviceDir "..\..")).Path
$composeFile = Join-Path $serviceDir "compose.yml"
$mavenWrapper = Join-Path $repoRoot "services\user-access-service\mvnw.cmd"
$examplesDir = Join-Path $repoRoot "contracts\events\v2\examples"

$reportingPort = if ($env:REPORTING_PORT) { $env:REPORTING_PORT } else { "8084" }
$rabbitPort = if ($env:RABBITMQ_MANAGEMENT_PORT) { $env:RABBITMQ_MANAGEMENT_PORT } else { "15672" }
$rabbitUser = if ($env:RABBITMQ_DEFAULT_USER) { $env:RABBITMQ_DEFAULT_USER } else { "mmiv" }
$rabbitPassword = if ($env:RABBITMQ_DEFAULT_PASS) { $env:RABBITMQ_DEFAULT_PASS } else { "mmiv-local" }
$migrationId = "c49f2f8f-ad97-45a8-8867-f3ca8a971d80"

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $Command,
        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Wait-ForHealth {
    $healthUri = "http://127.0.0.1:$reportingPort/actuator/health"

    for ($attempt = 1; $attempt -le 90; $attempt++) {
        try {
            $health = Invoke-RestMethod -UseBasicParsing -Uri $healthUri -TimeoutSec 3
            if ($health.status -eq "UP") {
                return
            }
        }
        catch {
            # The stack is still starting.
        }

        Start-Sleep -Seconds 2
    }

    throw "Reporting Service did not become healthy at $healthUri."
}

function Publish-Event {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FileName,
        [Parameter(Mandatory = $true)]
        [string] $RoutingKey
    )

    $payload = Get-Content -Raw (Join-Path $examplesDir $FileName)
    $event = $payload | ConvertFrom-Json
    $properties = [ordered]@{
            content_type = "application/json"
            delivery_mode = 2
            message_id = [string] $event.eventId
    }
    $request = [ordered]@{
        properties = $properties
        routing_key = $RoutingKey
        payload = [string] $payload
        payload_encoding = "string"
    }
    $requestBody = $request | ConvertTo-Json -Depth 4 -Compress

    $requestFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText(
            $requestFile,
            $requestBody,
            (New-Object Text.UTF8Encoding($false))
        )

        $response = & curl.exe `
            --fail `
            --silent `
            --show-error `
            --user "${rabbitUser}:${rabbitPassword}" `
            --header "content-type: application/json" `
            --data-binary "@$requestFile" `
            "http://127.0.0.1:$rabbitPort/api/exchanges/%2F/mmiv.events/publish"

        if ($LASTEXITCODE -ne 0) {
            throw "RabbitMQ publish request failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Remove-Item -LiteralPath $requestFile -Force -ErrorAction SilentlyContinue
    }

    $result = $response | ConvertFrom-Json

    if (-not $result.routed) {
        throw "RabbitMQ did not route $FileName with routing key $RoutingKey."
    }
}

function Wait-ForCompletedReport {
    $reportUri = "http://127.0.0.1:$reportingPort/api/reports/$migrationId"

    for ($attempt = 1; $attempt -le 60; $attempt++) {
        try {
            $report = Invoke-RestMethod -UseBasicParsing -Uri $reportUri -TimeoutSec 3
            if ($report.status -eq "COMPLETED") {
                return $report
            }
        }
        catch {
            # Events may not have been consumed yet.
        }

        Start-Sleep -Seconds 1
    }

    throw "Migration report $migrationId did not complete."
}

function Assert-CompletedReport {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Report
    )

    if ($Report.status -ne "COMPLETED" `
            -or $Report.summary.totalFiles -ne 1 `
            -or $Report.summary.successfulFiles -ne 1 `
            -or [double]$Report.summary.successRate -ne 100.0) {
        throw "Unexpected report result: $($Report | ConvertTo-Json -Depth 8 -Compress)"
    }
}

Push-Location $repoRoot
try {
    Invoke-CheckedCommand `
        -Description "Reporting Service build" `
        -Command {
            & $mavenWrapper -B -f services/reporting-service/pom.xml clean package
        }

    Invoke-CheckedCommand `
        -Description "Reporting stack startup" `
        -Command {
            docker compose -f $composeFile up --build --detach
        }

    Write-Host "Waiting for Reporting Service health..."
    Wait-ForHealth
    Write-Host "Reporting Service is healthy."

    $events = @(
        @{ File = "01-migration-started.json"; Key = "migration.started" },
        @{ File = "02-file-discovery-accepted.json"; Key = "discovery.file.completed" },
        @{ File = "04-migration-discovery-completed.json"; Key = "discovery.migration.completed" },
        @{ File = "05-file-transfer-completed.json"; Key = "transfer.file.completed" },
        @{ File = "06-integrity-validation-completed.json"; Key = "integrity.validation.completed" }
    )

    foreach ($item in $events) {
        Write-Host "Publishing $($item.File)..."
        Publish-Event -FileName $item.File -RoutingKey $item.Key
    }

    Write-Host "Waiting for the completed migration report..."
    $firstReport = Wait-ForCompletedReport
    Assert-CompletedReport -Report $firstReport

    Write-Host "Replaying the Integrity event..."
    Publish-Event `
        -FileName "06-integrity-validation-completed.json" `
        -RoutingKey "integrity.validation.completed"
    Start-Sleep -Seconds 2

    $duplicateReport = Invoke-RestMethod `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:$reportingPort/api/reports/$migrationId" `
        -TimeoutSec 3
    Assert-CompletedReport -Report $duplicateReport

    if ($duplicateReport.summary.totalFiles -ne $firstReport.summary.totalFiles `
            -or $duplicateReport.summary.successfulFiles -ne $firstReport.summary.successfulFiles) {
        throw "Duplicate delivery changed report totals."
    }

    Write-Host "PASS: Reporting smoke test completed and duplicate delivery was idempotent."
    Write-Host "Stack is still running. Stop it with:"
    Write-Host "docker compose -f services/reporting-service/compose.yml down"
    Write-Host "Use 'down --volumes' only when you intentionally want to delete local data."
}
catch {
    docker compose -f $composeFile logs --no-color reporting-service rabbitmq mongodb
    throw
}
finally {
    Pop-Location
}
