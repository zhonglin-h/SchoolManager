$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Get-PropertyValue([string]$filePath, [string]$propertyName) {
    if (-not (Test-Path -LiteralPath $filePath)) { return $null }
    $pattern = "^\s*" + [regex]::Escape($propertyName) + "\s*=\s*(.*)\s*$"
    foreach ($line in Get-Content -LiteralPath $filePath) {
        if ($line -match $pattern) {
            return $matches[1].Trim()
        }
    }
    return $null
}

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

$localProps = Join-Path $root "backend\src\main\resources\application-local.properties"
$backupFolderId = Get-PropertyValue -filePath $localProps -propertyName "app.backup.drive-folder-id"
$backupPgUser = Get-PropertyValue -filePath $localProps -propertyName "app.backup.postgres.username"
$backupPgPassword = Get-PropertyValue -filePath $localProps -propertyName "app.backup.postgres.password"

$backupWarnings = @()
if (-not $backupFolderId -or $backupFolderId -match "^<.*>$") {
    $backupWarnings += "app.backup.drive-folder-id"
}
if (-not $backupPgUser -or $backupPgUser -match "^<.*>$") {
    $backupWarnings += "app.backup.postgres.username"
}
if (-not $backupPgPassword -or $backupPgPassword -match "^<.*>$") {
    $backupWarnings += "app.backup.postgres.password"
}

if ($backupWarnings.Count -gt 0) {
    Write-Host ""
    Write-Host " WARNING: Backup setup is incomplete in application-local.properties:"
    foreach ($field in $backupWarnings) {
        Write-Host "   - $field"
    }
    Write-Host " Nightly backups may fail until these are configured."
}
else {
    Write-Host " Backup configuration fields are set."
}

Write-Host "Starting School Manager..."
$stdoutLog = Join-Path $root "school-manager.log"
$stderrLog = Join-Path $root "school-manager-err.log"

function Rotate-LogFile([string]$logPath, [int]$keepCount = 4) {
    if (-not (Test-Path -LiteralPath $logPath)) {
        return
    }

    $oldestToDelete = "$logPath.$($keepCount + 1)"
    if (Test-Path -LiteralPath $oldestToDelete) {
        $answer = Read-Host "Delete archives older than .$keepCount for $(Split-Path -Leaf $logPath)? (y/N)"
        if ($answer -match '^(y|yes)$') {
            Get-ChildItem -LiteralPath (Split-Path -Parent $logPath) -File |
                Where-Object { $_.Name -match ("^{0}\.\d+$" -f [regex]::Escape((Split-Path -Leaf $logPath))) } |
                ForEach-Object {
                    $suffix = [int]($_.Name.Split(".")[-1])
                    if ($suffix -gt $keepCount) {
                        Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
                    }
                }
        }
    }

    for ($i = $keepCount; $i -ge 1; $i--) {
        $src = "$logPath.$i"
        $dst = "$logPath.$($i + 1)"
        if (Test-Path -LiteralPath $src) {
            Move-Item -LiteralPath $src -Destination $dst -Force
        }
    }

    Move-Item -LiteralPath $logPath -Destination "$logPath.1" -Force
}

# Rotate logs on each start, keep the 4 most recent archives (.1 to .4).
Rotate-LogFile -logPath $stdoutLog -keepCount 4
Rotate-LogFile -logPath $stderrLog -keepCount 4

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

        $healthCode = curl.exe -s -o NUL -w "%{http_code}" --max-time 2 http://localhost:8080/actuator/health
        $rootCode = curl.exe -s -o NUL -w "%{http_code}" --max-time 2 http://localhost:8080/
        if ($healthCode -eq "200" -or $rootCode -eq "200") {
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
