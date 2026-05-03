$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "============================================================"
Write-Host " School Manager - Start"
Write-Host "============================================================"
Write-Host ""

Write-Host "Building application (this may take a moment on first run)..."
Push-Location (Join-Path $root "backend")
try {
    & .\gradlew.bat bootJar -q
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed."
    }
}
finally {
    Pop-Location
}
Write-Host " Build OK."

$libsDir = Join-Path $root "backend\build\libs"
$jar = Get-ChildItem -LiteralPath $libsDir -Filter *.jar -File |
    Where-Object { $_.Name -notmatch "plain" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "No runnable JAR found in $libsDir."
}

$jarPath = $jar.FullName
Write-Host " JAR: $jarPath"

$clientSecretPath = Join-Path $root "backend\client_secret.json"
if (-not (Test-Path -LiteralPath $clientSecretPath)) {
    $clientSecretPath = Join-Path $root "client_secret.json"
}
if (-not (Test-Path -LiteralPath $clientSecretPath)) {
    Write-Host ""
    Write-Host " ERROR: Missing Google OAuth credentials file:"
    Write-Host "   $(Join-Path $root 'backend\client_secret.json')"
    Write-Host " Expected primarily under backend/. You can also place it at repo root."
    Write-Host " Or set google.client-secret.path to a valid location."
    throw "Missing required client_secret.json"
}
Write-Host " Google client secret: $clientSecretPath"

Write-Host "Starting School Manager..."
$stdoutLog = Join-Path $root "school-manager.log"
$stderrLog = Join-Path $root "school-manager-err.log"
$proc = Start-Process -FilePath "java" `
    -ArgumentList @("-jar", $jarPath, "--spring.profiles.active=local", "--google.client-secret.path=$clientSecretPath") `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

$pidFile = Join-Path $root ".pid"
Set-Content -LiteralPath $pidFile -Value $proc.Id -Encoding ascii -NoNewline
Write-Host " PID: $($proc.Id) saved to .pid"

Write-Host "Waiting for server to start..."
$ready = $false
$oauthOpened = $false
for ($i = 0; $i -lt 45 -and -not $ready; $i++) {
    if ($proc.HasExited) {
        $proc.WaitForExit()
        Write-Host ""
        Write-Host " ERROR: App process exited early (code $($proc.ExitCode))."
        if (Test-Path -LiteralPath $stdoutLog) {
            Write-Host " Last app log lines:"
            Get-Content -LiteralPath $stdoutLog -Tail 30 | ForEach-Object { Write-Host "  $_" }
        }
        if (Test-Path -LiteralPath $stderrLog) {
            Write-Host " Last stderr lines:"
            Get-Content -LiteralPath $stderrLog -Tail 30 | ForEach-Object { Write-Host "  $_" }
        }
        throw "Application exited before becoming healthy."
    }

    Start-Sleep -Seconds 2
    try {
        if (-not $oauthOpened -and (Test-Path -LiteralPath $stdoutLog)) {
            $oauthUrl = Get-Content -LiteralPath $stdoutLog -Tail 200 |
                Select-String -Pattern 'https://accounts\.google\.com/o/oauth2/auth\S*' |
                Select-Object -Last 1 |
                ForEach-Object { $_.Matches[0].Value }
            if ($oauthUrl) {
                Write-Host ""
                Write-Host " Google OAuth consent required. Opening browser..."
                Start-Process $oauthUrl
                $oauthOpened = $true
            }
        }

        $httpCode = curl.exe -s -o NUL -w "%{http_code}" --max-time 2 http://localhost:8080/actuator/health
        if ($httpCode -eq "200") {
            $ready = $true
        }
    }
    catch {
        # keep waiting
    }
}

if (-not $ready) {
    Write-Host ""
    Write-Host " WARNING: Health endpoint did not return 200 within 90 seconds."
    Write-Host " Opening app URL anyway..."
}

Start-Sleep -Seconds 2
Start-Process "http://localhost:8080"

Write-Host ""
Write-Host "============================================================"
Write-Host " School Manager is running at http://localhost:8080"
Write-Host " Logs are being written to school-manager.log"
Write-Host " To stop the app, run: .\stop.ps1"
Write-Host "============================================================"
