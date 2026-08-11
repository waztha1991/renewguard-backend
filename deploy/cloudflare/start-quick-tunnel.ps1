# Expose local RenewGuard (Ktor on :8080) via a temporary Cloudflare Quick Tunnel.
$ErrorActionPreference = "Stop"

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

$cf = Find-Cloudflared
if (-not $cf) {
  Write-Error "cloudflared not found. Install with: winget install --id Cloudflare.cloudflared -e"
}

try {
  $health = Invoke-WebRequest -Uri "http://127.0.0.1:8080/health" -UseBasicParsing -TimeoutSec 3
  if ($health.StatusCode -ne 200) {
    Write-Error "Backend health check failed (HTTP $($health.StatusCode)). Start it with: .\gradlew.bat :backend:run"
  }
} catch {
  Write-Error "Backend not reachable at http://127.0.0.1:8080. Start it first: .\gradlew.bat :backend:run"
}

Write-Host "Starting Cloudflare Quick Tunnel -> http://127.0.0.1:8080"
Write-Host "Watch for a https://*.trycloudflare.com URL below."
Write-Host ""

& $cf tunnel --url http://127.0.0.1:8080
