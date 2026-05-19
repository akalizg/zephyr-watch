param(
    [ValidateSet("rest-async", "rest-sync", "invalid-on", "invalid-off", "onnx", "tf-serving", "default")]
    [string]$Scenario = "rest-async",
    [switch]$NoRestart,
    [switch]$SkipHealth,
    [switch]$ShowWindow,
    [int]$WaitSeconds = 35,
    [string]$PmmlPath = "zephyr-flink-job/src/main/resources/models/model.pmml",
    [string]$ModelVersion = "",
    [string]$ModelServiceUrl = "",
    [string]$DebugPrint = "true"
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path $PSScriptRoot).Path
$envFile = Join-Path $root ".env"
$logDir = Join-Path $root "logs\task-a"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Read-ZephyrEnvFile {
    if (-not (Test-Path $envFile)) {
        Write-Host "[INFO] .env not found. Using process environment and built-in defaults."
        return
    }

    Get-Content -Encoding UTF8 $envFile | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            return
        }
        $eq = $line.IndexOf("=")
        if ($eq -le 0) {
            return
        }
        $name = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim()
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Set-ZephyrEnv {
    param([hashtable]$Values)

    foreach ($key in $Values.Keys) {
        [Environment]::SetEnvironmentVariable($key, [string]$Values[$key], "Process")
    }
}

function Get-ZephyrEnv {
    param([string]$Name, [string]$DefaultValue = "")

    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value
}

function Convert-ToSafeSingleQuotedString {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return ""
    }
    return $Value.Replace("'", "''")
}

function Stop-OnlineInferenceJob {
    $escapedRoot = [regex]::Escape($root)
    $targets = Get-CimInstance Win32_Process |
        Where-Object { $_.Name -match "^(java|javaw|cmd|powershell|mvn|mvn\.cmd)\.exe$" } |
        Where-Object {
            $cmd = [string]$_.CommandLine
            $cmd -match $escapedRoot -and
            ($cmd -match "OnlineInferenceJob" -or $cmd -match "start-online-inference")
        } |
        Sort-Object ProcessId

    if (-not $targets -or $targets.Count -eq 0) {
        Write-Host "[INFO] No existing OnlineInferenceJob process found."
        return
    }

    Write-Host "[INFO] Stopping OnlineInferenceJob only:"
    foreach ($process in $targets) {
        Write-Host ("  PID={0} Name={1}" -f $process.ProcessId, $process.Name)
        try {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
        } catch {
            Write-Warning ("Failed to stop PID={0}: {1}" -f $process.ProcessId, $_.Exception.Message)
        }
    }
    Start-Sleep -Seconds 2
}

function Resolve-JavaHomeForScenario {
    $javaHome = Get-ZephyrEnv "ZEPHYR_JAVA_HOME" (Get-ZephyrEnv "JAVA_HOME" "")
    if ($Scenario -eq "onnx") {
        $onnxJavaHome = Get-ZephyrEnv "ZEPHYR_ONNX_JAVA_HOME" $javaHome
        if (-not [string]::IsNullOrWhiteSpace($onnxJavaHome) -and (Test-Path (Join-Path $onnxJavaHome "bin\java.exe"))) {
            return $onnxJavaHome
        }
        Write-Warning "ZEPHYR_ONNX_JAVA_HOME is empty or invalid. Falling back to JAVA_HOME."
    }
    return $javaHome
}

