$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Get-MajorVersionFromSemver([string]$versionText) {
    if (-not $versionText) { return $null }
    $clean = $versionText.Trim().TrimStart("v")
    $first = $clean.Split(".")[0]
    $major = 0
    if ([int]::TryParse($first, [ref]$major)) {
        return $major
    }
    return $null
}

Write-Host "============================================================"
Write-Host " School Manager - One-Time Setup"
Write-Host "============================================================"
Write-Host ""

# ---------------------------------------------------------------------------
# 1. Check Java 21+
# ---------------------------------------------------------------------------
Write-Host "[1/7] Checking Java..."
try {
    $javaVersionLine = (& java -version 2>&1 | Select-Object -First 1)
}
catch {
    Write-Host " ERROR: Java is not installed or not on PATH."
    Write-Host " Install Java 21 with:"
    Write-Host "   winget install Microsoft.OpenJDK.21"
    throw "Java not found"
}

$javaVersion = ""
if ($javaVersionLine -match '"([^"]+)"') {
    $javaVersion = $matches[1]
}
$javaMajor = Get-MajorVersionFromSemver $javaVersion
if (-not $javaMajor -or $javaMajor -lt 21) {
    Write-Host " ERROR: Java 21 or later is required. Found: $javaVersion"
    Write-Host " Install Java 21 with:"
    Write-Host "   winget install Microsoft.OpenJDK.21"
    throw "Java version too low"
}
Write-Host " OK - Java $javaVersion"

# ---------------------------------------------------------------------------
# 2. Check PostgreSQL
# ---------------------------------------------------------------------------
Write-Host "[2/7] Checking PostgreSQL..."
$pgFound = $true
try {
    $pgVersion = (& psql --version 2>&1).ToString().Trim()
} catch {
    $pgFound = $false
}
if (-not $pgFound -or $LASTEXITCODE -ne 0) {
    Write-Host " ERROR: PostgreSQL (psql / pg_dump) is not installed or not on PATH."
    Write-Host " Install PostgreSQL 16 with:"
    Write-Host "   winget install PostgreSQL.PostgreSQL"
    Write-Host " Then re-run setup.ps1."
    throw "PostgreSQL not found"
}
Write-Host " OK - $pgVersion"

# ---------------------------------------------------------------------------
# 3. Check Node.js
# ---------------------------------------------------------------------------
Write-Host "[3/7] Checking Node.js..."
try {
    $nodeVersion = (& node --version).Trim()
}
catch {
    Write-Host " ERROR: Node.js is not installed or not on PATH."
    Write-Host " Install Node.js 20+ with:"
    Write-Host "   winget install OpenJS.NodeJS.LTS"
    throw "Node.js not found"
}
Write-Host " OK - Node.js $nodeVersion"

# ---------------------------------------------------------------------------
# 4. Check / install pnpm
# ---------------------------------------------------------------------------
Write-Host "[4/7] Checking pnpm..."
$pnpmVersion = $null
try {
    $pnpmVersion = (& pnpm --version).Trim()
}
catch {
    Write-Host " pnpm not found - installing via npm..."
    & npm install -g pnpm
    if ($LASTEXITCODE -ne 0) {
        Write-Host " ERROR: Failed to install pnpm. Check npm permissions."
        throw "pnpm install failed"
    }
    $pnpmVersion = (& pnpm --version).Trim()
}
Write-Host " OK - pnpm $pnpmVersion"

# ---------------------------------------------------------------------------
# 5. Pre-fetch frontend npm dependencies
# ---------------------------------------------------------------------------
Write-Host "[5/7] Installing frontend dependencies..."
Push-Location (Join-Path $root "frontend")
try {
    & pnpm install
    if ($LASTEXITCODE -ne 0) {
        throw "pnpm install failed"
    }
}
finally {
    Pop-Location
}
Write-Host " OK - frontend dependencies installed."

# ---------------------------------------------------------------------------
# 6. Pre-fetch backend dependencies and download Playwright browsers
# ---------------------------------------------------------------------------
Write-Host "[6/7] Pre-fetching backend dependencies and Playwright browsers..."
Push-Location (Join-Path $root "backend")
try {
    & .\gradlew.bat dependencies -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host " WARNING: Gradle dependency pre-fetch failed (non-fatal)."
    }

    & .\gradlew.bat playwrightInstall -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host " NOTE: playwrightInstall task failed - browser binaries were not downloaded."
        Write-Host "       This is only needed for the Meet auto-join feature."
        Write-Host "       To install manually:"
        Write-Host "         cd backend; .\gradlew.bat playwrightInstall"
    }
}
finally {
    Pop-Location
}
Write-Host " OK - backend dependencies ready."

# ---------------------------------------------------------------------------
# 7. Create application-local.properties if missing
# ---------------------------------------------------------------------------
Write-Host "[7/7] Checking credential configuration..."
$localProps = Join-Path $root "backend\src\main\resources\application-local.properties"
if (-not (Test-Path -LiteralPath $localProps)) {
    $template = Join-Path $root "application.properties.template"
    Write-Host " Creating $localProps from template..."
    Copy-Item -LiteralPath $template -Destination $localProps -Force
    Write-Host " Opening the file in Notepad for you to fill in your credentials..."
    Start-Process notepad.exe -ArgumentList $localProps
}
else {
    Write-Host " $localProps already exists - skipping."
}

Write-Host ""
Write-Host "============================================================"
Write-Host " Setup complete!"
Write-Host ""
Write-Host "NEXT STEPS - place these files before starting the app:"
Write-Host ""
Write-Host " 1. client_secret.json - backend/client_secret.json"
Write-Host "    (OAuth credentials from Google Cloud Console)"
Write-Host ""
Write-Host " 2. application-local.properties - fill in all <placeholder> values"
Write-Host "    (opened in Notepad above if it was just created)"
Write-Host ""
Write-Host " 3. (Optional) service-account.json - repository root"
Write-Host "    (only needed if you use a Service Account instead of OAuth)"
Write-Host ""
Write-Host "See credentials-checklist.md for detailed instructions."
Write-Host ""
Write-Host "When ready, run: .\start.ps1"
Write-Host "============================================================"
