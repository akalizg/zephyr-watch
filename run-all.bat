@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
cd /d "%ROOT%"

if exist "%ROOT%.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%.env") do (
        if not "%%A"=="" set "%%A=%%B"
    )
)

set "PREFERRED_JAVA_HOME=C:\Users\admin\.jdks\corretto-1.8.0_462"
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

if "%ZEPHYR_AUTO_BOOTSTRAP_MODEL%"=="" set "ZEPHYR_AUTO_BOOTSTRAP_MODEL=true"
if "%ZEPHYR_ENABLE_AUTO_INCREMENTAL_RETRAIN%"=="" set "ZEPHYR_ENABLE_AUTO_INCREMENTAL_RETRAIN=false"
if "%ZEPHYR_INCREMENTAL_RETRAIN_POLL_SEC%"=="" set "ZEPHYR_INCREMENTAL_RETRAIN_POLL_SEC=300"
if "%ZEPHYR_ONLINE_PROFILE%"=="" set "ZEPHYR_ONLINE_PROFILE=full"
if "%ZEPHYR_ONLINE_WINDOW_TIME_MODE%"=="" set "ZEPHYR_ONLINE_WINDOW_TIME_MODE=processing"
if "%ZEPHYR_FEATURE_WINDOW_SECONDS%"=="" set "ZEPHYR_FEATURE_WINDOW_SECONDS=10"
if "%ZEPHYR_FEATURE_WINDOW_SLIDE_SECONDS%"=="" set "ZEPHYR_FEATURE_WINDOW_SLIDE_SECONDS=5"

if /I "%ZEPHYR_ONLINE_PROFILE%"=="stable" (
    if "%ZEPHYR_ENABLE_LOCAL_RUL%"=="" set "ZEPHYR_ENABLE_LOCAL_RUL=false"
    if "%ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK%"=="" set "ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK=false"
    if "%ZEPHYR_ENABLE_REDIS_SINK%"=="" set "ZEPHYR_ENABLE_REDIS_SINK=false"
    if "%ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK%"=="" set "ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK=false"
    if "%ZEPHYR_ENABLE_ALERT_PIPELINE%"=="" set "ZEPHYR_ENABLE_ALERT_PIPELINE=false"
)

if /I "%ZEPHYR_ONLINE_PROFILE%"=="full" (
    if "%ZEPHYR_ENABLE_LOCAL_RUL%"=="" set "ZEPHYR_ENABLE_LOCAL_RUL=true"
    if "%ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK%"=="" set "ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK=true"
    if "%ZEPHYR_ENABLE_REDIS_SINK%"=="" set "ZEPHYR_ENABLE_REDIS_SINK=true"
    if "%ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK%"=="" set "ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK=true"
    if "%ZEPHYR_ENABLE_ALERT_PIPELINE%"=="" set "ZEPHYR_ENABLE_ALERT_PIPELINE=true"
)

set "PYTHON_EXE=%ROOT%.venv\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=%ROOT%zephyr-ml\.venv1\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"

echo ============================================================
echo Zephyr-Watch one-click launcher
echo Project: %ROOT%
echo JAVA_HOME: %JAVA_HOME%
echo PMML:    %PMML_PATH%
echo Model:   %MODEL_VERSION%
echo Debug:   %DEBUG_PRINT%
echo Risk API:%MODEL_SERVICE_URL%
echo Auto model bootstrap: %ZEPHYR_AUTO_BOOTSTRAP_MODEL%
echo Auto incremental retrain: %ZEPHYR_ENABLE_AUTO_INCREMENTAL_RETRAIN%
echo Online profile: %ZEPHYR_ONLINE_PROFILE%
echo Online window:  %ZEPHYR_ONLINE_WINDOW_TIME_MODE%, %ZEPHYR_FEATURE_WINDOW_SECONDS%s/%ZEPHYR_FEATURE_WINDOW_SLIDE_SECONDS%s
echo Online branches: RUL=%ZEPHYR_ENABLE_LOCAL_RUL%, featureSnapshot=%ZEPHYR_ENABLE_FEATURE_SNAPSHOT_SINK%, Redis=%ZEPHYR_ENABLE_REDIS_SINK%, KafkaOut=%ZEPHYR_ENABLE_KAFKA_OUTPUT_SINK%, Alert=%ZEPHYR_ENABLE_ALERT_PIPELINE%
echo ============================================================
echo.
echo Make sure Kafka, MySQL, Redis, HDFS/Hive and other external
echo services configured in application.yml and StorageConfig are running.
echo.

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
set "API_HEALTH_URL=http://localhost:8080/actuator/health"
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