function Start-OnlineInferenceJob {
    $javaHome = Resolve-JavaHomeForScenario
    if (-not [string]::IsNullOrWhiteSpace($javaHome) -and (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
        $env:JAVA_HOME = $javaHome
        $env:PATH = "$javaHome\bin;$env:PATH"
    }

    if ($Scenario -eq "onnx") {
        $env:MAVEN_OPTS = "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED"
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stdout = Join-Path $logDir "$Scenario-$timestamp.out.log"
    $stderr = Join-Path $logDir "$Scenario-$timestamp.err.log"
    $launcher = Join-Path $logDir "$Scenario-$timestamp-launcher.ps1"

    Write-Host "[INFO] Starting OnlineInferenceJob for scenario: $Scenario"
    Write-Host "[INFO] Logs:"
    Write-Host "  $stdout"
    Write-Host "  $stderr"
    $safeRoot = Convert-ToSafeSingleQuotedString $root
    $safeJavaHome = Convert-ToSafeSingleQuotedString $javaHome
    $safeMavenOpts = Convert-ToSafeSingleQuotedString $env:MAVEN_OPTS
    $safePmmlPath = Convert-ToSafeSingleQuotedString $PmmlPath
    $safeModelVersion = Convert-ToSafeSingleQuotedString $ModelVersion
    $safeDebugPrint = Convert-ToSafeSingleQuotedString $DebugPrint
    $safeModelServiceUrl = Convert-ToSafeSingleQuotedString $ModelServiceUrl
    $safeStdout = Convert-ToSafeSingleQuotedString $stdout
    $safeStderr = Convert-ToSafeSingleQuotedString $stderr

    if ($ShowWindow) {
        $launcherContent = @"
Set-Location '$safeRoot'
if ('$safeJavaHome' -ne '') {
    `$env:JAVA_HOME = '$safeJavaHome'
    `$env:PATH = '$safeJavaHome\bin;' + `$env:PATH
}
if ('$safeMavenOpts' -ne '') {
    `$env:MAVEN_OPTS = '$safeMavenOpts'
}
& mvn.cmd -q -f 'zephyr-flink-job/pom.xml' org.codehaus.mojo:exec-maven-plugin:3.3.0:java '-Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob' '-Dexec.args=$safePmmlPath $safeModelVersion $safeDebugPrint $safeModelServiceUrl' 2>&1 | Tee-Object -FilePath '$safeStdout' -Append
"@
    } else {
        $launcherContent = @"
Set-Location '$safeRoot'
if ('$safeJavaHome' -ne '') {
    `$env:JAVA_HOME = '$safeJavaHome'
    `$env:PATH = '$safeJavaHome\bin;' + `$env:PATH
}
if ('$safeMavenOpts' -ne '') {
    `$env:MAVEN_OPTS = '$safeMavenOpts'
}
& mvn.cmd -q -f 'zephyr-flink-job/pom.xml' org.codehaus.mojo:exec-maven-plugin:3.3.0:java '-Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob' '-Dexec.args=$safePmmlPath $safeModelVersion $safeDebugPrint $safeModelServiceUrl' 1>> '$safeStdout' 2>> '$safeStderr'
"@
    }
    Set-Content -Path $launcher -Value $launcherContent -Encoding UTF8

    $startParams = @{
        FilePath = "powershell.exe"
        ArgumentList = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $launcher
        )
        WorkingDirectory = $root
    }
    if (-not $ShowWindow) {
        $startParams.WindowStyle = "Hidden"
    } else {
        $startParams.ArgumentList = @(
            "-NoExit",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $launcher
        )
    }
    Start-Process @startParams | Out-Null

    return @{
        Stdout = $stdout
        Stderr = $stderr
        Launcher = $launcher
    }
}

function Assert-HttpHealth {
    param([string]$Name, [string]$Uri, [string]$ExpectedText)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 6
        $text = if ($response.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($response.Content)
        } else {
            [string]$response.Content
        }
        if ($ExpectedText -and $text -notmatch [regex]::Escape($ExpectedText)) {
            throw "Unexpected response: $text"
        }
        Write-Host "[PASS] $Name $Uri"
    } catch {
        Write-Warning "$Name check failed: $($_.Exception.Message)"
    }
}

function Wait-ForLogEvidence {
    param([string]$Path, [string[]]$RequiredPatterns, [string[]]$ForbiddenPatterns = @())

    if (-not (Test-Path $Path)) {
        Write-Warning "Log file does not exist yet: $Path"
        return
    }

    $content = Get-Content -Raw -Encoding UTF8 $Path
    foreach ($pattern in $RequiredPatterns) {
        if ($content -match [regex]::Escape($pattern)) {
            Write-Host "[PASS] log contains: $pattern"
        } else {
            Write-Warning "log missing: $pattern"
        }
    }
    foreach ($pattern in $ForbiddenPatterns) {
        if ($content -match [regex]::Escape($pattern)) {
            Write-Warning "log contains forbidden text: $pattern"
        } else {
            Write-Host "[PASS] log does not contain: $pattern"
        }
    }
}

Read-ZephyrEnvFile

if ([string]::IsNullOrWhiteSpace($ModelVersion)) {
    $ModelVersion = switch ($Scenario) {
        "onnx" { "risk-classifier-onnx-v1" }
        "tf-serving" { "risk-classifier-tf-serving-v1" }
        default { "risk-classifier-rest-v1" }
    }
}

if ([string]::IsNullOrWhiteSpace($ModelServiceUrl)) {
    $ModelServiceUrl = Get-ZephyrEnv "ZEPHYR_MODEL_SERVICE_URL" "http://localhost:5001/api/risk/score"
}

