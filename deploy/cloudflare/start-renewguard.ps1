# Build RenewGuard, start the Ktor backend, and expose it through a Cloudflare Quick Tunnel.
#
#   .\deploy\cloudflare\start-renewguard.ps1            # reuse an existing web build
#   .\deploy\cloudflare\start-renewguard.ps1 -Rebuild   # force a fresh web build
#
# Data stays in backend/data on this machine, so nothing is lost when the tunnel stops.

param(
  [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")

function Find-Cloudflared {
  $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $candidates = @(
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Cloudflare.cloudflared_Microsoft.Winget.Source_8wekyb3d8bbwe\cloudflared.exe",
    "$env:ProgramFiles\cloudflared\cloudflared.exe",
    "$PSScriptRoot\bin\cloudflared.exe"
  )
  foreach ($p in $candidates) {
    if (Test-Path $p) { return $p }
  }
  return $null
}

function Test-BackendUp {
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8080/health" -UseBasicParsing -TimeoutSec 3
    return $r.StatusCode -eq 200
  } catch {
    return $false
  }
}

$cf = Find-Cloudflared
if (-not $cf) {
  Write-Error "cloudflared not found. Install with: winget install --id Cloudflare.cloudflared -e"
}

$webDist = Join-Path $repoRoot "web\dist"
if ($Rebuild -or -not (Test-Path (Join-Path $webDist "index.html"))) {
  Write-Host "Building web UI..." -ForegroundColor Cyan
  Push-Location (Join-Path $repoRoot "web")
  try {
    if (-not (Test-Path "node_modules")) { npm ci }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "web build failed" }
  } finally {
    Pop-Location
  }
} else {
  Write-Host "Reusing existing web build (pass -Rebuild to refresh)." -ForegroundColor DarkGray
}

if (Test-BackendUp) {
  Write-Host "Backend already running on :8080." -ForegroundColor DarkGray
} else {
  Write-Host "Starting Ktor backend on :8080..." -ForegroundColor Cyan
  Start-Process -FilePath (Join-Path $repoRoot "gradlew.bat") `
    -ArgumentList ":backend:run" `
    -WorkingDirectory $repoRoot `
    -WindowStyle Minimized

  # Gradle + JVM startup, and the first run compiles the backend.
  $deadline = (Get-Date).AddMinutes(5)
  while (-not (Test-BackendUp)) {
    if ((Get-Date) -gt $deadline) {
      Write-Error "Backend did not answer /health within 5 minutes. Check the Gradle window for errors."
    }
    Start-Sleep -Seconds 3
  }
  Write-Host "Backend is up." -ForegroundColor Green
}

Write-Host ""
Write-Host "Starting Cloudflare Quick Tunnel -> http://127.0.0.1:8080" -ForegroundColor Cyan
Write-Host "Look for the https://<random>.trycloudflare.com URL below - that is your public address." -ForegroundColor Yellow
Write-Host "Keep this window open; closing it takes the site offline." -ForegroundColor Yellow
Write-Host ""

& $cf tunnel --url http://127.0.0.1:8080
