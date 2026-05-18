@echo off
setlocal EnableExtensions

rem ============================================================================
rem Zephyr-Watch startup template (team baseline)
rem
rem Usage:
rem   1) Copy this file to your own launcher (e.g. run-all-local.bat), OR run it directly.
rem   2) Put machine-specific values in repo-root ".env" (recommended).
rem   3) Do NOT commit personal passwords/paths in ".env".
rem
rem Team Grafana mode in this template:
rem   Docker Compose (zephyr-dashboard/docker-compose.grafana.yml)
rem ============================================================================

set "ROOT=%~dp0"
cd /d "%ROOT%"

if exist "%ROOT%.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%.env") do (
        if not "%%A"=="" set "%%A=%%B"
    )
)

rem ----------------------------------------------------------------------------
rem User-tunable defaults (prefer setting these in .env)
rem ----------------------------------------------------------------------------
rem Example:
rem   ZEPHYR_JAVA_HOME=C:\Program Files\Java\jdk1.8.0_381
rem   ZEPHYR_ONNX_JAVA_HOME=C:\Program Files\Java\jdk-21
rem   ZEPHYR_PYTHON_EXE=D:\path\to\project\.venv\Scripts\python.exe
rem   ZEPHYR_MYSQL_HOST=127.0.0.1
rem   ZEPHYR_MYSQL_PORT=3306

set "PREFERRED_JAVA_HOME=%ZEPHYR_JAVA_HOME%"
if "%PREFERRED_JAVA_HOME%"=="" set "PREFERRED_JAVA_HOME=%JAVA_HOME%"
if exist "%PREFERRED_JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=%PREFERRED_JAVA_HOME%"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

set "PMML_PATH=%~1"
if "%PMML_PATH%"=="" set "PMML_PATH=zephyr-flink-job/src/main/resources/models/model.pmml"

set "MODEL_VERSION=%~2"
if "%MODEL_VERSION%"=="" set "MODEL_VERSION=risk-classifier-rest-v1"

set "DEBUG_PRINT=%~3"
if "%DEBUG_PRINT%"=="" set "DEBUG_PRINT=false"

set "MODEL_SERVICE_URL=%~4"
if "%MODEL_SERVICE_URL%"=="" set "MODEL_SERVICE_URL=http://localhost:5001/api/risk/score"

set "ASYNC_RISK_INFERENCE=%~5"
if "%ASYNC_RISK_INFERENCE%"=="" set "ASYNC_RISK_INFERENCE=%ZEPHYR_ENABLE_ASYNC_RISK_INFERENCE%"
if "%ASYNC_RISK_INFERENCE%"=="" set "ASYNC_RISK_INFERENCE=false"
set "ZEPHYR_ENABLE_ASYNC_RISK_INFERENCE=%ASYNC_RISK_INFERENCE%"

if "%ZEPHYR_RISK_INFERENCE_BACKEND%"=="" set "ZEPHYR_RISK_INFERENCE_BACKEND=rest"
if "%ZEPHYR_FORCE_SYNC_REST_INFERENCE%"=="" set "ZEPHYR_FORCE_SYNC_REST_INFERENCE=false"

if "%ZEPHYR_ONNX_JAVA_HOME%"=="" set "ZEPHYR_ONNX_JAVA_HOME=%JAVA_HOME%"

set "ONLINE_JOB_JAVA_HOME=%JAVA_HOME%"
set "ONLINE_JOB_MAVEN_OPTS="
if /i "%ZEPHYR_ENABLE_ASYNC_RISK_INFERENCE%"=="true" (
    rem Legacy flag retained for backward compatibility.
    rem REST mainline is async by backend routing in OnlineInferenceJob.
) else if /i "%ZEPHYR_RISK_INFERENCE_BACKEND%"=="onnx" (
    if exist "%ZEPHYR_ONNX_JAVA_HOME%\bin\java.exe" (
        set "ONLINE_JOB_JAVA_HOME=%ZEPHYR_ONNX_JAVA_HOME%"
        set "ONLINE_JOB_MAVEN_OPTS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED"
    ) else (
        echo [WARN] ONNX backend requested but ZEPHYR_ONNX_JAVA_HOME is invalid: %ZEPHYR_ONNX_JAVA_HOME%
        echo [WARN] OnlineInferenceJob will continue with default JAVA_HOME: %JAVA_HOME%
    )
)

