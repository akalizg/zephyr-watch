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

set "PYTHON_EXE=%ROOT%zephyr-ml\.venv1\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"

echo ============================================================
echo Zephyr-Watch one-click launcher
echo Project: %ROOT%
echo JAVA_HOME: %JAVA_HOME%
echo PMML:    %PMML_PATH%
echo Model:   %MODEL_VERSION%
echo Debug:   %DEBUG_PRINT%
echo Risk API:%MODEL_SERVICE_URL%
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

echo [3/7] Starting Python Risk Model Service...
start "Zephyr Risk Model Service" /D "%ROOT%zephyr-ml" cmd /k ""%PYTHON_EXE%" serve\risk_model_service.py"

echo [4/7] Starting OnlineInferenceJob...
start "Zephyr OnlineInferenceJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob^" ^"-Dexec.args=%PMML_PATH% %MODEL_VERSION% %DEBUG_PRINT% %MODEL_SERVICE_URL%^""

echo [5/7] Starting RecommendJob...
start "Zephyr RecommendJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.RecommendJob^" ^"-Dexec.args=%DEBUG_PRINT%^""

echo [6/7] Starting AlertReviewJob...
start "Zephyr AlertReviewJob" /D "%ROOT%" cmd /k "mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.flink.app.AlertReviewJob^""

echo [7/7] Starting SensorDataProducer...
start "Zephyr SensorDataProducer" /D "%ROOT%" cmd /k "mvn -q -f zephyr-producer/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java ^"-Dexec.mainClass=com.zephyr.watch.producer.SensorDataProducer^""

echo.
echo All Zephyr-Watch processes have been started in separate windows.
echo API health check: http://localhost:8080/actuator/health
echo Risk model health check: http://localhost:5001/api/risk/health
echo.
echo Optional arguments:
echo   run-all.bat [pmmlPath] [modelVersion] [debugPrint] [modelServiceUrl]
echo Example:
echo   run-all.bat zephyr-flink-job/src/main/resources/models/model.pmml risk-classifier-rest-v1 true http://localhost:5001/api/risk/score
echo.
pause
