$ErrorActionPreference = "Stop"

function Get-DashboardOverview {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/dashboard/overview" -Method Get
    return $response.data
}

function Assert-Health {
    $apiHealth = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get
    if ($apiHealth.status -ne "UP") {
        throw "Spring Boot API is not healthy."
    }

    $modelHealth = Invoke-RestMethod -Uri "http://localhost:5001/api/risk/health" -Method Get
    if (-not $modelHealth.success) {
        throw "Risk model service health check failed."
    }

    $socketCheck = Test-NetConnection 127.0.0.1 -Port 9999 -WarningAction SilentlyContinue
    if (-not $socketCheck.TcpTestSucceeded) {
        throw "SensorSocketServer port 9999 is not accepting connections."
    }
}

function Run-SocketClientBurst {
    $python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
    if (-not (Test-Path $python)) {
        throw "Python executable not found: $python"
    }

    $env:ZEPHYR_SOCKET_SEND_INTERVAL_SEC = "0.01"
    & $python ".\zephyr-producer\mock_socket_client.py"
    Remove-Item Env:\ZEPHYR_SOCKET_SEND_INTERVAL_SEC -ErrorAction SilentlyContinue
}

Assert-Health
$before = Get-DashboardOverview
Write-Host "Before: predictionCount=$($before.predictionCount), alertCount=$($before.alertCount)"

Run-SocketClientBurst
Start-Sleep -Seconds 5

$after = Get-DashboardOverview
Write-Host "After : predictionCount=$($after.predictionCount), alertCount=$($after.alertCount)"

if ($after.predictionCount -le $before.predictionCount) {
    throw "predictionCount did not increase after socket client burst."
}

Write-Host "Socket flow verification passed."