if "%ZEPHYR_ASYNC_RISK_INFERENCE_TIMEOUT_MS%"=="" set "ZEPHYR_ASYNC_RISK_INFERENCE_TIMEOUT_MS=8000"
if "%ZEPHYR_ASYNC_RISK_INFERENCE_CAPACITY%"=="" set "ZEPHYR_ASYNC_RISK_INFERENCE_CAPACITY=100"

if "%ZEPHYR_AUTO_BOOTSTRAP_MODEL%"=="" set "ZEPHYR_AUTO_BOOTSTRAP_MODEL=true"
if "%ZEPHYR_AUTO_START_MYSQL%"=="" set "ZEPHYR_AUTO_START_MYSQL=true"
if "%ZEPHYR_AUTO_START_GRAFANA%"=="" set "ZEPHYR_AUTO_START_GRAFANA=true"
if "%ZEPHYR_GRAFANA_PORT%"=="" set "ZEPHYR_GRAFANA_PORT=3000"
if "%ZEPHYR_API_PORT%"=="" set "ZEPHYR_API_PORT=8080"
if "%ZEPHYR_MYSQL_HOST%"=="" set "ZEPHYR_MYSQL_HOST=127.0.0.1"
if "%ZEPHYR_MYSQL_PORT%"=="" set "ZEPHYR_MYSQL_PORT=3306"
if "%ZEPHYR_MYSQL_SERVICE_NAMES%"=="" set "ZEPHYR_MYSQL_SERVICE_NAMES=MySQL80 MySQL mysql"
if "%ZEPHYR_GRAFANA_COMPOSE_FILE%"=="" set "ZEPHYR_GRAFANA_COMPOSE_FILE=%ROOT%zephyr-dashboard\docker-compose.grafana.yml"

set "PYTHON_EXE=%ZEPHYR_PYTHON_EXE%"
if "%PYTHON_EXE%"=="" set "PYTHON_EXE=%ROOT%.venv\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=%ROOT%zephyr-ml\.venv1\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"

echo ============================================================
echo Zephyr-Watch one-click launcher ^(template^)
echo Project: %ROOT%
echo JAVA_HOME: %JAVA_HOME%
echo Python:  %PYTHON_EXE%
echo MySQL:   %ZEPHYR_MYSQL_HOST%:%ZEPHYR_MYSQL_PORT%
echo Grafana: http://localhost:%ZEPHYR_GRAFANA_PORT% ^(Docker Compose^)
echo Grafana compose: %ZEPHYR_GRAFANA_COMPOSE_FILE%
echo PMML:    %PMML_PATH%
echo Model:   %MODEL_VERSION%
echo Debug:   %DEBUG_PRINT%
echo Risk API:%MODEL_SERVICE_URL%
echo Async legacy flag: %ZEPHYR_ENABLE_ASYNC_RISK_INFERENCE%
echo Backend: %ZEPHYR_RISK_INFERENCE_BACKEND%
echo Force sync REST fallback: %ZEPHYR_FORCE_SYNC_REST_INFERENCE%
echo OnlineInferenceJob JAVA_HOME: %ONLINE_JOB_JAVA_HOME%
if not "%ONLINE_JOB_MAVEN_OPTS%"=="" echo OnlineInferenceJob MAVEN_OPTS: %ONLINE_JOB_MAVEN_OPTS%
echo Auto model bootstrap: %ZEPHYR_AUTO_BOOTSTRAP_MODEL%
echo ============================================================
echo.
echo Make sure Kafka, MySQL, Redis, HDFS/Hive and other external
echo services configured in application.yml and StorageConfig are running.
echo.

where powershell >nul 2>nul
if errorlevel 1 (
    echo [ERROR] PowerShell was not found in PATH.
    echo This script needs PowerShell for dependency preflight checks.
    pause
    exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Maven command "mvn" was not found in PATH.
    echo Please install Maven or add it to PATH, then run this script again.
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java command "java" was not found in PATH.
    echo Please install JDK 8 and add it to PATH, then run this script again.
    pause
    exit /b 1
)

