$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "============================================================"
Write-Host " School Manager - Stop"
Write-Host "============================================================"
Write-Host ""

$pidFile = Join-Path $root ".pid"
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host " No .pid file found. Is School Manager running?"
    exit 0
}

$pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
if (-not $pidText) {
    Write-Host " .pid file is empty. Cleaning up."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

$appPid = 0
if (-not [int]::TryParse($pidText, [ref]$appPid)) {
    Write-Host " WARNING: Invalid PID value in .pid: '$pidText'"
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host " Verifying process $appPid..."
$proc = Get-CimInstance Win32_Process -Filter "ProcessId = $appPid" -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-Host " Process $appPid is not running. Cleaning up stale .pid."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

$cmdLine = [string]$proc.CommandLine
if ($cmdLine -notmatch "school-manager" -and $cmdLine -notmatch "SchoolManager") {
    Write-Host " WARNING: PID $appPid does not look like School Manager."
    Write-Host " Command: $cmdLine"
    Write-Host " Aborting stop for safety."
    exit 1
}

Write-Host " Stopping process $appPid..."
Stop-Process -Id $appPid -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 300

if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
    Write-Host " WARNING: Could not stop PID $appPid."
    exit 1
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Host " Stopped."
Write-Host ""
Write-Host "============================================================"
Write-Host " School Manager stopped."
Write-Host "============================================================"