echo Waiting for Risk Model Service health check...
set "MODEL_HEALTH_URL=http://localhost:5001/api/risk/health"
set "MODEL_WAIT_MAX=60"
set /a MODEL_WAIT_COUNT=0
:wait_model
curl.exe -fsS "%MODEL_HEALTH_URL%" >nul 2>nul
if not errorlevel 1 goto model_ready
set /a MODEL_WAIT_COUNT+=1
if %MODEL_WAIT_COUNT% geq %MODEL_WAIT_MAX% goto model_timeout
timeout /t 2 /nobreak >nul
goto wait_model

:model_ready
echo Risk Model Service is ready.
goto continue_startup

:api_timeout
echo [ERROR] Spring Boot API did not become healthy within the expected time.
echo Please inspect the "Zephyr API" window, then rerun this script.
pause
exit /b 1

:model_timeout
echo [ERROR] Risk Model Service did not become healthy within the expected time.
echo Please inspect the "Zephyr Risk Model Service" window, then rerun this script.
pause
exit /b 1

:continue_startup

echo [4/7] Starting OnlineInferenceJob...
start "Zephyr OnlineInferenceJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob^" ^"-Dexec.args=%PMML_PATH% %MODEL_VERSION% %DEBUG_PRINT% %MODEL_SERVICE_URL%^""

echo [5/7] Starting RecommendJob...
start "Zephyr RecommendJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.RecommendJob^" ^"-Dexec.args=%DEBUG_PRINT%^""

echo [6/7] Starting AlertReviewJob...
start "Zephyr AlertReviewJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.AlertReviewJob^""

echo [7/8] Starting SensorSocketServer...
start "Zephyr SensorSocketServer" /D "%ROOT%" cmd /k "mvn -q -f zephyr-producer/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.producer.SensorSocketServer^""

echo Waiting for SensorSocketServer to open port 9999...
set "SOCKET_WAIT_MAX=30"
set /a SOCKET_WAIT_COUNT=0
:wait_socket
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1',9999); exit 0 } catch { exit 1 } finally { $c.Dispose() }" >nul 2>nul
if not errorlevel 1 goto socket_ready
set /a SOCKET_WAIT_COUNT+=1
if %SOCKET_WAIT_COUNT% geq %SOCKET_WAIT_MAX% goto socket_timeout
timeout /t 1 /nobreak >nul
goto wait_socket

:socket_ready
echo SensorSocketServer is ready.

echo [8/8] Starting mock_socket_client.py...
start "Zephyr mock_socket_client" /D "%ROOT%zephyr-producer" cmd /k ""%PYTHON_EXE%" mock_socket_client.py"

if /I "%ZEPHYR_ENABLE_AUTO_INCREMENTAL_RETRAIN%"=="true" (
echo [9/9] Starting incremental_retrain loop...
start "Zephyr incremental_retrain" /D "%ROOT%zephyr-ml" cmd /k ""%PYTHON_EXE%" train\incremental_retrain.py --require-all --register --activate --loop --poll-interval-sec %ZEPHYR_INCREMENTAL_RETRAIN_POLL_SEC%"
)
goto startup_done

:socket_timeout
echo [ERROR] SensorSocketServer did not open port 9999 within the expected time.
echo Please inspect the "Zephyr SensorSocketServer" window, then rerun this script.
pause
exit /b 1

:startup_done

echo.
echo All Zephyr-Watch processes have been started in separate windows.
echo API health check: http://localhost:8080/actuator/health
echo Dashboard page:   http://localhost:8080/dashboard
echo Dashboard API:    http://localhost:8080/api/dashboard/overview
echo API docs-lite:    http://localhost:8080/api/docs-lite
echo Risk model health check: http://localhost:5001/api/risk/health
echo.
echo Optional arguments:
echo   run-all.bat [pmmlPath] [modelVersion] [debugPrint] [modelServiceUrl]
echo Example:
echo   run-all.bat zephyr-flink-job/src/main/resources/models/model.pmml risk-classifier-rest-v1 true http://localhost:5001/api/risk/score
echo.
pause
