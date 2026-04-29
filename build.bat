@echo off
setlocal
title School Manager — Build

echo ============================================================
echo  School Manager — Full Build
echo ============================================================
echo.

:: Build frontend
echo [1/2] Building frontend...
pushd frontend
pnpm install
if errorlevel 1 (
    echo  ERROR: pnpm install failed.
    popd
    pause
    exit /b 1
)
pnpm build
if errorlevel 1 (
    echo  ERROR: pnpm build failed.
    popd
    pause
    exit /b 1
)
popd
echo  Frontend build OK.

:: Build backend fat JAR (copyFrontend Gradle task copies dist/ into static/)
echo [2/2] Building backend fat JAR...
pushd backend
call gradlew.bat bootJar
if errorlevel 1 (
    echo  ERROR: Gradle bootJar failed.
    popd
    pause
    exit /b 1
)
popd
echo  Backend build OK.

echo.
echo ============================================================
echo  Build complete. JAR: backend\build\libs\*.jar
echo  Run start.bat to launch the application.
echo ============================================================
pause
