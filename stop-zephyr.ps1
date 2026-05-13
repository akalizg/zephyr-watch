$ErrorActionPreference = "Stop"

$root = (Resolve-Path $PSScriptRoot).Path

$patterns = @(
    "zephyr-api",
    "zephyr-flink-job",
    "zephyr-producer",
    "zephyr-ml",
    "OnlineInferenceJob",
    "AlertReviewJob",
    "RecommendJob",
    "SensorSocketServer",
    "mock_socket_client.py",
    "risk_model_service.py",
    "incremental_retrain.py",
    "spring-boot:run",
    "exec.mainClass=com.zephyr.watch"
)

function Test-ZephyrProcess {
    param([object]$Process)

    $commandLine = [string]$Process.CommandLine
    if ([string]::IsNullOrWhiteSpace($commandLine)) {
        return $false
    }

    if ($commandLine -notlike "*$root*" -and $commandLine -notmatch "com\.zephyr\.watch|zephyr-") {
        return $false
    }

    foreach ($pattern in $patterns) {
        if ($commandLine -like "*$pattern*") {
            return $true
        }
    }
    return $false
}

$candidates = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match "^(java|javaw|cmd|powershell|python|mvn|mvn\.cmd)\.exe$" } |
    Where-Object { Test-ZephyrProcess $_ } |
    Sort-Object ProcessId

if (-not $candidates -or $candidates.Count -eq 0) {
    Write-Host "No Zephyr-Watch runtime processes found."
    exit 0
}

Write-Host "Stopping Zephyr-Watch runtime processes:"
$candidates | ForEach-Object {
    $shortCommand = ([string]$_.CommandLine)
    if ($shortCommand.Length -gt 180) {
        $shortCommand = $shortCommand.Substring(0, 180) + "..."
    }
    Write-Host ("  PID={0} Name={1} Command={2}" -f $_.ProcessId, $_.Name, $shortCommand)
}

foreach ($process in $candidates) {
    try {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Warning ("Failed to stop PID={0}: {1}" -f $process.ProcessId, $_.Exception.Message)
    }
}

Start-Sleep -Seconds 2

$remaining = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match "^(java|javaw|cmd|powershell|python|mvn|mvn\.cmd)\.exe$" } |
    Where-Object { Test-ZephyrProcess $_ }

if ($remaining -and $remaining.Count -gt 0) {
    Write-Warning "Some Zephyr-Watch processes are still running:"
    $remaining | ForEach-Object {
        Write-Warning ("  PID={0} Name={1}" -f $_.ProcessId, $_.Name)
    }
    exit 1
}

Write-Host "All matched Zephyr-Watch runtime processes have been stopped."