echo [Preflight] Checking MySQL on %ZEPHYR_MYSQL_HOST%:%ZEPHYR_MYSQL_PORT%...
call :check_tcp_port "%ZEPHYR_MYSQL_HOST%" "%ZEPHYR_MYSQL_PORT%"
if errorlevel 1 (
    echo [WARN] MySQL is not reachable.
    if /i "%ZEPHYR_AUTO_START_MYSQL%"=="true" (
        if /i "%ZEPHYR_MYSQL_HOST%"=="localhost" (
            call :start_mysql_services
        )
        if "%ZEPHYR_MYSQL_HOST%"=="127.0.0.1" (
            call :start_mysql_services
        )
        call :wait_tcp_port "%ZEPHYR_MYSQL_HOST%" "%ZEPHYR_MYSQL_PORT%" 30
        if errorlevel 1 (
            echo [ERROR] MySQL is still unavailable on %ZEPHYR_MYSQL_HOST%:%ZEPHYR_MYSQL_PORT%.
            echo Please start MySQL manually, then rerun this script.
            pause
            exit /b 1
        )
    ) else (
        echo [ERROR] MySQL is unavailable and ZEPHYR_AUTO_START_MYSQL is disabled.
        pause
        exit /b 1
    )
)
echo [OK] MySQL is reachable.

if /i "%ZEPHYR_AUTO_START_GRAFANA%"=="true" (
    echo [Preflight] Ensuring Grafana is running on port %ZEPHYR_GRAFANA_PORT%...
    call :check_tcp_port "127.0.0.1" "%ZEPHYR_GRAFANA_PORT%"
    if errorlevel 1 (
        call :start_grafana_compose
        call :wait_tcp_port "127.0.0.1" "%ZEPHYR_GRAFANA_PORT%" 40
        if errorlevel 1 (
            echo [WARN] Grafana was not reachable after startup attempt.
            echo        You can continue project startup; dashboard can be started later.
        ) else (
            echo [OK] Grafana is reachable.
        )
    ) else (
        echo [OK] Grafana is already running.
    )
)

echo [1/7] Building and installing all modules...
call mvn -q -DskipTests install
if errorlevel 1 (
    echo [ERROR] Maven package failed. Fix the build errors above and retry.
    pause
    exit /b 1
)

echo.
echo [2/7] Starting Spring Boot API...
start "Zephyr API" /D "%ROOT%" cmd /k "mvn -q -f zephyr-api/pom.xml org.springframework.boot:spring-boot-maven-plugin:2.7.18:run"

echo Waiting for Spring Boot API health check...
set "API_HEALTH_URL=http://localhost:%ZEPHYR_API_PORT%/actuator/health"
set "API_WAIT_MAX=60"
set /a API_WAIT_COUNT=0
:wait_api
curl.exe -fsS "%API_HEALTH_URL%" >nul 2>nul
if not errorlevel 1 goto api_ready
set /a API_WAIT_COUNT+=1
if %API_WAIT_COUNT% geq %API_WAIT_MAX% goto api_timeout
timeout /t 2 /nobreak >nul
goto wait_api

:api_ready
echo Spring Boot API is ready.

echo [3/7] Starting Python Risk Model Service...
start "Zephyr Risk Model Service" /D "%ROOT%zephyr-ml" cmd /k ""%PYTHON_EXE%" serve\risk_model_service.py"
goto continue_startup

:api_timeout
echo [ERROR] Spring Boot API did not become healthy within the expected time.
echo Please inspect the "Zephyr API" window, then rerun this script.
pause
exit /b 1

:continue_startup

echo [4/7] Starting OnlineInferenceJob...
set "ONLINE_JOB_LAUNCHER=%ROOT%start-online-inference.bat"
> "%ONLINE_JOB_LAUNCHER%" (
    echo @echo off
    echo setlocal EnableExtensions
    echo set "JAVA_HOME=%ONLINE_JOB_JAVA_HOME%"
    echo set "PATH=%%JAVA_HOME%%\bin;%%PATH%%"
    if not "%ONLINE_JOB_MAVEN_OPTS%"=="" echo set "MAVEN_OPTS=%ONLINE_JOB_MAVEN_OPTS%"
    echo cd /d "%ROOT%"
    echo mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob^" ^"-Dexec.args=%PMML_PATH% %MODEL_VERSION% %DEBUG_PRINT% %MODEL_SERVICE_URL%^"
    echo pause
)
start "Zephyr OnlineInferenceJob" /D "%ROOT%" cmd /k ""%ONLINE_JOB_LAUNCHER%""

