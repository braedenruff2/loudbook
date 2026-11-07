@echo off
rem Lets your phone use the Loudbook PC voice away from home (mobile data, other Wi-Fi), through Tailscale: a free,
rem private, encrypted link between your own devices. Nothing is opened to the internet. The PowerShell part is below.
setlocal
title Loudbook - PC voice away from home
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f=[IO.File]::ReadAllText('%~f0'); $i=$f.LastIndexOf('#PS_START#'); iex $f.Substring($i+10)"
echo.
pause
exit /b
#PS_START#
$ErrorActionPreference = 'Stop'
function Say($t) { Write-Host $t }
try {
  $ts = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
  # 1. Tailscale on this PC
  if (-not (Test-Path $ts)) {
    Say '1/3 Installing Tailscale (free)...'
    try { winget install -e --id Tailscale.Tailscale --accept-package-agreements --accept-source-agreements --silent | Out-Null } catch { }
    if (-not (Test-Path $ts)) {
      Say '    Couldn''t install it by itself. The download page is opening: install it, then run this again.'
      Start-Process 'https://tailscale.com/download/windows'
      return
    }
  } else { Say '1/3 Tailscale is installed.' }
  # 2. signed in (the same account goes on the phone)
  function TsIp { try { $x = (& $ts ip -4 2>$null | Select-Object -First 1); if ($x -match '^100\.') { $x.Trim() } } catch { $null } }
  $ip = TsIp
  if (-not $ip) {
    Say '2/3 Sign in to Tailscale in the browser window that opens (Google is easiest). Use the same account on your phone.'
    $log = Join-Path $env:TEMP 'loudbook-tailscale-login'
    Start-Process -FilePath $ts -ArgumentList 'login' -NoNewWindow -RedirectStandardError "$log.err" -RedirectStandardOutput "$log.out"
    $opened = $false
    for ($i = 0; $i -lt 300 -and -not $ip; $i++) {
      Start-Sleep 1
      if (-not $opened) {
        $t = (Get-Content "$log.err", "$log.out" -Raw -ErrorAction SilentlyContinue) -join ' '
        if ($t -match '(https://login\.tailscale\.com/\S+)') { Start-Process $Matches[1]; $opened = $true }
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
  Say ''
  Say 'On your phone:'
  Say '  1. Install Tailscale from the Play Store and sign in with the same account. Turn it on.'
  Say '     (In Tailscale''s settings, "Always-on VPN" keeps it on.)'
  Say '  2. Open Loudbook once while at home, and read a sentence with the PC voice: it learns this address.'
  Say '  Then, away from home, Loudbook reaches the PC by itself (it takes a few seconds the first time).'
  Say ''
  Say 'The PC has to be on and awake. The PC voice uses about 170 MB of mobile data an hour of listening.'
} catch {
  Write-Host ''
  Write-Host "  Stopped: $($_.Exception.Message)" -ForegroundColor Red
}
