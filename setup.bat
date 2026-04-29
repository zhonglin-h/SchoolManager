@echo off
setlocal enabledelayedexpansion
title School Manager — Setup

echo ============================================================
echo  School Manager — One-Time Setup
echo ============================================================
echo.

:: --------------------------------------------------------------------------
:: 1. Check Java 21+
:: --------------------------------------------------------------------------
echo [1/6] Checking Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo  ERROR: Java is not installed or not on PATH.
    echo  Install Java 21 with:
    echo    winget install Microsoft.OpenJDK.21
    echo  Then re-run setup.bat.
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%v
)
set JAVA_VER=%JAVA_VER:"=%
for /f "delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
if %JAVA_MAJOR% LSS 21 (
    echo  ERROR: Java 21 or later is required. Found: %JAVA_VER%
    echo  Install Java 21 with:
    echo    winget install Microsoft.OpenJDK.21
    pause
    exit /b 1
)
echo  OK — Java %JAVA_VER%

:: --------------------------------------------------------------------------
:: 2. Check Node.js
:: --------------------------------------------------------------------------
echo [2/6] Checking Node.js...
node --version >nul 2>&1
if errorlevel 1 (
    echo  ERROR: Node.js is not installed or not on PATH.
    echo  Install Node.js 20+ with:
    echo    winget install OpenJS.NodeJS.LTS
    echo  Then re-run setup.bat.
    pause
    exit /b 1
)
for /f %%v in ('node --version') do set NODE_VER=%%v
echo  OK — Node.js %NODE_VER%

:: --------------------------------------------------------------------------
:: 3. Check / install pnpm
:: --------------------------------------------------------------------------
echo [3/6] Checking pnpm...
pnpm --version >nul 2>&1
if errorlevel 1 (
    echo  pnpm not found — installing via npm...
    npm install -g pnpm
    if errorlevel 1 (
        echo  ERROR: Failed to install pnpm. Check npm permissions.
        pause
        exit /b 1
    )
)
for /f %%v in ('pnpm --version') do set PNPM_VER=%%v
echo  OK — pnpm %PNPM_VER%

:: --------------------------------------------------------------------------
:: 4. Pre-fetch frontend npm dependencies
:: --------------------------------------------------------------------------
echo [4/6] Installing frontend dependencies...
pushd frontend
pnpm install
if errorlevel 1 (
    echo  ERROR: pnpm install failed. See output above.
    popd
    pause
    exit /b 1
)
popd
echo  OK — frontend dependencies installed.

:: --------------------------------------------------------------------------
:: 5. Pre-fetch Gradle + backend dependencies and download Playwright browsers
:: --------------------------------------------------------------------------
echo [5/6] Pre-fetching backend dependencies and Playwright browsers...
pushd backend
call gradlew.bat dependencies -q
if errorlevel 1 (
    echo  WARNING: Gradle dependency pre-fetch failed (non-fatal).
)

:: Install Playwright browser binaries required for Meet auto-join
call gradlew.bat playwright-install -q 2>nul
if errorlevel 1 (
    echo  NOTE: playwright-install task not found — browser binaries were not downloaded.
    echo        Run 'java -jar backend\build\libs\*.jar --playwright-install' after building
    echo        if you plan to use the auto-join feature.
)
popd
echo  OK — backend dependencies ready.

:: --------------------------------------------------------------------------
:: 6. Create application-local.properties if missing
:: --------------------------------------------------------------------------
echo [6/6] Checking credential configuration...
set LOCAL_PROPS=backend\src\main\resources\application-local.properties
if not exist "%LOCAL_PROPS%" (
    echo  Creating %LOCAL_PROPS% from template...
    copy application.properties.template "%LOCAL_PROPS%" >nul
    echo  Opening the file in Notepad for you to fill in your credentials...
    start notepad "%LOCAL_PROPS%"
) else (
    echo  %LOCAL_PROPS% already exists — skipping.
)

echo.
echo ============================================================
echo  Setup complete!
echo.
echo  NEXT STEPS — place these files before starting the app:
echo.
echo   1. client_secret.json      — repository root
echo      (OAuth credentials from Google Cloud Console)
echo.
echo   2. application-local.properties — fill in all ^<placeholder^> values
echo      (already opened in Notepad if it was just created)
echo.
echo   3. (Optional) service-account.json — repository root
echo      (only needed if you use a Service Account instead of OAuth)
echo.
echo  See credentials-checklist.md for detailed instructions.
echo.
echo  When ready, run:  start.bat
echo ============================================================
pause