echo [5/7] Starting RecommendJob...
start "Zephyr RecommendJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.RecommendJob^" ^"-Dexec.args=%DEBUG_PRINT%^""

echo [6/7] Starting AlertReviewJob...
start "Zephyr AlertReviewJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.AlertReviewJob^""

echo [7/7] Starting SensorDataProducer...
start "Zephyr SensorDataProducer" /D "%ROOT%" cmd /k "mvn -q -f zephyr-producer/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.producer.SensorDataProducer^""

echo.
echo All Zephyr-Watch processes have been started in separate windows.
echo API health check: http://localhost:%ZEPHYR_API_PORT%/actuator/health
echo Dashboard page:   http://localhost:%ZEPHYR_API_PORT%/dashboard
echo Dashboard API:    http://localhost:%ZEPHYR_API_PORT%/api/dashboard/overview
echo API docs-lite:    http://localhost:%ZEPHYR_API_PORT%/api/docs-lite
echo Risk model health check: http://localhost:5001/api/risk/health
echo Grafana ^(if running^): http://localhost:%ZEPHYR_GRAFANA_PORT%
echo.
echo Optional arguments:
echo   run-all-example.bat [pmmlPath] [modelVersion] [debugPrint] [modelServiceUrl] [enableAsyncRiskInference]
echo Example:
echo   run-all-example.bat zephyr-flink-job/src/main/resources/models/model.pmml risk-classifier-rest-v1 true http://localhost:5001/api/risk/score false
echo.
pause
exit /b 0

:check_tcp_port
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
 "$HostName='%~1'; $PortNo=[int]'%~2'; try { $c = New-Object Net.Sockets.TcpClient; $iar = $c.BeginConnect($HostName,$PortNo,$null,$null); $ok = $iar.AsyncWaitHandle.WaitOne(1500,$false); if ($ok -and $c.Connected) { $c.EndConnect($iar); exit 0 } else { exit 1 } } catch { exit 1 } finally { if ($c) { $c.Close() } }"
exit /b %errorlevel%

:wait_tcp_port
set "WAIT_HOST=%~1"
set "WAIT_PORT=%~2"
set "WAIT_MAX=%~3"
if "%WAIT_MAX%"=="" set "WAIT_MAX=20"
set /a WAIT_COUNT=0
:wait_tcp_loop
call :check_tcp_port "%WAIT_HOST%" "%WAIT_PORT%"
if not errorlevel 1 exit /b 0
set /a WAIT_COUNT+=1
if %WAIT_COUNT% geq %WAIT_MAX% exit /b 1
timeout /t 1 /nobreak >nul
goto wait_tcp_loop

:start_mysql_services
for %%S in (%ZEPHYR_MYSQL_SERVICE_NAMES%) do (
    sc query "%%S" >nul 2>nul
    if not errorlevel 1 (
        echo [INFO] Trying to start MySQL service: %%S
        sc start "%%S" >nul 2>nul
    )
)
exit /b 0

:start_grafana_compose
if not exist "%ZEPHYR_GRAFANA_COMPOSE_FILE%" (
    echo [WARN] Grafana compose file not found: %ZEPHYR_GRAFANA_COMPOSE_FILE%
    echo        Skip auto-start. Check zephyr-dashboard/docker-compose.grafana.yml
    exit /b 0
)

docker compose version >nul 2>nul
if not errorlevel 1 (
    echo [INFO] Starting Grafana via Docker Compose...
    docker compose -f "%ZEPHYR_GRAFANA_COMPOSE_FILE%" up -d
    if errorlevel 1 (
        echo [WARN] docker compose up failed. Please run manually:
        echo        docker compose -f "%ZEPHYR_GRAFANA_COMPOSE_FILE%" up -d
    )
    exit /b 0
)

where docker-compose >nul 2>nul
if not errorlevel 1 (
    echo [INFO] Starting Grafana via docker-compose...
    docker-compose -f "%ZEPHYR_GRAFANA_COMPOSE_FILE%" up -d
    if errorlevel 1 (
        echo [WARN] docker-compose up failed. Please run manually:
        echo        docker-compose -f "%ZEPHYR_GRAFANA_COMPOSE_FILE%" up -d
    )
    exit /b 0
)

echo [WARN] Docker Compose not found. Please install Docker Desktop and run:
echo        docker compose -f "%ZEPHYR_GRAFANA_COMPOSE_FILE%" up -d
exit /b 0

