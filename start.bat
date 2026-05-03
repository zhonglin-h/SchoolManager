@echo off
setlocal enabledelayedexpansion
title School Manager - Starting...

echo ============================================================
echo  School Manager - Start
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
for %%f in (backend\build\libs\*.jar) do (
    echo %%~nxf | findstr /i /c:"plain" >nul
    if errorlevel 1 (
        set JAR_PATH=%%f
    )
)
if "%JAR_PATH%"=="" (
    echo  ERROR: No JAR found in backend\build\libs\.
    pause
    exit /b 1
)
echo  JAR: %JAR_PATH%

:: --------------------------------------------------------------------------
:: Start the application in the background via PowerShell (captures PID reliably)
:: --------------------------------------------------------------------------
echo Starting School Manager...
for /f %%p in ('powershell -NoProfile -Command ^
    "Start-Process -FilePath java ^
        -ArgumentList @(''-jar'', ''%JAR_PATH%'', ''--spring.profiles.active=local'') ^
        -RedirectStandardOutput school-manager.log ^
        -RedirectStandardError school-manager-err.log ^
        -PassThru ^
     | Select-Object -ExpandProperty Id"') do set APP_PID=%%p
if "%APP_PID%"=="" (
    echo  ERROR: Failed to start application (no PID returned). Check Java installation and logs.
    pause
    exit /b 1
)
echo %APP_PID% > .pid
echo  PID: %APP_PID% saved to .pid

:: --------------------------------------------------------------------------
:: Wait for the app to be ready, then open browser
:: --------------------------------------------------------------------------
echo Waiting for server to start...
set READY=0
set HTTP_CODE=
for /l %%i in (1,1,30) do (
    if !READY!==0 (
        timeout /t 2 /nobreak >nul
        for /f %%h in ('curl -s -o nul -w "%%{http_code}" http://localhost:8080/actuator/health 2^>nul') do set HTTP_CODE=%%h
        if "!HTTP_CODE!"=="200" (
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
