@echo off
setlocal enabledelayedexpansion
title School Manager — Starting...

echo ============================================================
echo  School Manager — Start
echo ============================================================
echo.

:: --------------------------------------------------------------------------
:: Build the fat JAR (Gradle skips if nothing has changed)
:: --------------------------------------------------------------------------
echo Building application (this may take a moment on first run)...
pushd backend
call gradlew.bat bootJar -q
if errorlevel 1 (
    echo  ERROR: Build failed. Check the output above.
    popd
    pause
    exit /b 1
)
popd
echo  Build OK.

:: --------------------------------------------------------------------------
:: Find the JAR
:: --------------------------------------------------------------------------
set JAR_PATH=
for %%f in (backend\build\libs\*.jar) do set JAR_PATH=%%f
if "%JAR_PATH%"=="" (
    echo  ERROR: No JAR found in backend\build\libs\.
    pause
    exit /b 1
)
echo  JAR: %JAR_PATH%

:: --------------------------------------------------------------------------
:: Start the application in the background, save PID
:: --------------------------------------------------------------------------
echo Starting School Manager...
start /b java -jar "%JAR_PATH%" --spring.profiles.active=local > school-manager.log 2>&1
:: Capture PID of the java process just launched
for /f "tokens=2" %%p in ('tasklist /fi "imagename eq java.exe" /fo csv /nh 2^>nul ^| head -1') do (
    set APP_PID=%%p
)
:: Fallback: use wmic to get the latest java PID
for /f "skip=1 tokens=1" %%p in ('wmic process where "name='java.exe'" get ProcessId 2^>nul') do (
    if not "%%p"=="" set APP_PID=%%p
)
echo %APP_PID% > .pid
echo  PID: %APP_PID% saved to .pid

:: --------------------------------------------------------------------------
:: Wait for the app to be ready, then open browser
:: --------------------------------------------------------------------------
echo Waiting for server to start...
set READY=0
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        timeout /t 2 /nobreak >nul
        curl -s -o nul -w "%%{http_code}" http://localhost:8080/actuator/health >nul 2>&1
        if not errorlevel 1 (
            set READY=1
        )
    )
)

:: Open browser regardless (server might not expose /actuator/health)
timeout /t 4 /nobreak >nul
start http://localhost:8080

echo.
echo ============================================================
echo  School Manager is running at http://localhost:8080
echo  Logs are being written to school-manager.log
echo  To stop the app, run:  stop.bat
echo ============================================================