$scenarioEnv = switch ($Scenario) {
    "rest-async" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "rest"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
        }
    }
    "rest-sync" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "rest"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "true"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
        }
    }
    "invalid-on" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "rest"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "true"
        }
    }
    "invalid-off" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "rest"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
        }
    }
    "onnx" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "onnx"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
            ZEPHYR_ONNX_MODEL_PATH = (Get-ZephyrEnv "ZEPHYR_ONNX_MODEL_PATH" (Join-Path $root "zephyr-ml\models\best_risk_model.onnx"))
            ZEPHYR_ONNX_INPUT_NAME = (Get-ZephyrEnv "ZEPHYR_ONNX_INPUT_NAME" "input")
            ZEPHYR_ONNX_OUTPUT_NAME = (Get-ZephyrEnv "ZEPHYR_ONNX_OUTPUT_NAME" "")
        }
    }
    "tf-serving" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "tf-serving"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
            ZEPHYR_TF_SERVING_URL = (Get-ZephyrEnv "ZEPHYR_TF_SERVING_URL" "http://localhost:8501/v1/models/risk_classifier:predict")
            ZEPHYR_TF_SERVING_INPUT_NAME = (Get-ZephyrEnv "ZEPHYR_TF_SERVING_INPUT_NAME" "inputs")
            ZEPHYR_TF_SERVING_SIGNATURE_NAME = (Get-ZephyrEnv "ZEPHYR_TF_SERVING_SIGNATURE_NAME" "serving_default")
        }
    }
    "default" {
        @{
            ZEPHYR_RISK_INFERENCE_BACKEND = "rest"
            ZEPHYR_FORCE_SYNC_REST_INFERENCE = "false"
            ZEPHYR_ENABLE_INVALID_SENSOR_SINK = "false"
        }
    }
}

Set-ZephyrEnv $scenarioEnv

Write-Host "============================================================"
Write-Host "Task A verification scenario: $Scenario"
Write-Host "Project: $root"
Write-Host "Model version: $ModelVersion"
Write-Host "Risk API: $ModelServiceUrl"
Write-Host "Effective scenario variables:"
$scenarioEnv.GetEnumerator() | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0}={1}" -f $_.Name, $_.Value)
}
Write-Host "============================================================"

if (-not $SkipHealth) {
    Assert-HttpHealth -Name "API health" -Uri "http://localhost:$(Get-ZephyrEnv "ZEPHYR_API_PORT" "8080")/actuator/health" -ExpectedText '"UP"'
    Assert-HttpHealth -Name "Risk model health" -Uri "http://localhost:5001/api/risk/health" -ExpectedText '"success"'
    if ($Scenario -eq "tf-serving") {
        Assert-HttpHealth -Name "TF Serving model" -Uri "http://localhost:8501/v1/models/risk_classifier" -ExpectedText "AVAILABLE"
    }
}

$logs = $null
if (-not $NoRestart) {
    Stop-OnlineInferenceJob
    $logs = Start-OnlineInferenceJob
    Write-Host "[INFO] Waiting $WaitSeconds seconds before checking evidence..."
    Start-Sleep -Seconds $WaitSeconds
} else {
    Write-Host "[INFO] -NoRestart was set. Skipping OnlineInferenceJob restart."
    $latest = Get-ChildItem -Path $logDir -Filter "$Scenario-*.out.log" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($latest) {
        $logs = @{ Stdout = $latest.FullName; Stderr = "" }
    }
}

if ($logs) {
    $required = switch ($Scenario) {
        "rest-async" { @("riskInferenceBackend=rest", "useAsyncRestMainline=true", "forceSyncRestInference=false") }
        "rest-sync" { @("riskInferenceBackend=rest", "useAsyncRestMainline=false", "ZEPHYR_FORCE_SYNC_REST_INFERENCE=true, fallback to sync REST inference.") }
        "invalid-on" { @("enableInvalidSensorSink=true") }
        "invalid-off" { @("enableInvalidSensorSink=false") }
        "onnx" { @("riskInferenceBackend=onnx", "ZEPHYR_ONNX_BACKEND_ACTIVE", "MultiBackendRiskPredictFunction ONNX initialized") }
        "tf-serving" { @("riskInferenceBackend=tf-serving") }
        "default" { @("riskInferenceBackend=rest", "useAsyncRestMainline=true", "enableInvalidSensorSink=false") }
    }
    $forbidden = if ($Scenario -eq "onnx") { @("ONNX unavailable, bridging to REST") } else { @() }
    Wait-ForLogEvidence -Path $logs.Stdout -RequiredPatterns $required -ForbiddenPatterns $forbidden
}

Write-Host ""
Write-Host "Next quick evidence commands:"
Write-Host "  REST/ONNX/TF result: query device_risk_prediction by model_version for recent rows."
Write-Host "  invalid-on/off: send a marked bad Kafka message and consume invalid_sensor_topic."
Write-Host "  logs: Get-Content -Tail 80 $($logs.Stdout)"
