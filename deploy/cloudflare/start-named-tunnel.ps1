# Run a named Cloudflare Tunnel using deploy/cloudflare/config.yml
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

$config = Join-Path $PSScriptRoot "config.yml"
if (-not (Test-Path $config)) {
  Write-Error "Missing $config — copy config.example.yml to config.yml and fill in tunnel id + hostname."
}

try {
  Invoke-WebRequest -Uri "http://127.0.0.1:8080/health" -UseBasicParsing -TimeoutSec 3 | Out-Null
} catch {
  Write-Error "Backend not reachable at http://127.0.0.1:8080. Start it first: .\gradlew.bat :backend:run"
}

Write-Host "Starting named Cloudflare Tunnel with $config"
& $cf tunnel --config $config run
