@echo off
rem Lets your phone use the Loudbook PC voice away from home (mobile data, other Wi-Fi), through Tailscale: a free,
rem private, encrypted link between your own devices. Nothing is opened to the internet. The PowerShell part is below.
setlocal
title Loudbook - PC voice away from home
set "LB_REPO=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f=[IO.File]::ReadAllText('%~f0'); $i=$f.LastIndexOf('#PS_START#'); iex $f.Substring($i+10)"
echo.
pause
exit /b
#PS_START#
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# (what Braeden needs is also written to this file on the Desktop, so it can be passed on to him)
$note = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Loudbook away from home.txt'
function Say($t) { Write-Host $t; try { Add-Content -Path $note -Value $t } catch { } }
Set-Content -Path $note -Value ("Loudbook away from home, " + (Get-Date))
try {
  $ts = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
  # 1. Tailscale on this PC
  if (-not (Test-Path $ts)) {
    # (the installer itself, with its own window: winget could sit waiting for a hidden prompt for hours)
    Say '1/3 Downloading Tailscale (free)... A Tailscale installer window will open: click through it (Install), and Yes if Windows asks.'
    $setup = Join-Path $env:TEMP 'tailscale-setup.exe'
    Invoke-WebRequest -UseBasicParsing 'https://pkgs.tailscale.com/stable/tailscale-setup-latest.exe' -OutFile $setup
    Start-Process -FilePath $setup -Wait
    for ($i = 0; $i -lt 60 -and -not (Test-Path $ts); $i++) { Start-Sleep 1 }
    if (-not (Test-Path $ts)) {
      Say '    Tailscale didn''t install. Run this again and finish the installer window.'
      return
    }
    Start-Sleep 5
  } else { Say '1/3 Tailscale is installed.' }
  # 2. signed in (the same account goes on the phone)
  function TsIp { try { $x = (& $ts ip -4 2>$null | Select-Object -First 1); if ($x -match '^100\.') { $x.Trim() } } catch { $null } }
  $ip = TsIp
  if (-not $ip) {
    Say '2/3 Tailscale needs to be signed in to the same account as the phone. Either sign in in the browser window that opens,'
    Say '    or open the link below on the phone that has Tailscale (it signs this PC in to that account). Waiting up to 30 minutes...'
    $log = Join-Path $env:TEMP 'loudbook-tailscale-login'
    Start-Process -FilePath $ts -ArgumentList 'login' -NoNewWindow -RedirectStandardError "$log.err" -RedirectStandardOutput "$log.out"
    $opened = $false
    for ($i = 0; $i -lt 1800 -and -not $ip; $i++) {
      Start-Sleep 1
      if (-not $opened) {
        $t = (Get-Content "$log.err", "$log.out" -Raw -ErrorAction SilentlyContinue) -join ' '
        if ($t -match '(https://login\.tailscale\.com/\S+)') { Say "    Sign-in link: $($Matches[1])"; Start-Process $Matches[1]; $opened = $true }
      }
      $ip = TsIp
    }
    if (-not $ip) { throw 'Tailscale isn''t signed in yet. Open Tailscale from the tray, sign in, then run this again.' }
  } else { Say '2/3 Tailscale is signed in.' }
  # 3. let the phone in, from Tailscale's addresses only (whatever Windows calls that network)
  if (-not (Get-NetFirewallRule -DisplayName 'Loudbook PC voice (Tailscale)' -ErrorAction SilentlyContinue)) {
    Say '3/3 Letting your phone in through Tailscale (Windows will ask for permission).'
    $cmd = "New-NetFirewallRule -DisplayName 'Loudbook PC voice (Tailscale)' -Direction Inbound -Protocol TCP -LocalPort 8770 -RemoteAddress 100.64.0.0/10 -Profile Any -Action Allow | Out-Null"
    Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList ('-NoProfile -Command "' + $cmd + '"')
  } else { Say '3/3 Firewall is set.' }
  Say ''
  Say "Done. This PC's Tailscale address: $ip"
  # pair the phone now, from anywhere: the address above and the code below go in Loudbook > Settings > Pair with my PC
  $uv = Join-Path $env:LOCALAPPDATA 'Loudbook\bin\uv.exe'
  $srv = Join-Path $env:LB_REPO 'pc-server'
  if ((Test-Path $uv) -and $env:LB_REPO) {
    Say ''
    Say "On the phone (Tailscale on): Loudbook > Settings > Pair with my PC. PC address: $ip   Code: below."
    $env:UV_PROJECT_ENVIRONMENT = Join-Path $env:LOCALAPPDATA 'Loudbook\venv'
    $ErrorActionPreference = 'Continue'
    & $uv run --no-sync --project $srv python -u (Join-Path $srv 'loudbook_server.py') pair 2>&1 | ForEach-Object { Say "$_" }
  }
  Say ''
  Say 'All done. Leave this PC on (it can lock its screen, but it must not sleep or shut down).'
} catch {
  Write-Host ''
  Write-Host "  Stopped: $($_.Exception.Message)" -ForegroundColor Red
}
