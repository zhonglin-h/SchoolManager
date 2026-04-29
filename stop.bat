@echo off
setlocal
title School Manager — Stopping...

echo ============================================================
echo  School Manager — Stop
echo ============================================================
echo.

if not exist .pid (
    echo  No .pid file found. Is School Manager running?
    echo  You can stop it manually via Task Manager (java.exe).
    pause
    exit /b 0
)

set /p APP_PID=<.pid
if "%APP_PID%"=="" (
    echo  .pid file is empty. Nothing to stop.
    del .pid
    pause
    exit /b 0
)

echo  Stopping process %APP_PID%...
taskkill /PID %APP_PID% /F >nul 2>&1
if errorlevel 1 (
    echo  WARNING: Could not kill PID %APP_PID%. It may have already stopped.
) else (
    echo  Stopped.
)

del .pid
echo.
echo ============================================================
echo  School Manager stopped.
echo ============================================================
pause
