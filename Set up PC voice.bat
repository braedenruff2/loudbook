@echo off
rem Sets up the Loudbook PC voice: the reading voice runs on this PC for your paired phone.
rem Everything it installs goes in %LOCALAPPDATA%\Loudbook. The PowerShell part is below.
setlocal
title Loudbook - PC voice
set "LB_REPO=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f=[IO.File]::ReadAllText('%~f0'); $i=$f.LastIndexOf('#PS_START#'); iex $f.Substring($i+10)"
echo.
if not defined LB_CI pause
exit /b
#PS_START#
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$repo = $env:LB_REPO.TrimEnd('\')
$srv  = Join-Path $repo 'pc-server'
$lb   = Join-Path $env:LOCALAPPDATA 'Loudbook'
$bin  = Join-Path $lb 'bin'
$models = Join-Path $lb 'models'
New-Item -ItemType Directory -Force $bin, $models | Out-Null
$env:UV_PROJECT_ENVIRONMENT = Join-Path $lb 'venv'
function Say($t) { Write-Host "  $t" }
Write-Host ''
Write-Host '  Loudbook PC voice'
Write-Host '  ================='
Write-Host '  The reading voice runs on this PC instead of the phone (saves its battery).'
Write-Host '  Only phones you pair can use it, and only on your home network.'
Write-Host ''
try {
  # 1. uv: installs the right Python and packages without touching anything else on the PC
  $uv = Join-Path $bin 'uv.exe'
  if (-not (Test-Path $uv)) {
    Say '1/5 Getting uv (Python installer)...'
    $zip = Join-Path $env:TEMP 'loudbook-uv.zip'
    Invoke-WebRequest 'https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip' -OutFile $zip -UseBasicParsing
    $tmp = Join-Path $env:TEMP 'loudbook-uv'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Force $zip $tmp
    Copy-Item (Get-ChildItem $tmp -Recurse -Filter 'uv.exe' | Select-Object -First 1).FullName $uv
    Remove-Item -Recurse -Force $tmp, $zip
  } else { Say '1/5 uv is here.' }

  # 2. the voice (Kokoro v1.0): reuse the copy downloaded for the app if it's still around
  $k = Join-Path $models 'kokoro-multi-lang-v1_0'
  if (-not (Test-Path (Join-Path $k 'model.onnx'))) {
    $tb = @(
      (Join-Path $repo '_to_delete\old\downloads\kokoro-multi-lang-v1_0.tar.bz2'),
      (Join-Path $env:USERPROFILE 'Desktop\Claude outputs\Loudbook-android-setup\Loudbook-android\downloads\kokoro-multi-lang-v1_0.tar.bz2'),
      (Join-Path $lb 'kokoro-multi-lang-v1_0.tar.bz2')
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $tb) {
      Say '2/5 Downloading the voice (350 MB, once)...'
      $tb = Join-Path $lb 'kokoro-multi-lang-v1_0.tar.bz2'
      Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2' -OutFile $tb -UseBasicParsing
    }
    Say '2/5 Unpacking the voice...'
    & tar.exe -xjf $tb -C $models
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $k 'model.onnx'))) { throw 'unpacking the voice failed' }
  } else { Say '2/5 The voice is here.' }

  # 3. Python and the packages (sherpa-onnx, cryptography, numpy) in their own folder
  Say '3/5 Getting Python and the voice engine ready (first time takes a minute)...'
  & $uv sync --quiet --python 3.12 --project $srv
  if ($LASTEXITCODE -ne 0) { throw 'installing the Python packages failed' }

  # 3b. the natural voice (Chatterbox-Turbo) when there's an NVIDIA graphics card
  $natFlag = Join-Path $lb 'natural.on'
  $gpu = Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'NVIDIA' } | Select-Object -First 1
  if ($gpu -or $env:LB_NATURAL) {
    $gname = if ($gpu) { $gpu.Name } else { 'test machine' }
    Say "3/5 Found $gname. Installing the natural voice for it (about 4 GB, once; this takes a while)..."
    & $uv sync --quiet --python 3.12 --project $srv --extra natural
    if ($LASTEXITCODE -eq 0) {
      Say '    Downloading the natural voice model...'
      & $uv run --no-sync --project $srv python -c "from huggingface_hub import snapshot_download as d; d(repo_id='ResembleAI/chatterbox-turbo', allow_patterns=['*.safetensors','*.json','*.txt','*.pt','*.model'])"
    }
    if ($LASTEXITCODE -eq 0) { Set-Content $natFlag 'on' }
    else {
      Remove-Item $natFlag -ErrorAction SilentlyContinue
      Say '    The natural voice could not be installed; Kokoro will read. (Run this again to retry.)'
      & $uv sync --quiet --python 3.12 --project $srv
    }
  } else {
    Remove-Item $natFlag -ErrorAction SilentlyContinue
    Say '    No NVIDIA graphics card found, so the PC reads with Kokoro (the same voice as the phone).'
  }

  # 4. let the phone reach it: home (private) networks only, these two ports only
  if (-not $env:LB_CI) {
    $have = Get-NetFirewallRule -DisplayName 'Loudbook PC voice' -ErrorAction SilentlyContinue
    if (-not $have) {
      Say '4/5 Allowing the phone in through Windows Firewall (home networks only). Windows will ask for permission.'
      $cmd = "New-NetFirewallRule -DisplayName 'Loudbook PC voice' -Direction Inbound -Protocol TCP -LocalPort 8770 -Profile Private -Action Allow | Out-Null; " +
             "New-NetFirewallRule -DisplayName 'Loudbook PC voice (finding the PC)' -Direction Inbound -Protocol UDP -LocalPort 8771 -Profile Private -Action Allow | Out-Null"
      try { Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList ('-NoProfile -Command "' + $cmd + '"') }
      catch { Say '    Skipped. If Windows asks whether Python may use the network, allow Private networks only.' }
    } else { Say '4/5 Firewall is set.' }
    $public = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq 'Public' }
    if ($public) {
      Say ''
      Say "    Note: Windows treats '$($public[0].Name)' as a public network, so the phone can't reach this PC on it."
      Say '    If it is your home Wi-Fi: Settings > Network & internet > (your network) > Private network.'
      Say ''
    }
    # start with Windows
    $startup = [Environment]::GetFolderPath('Startup')
    $launcher = Join-Path $startup 'Loudbook PC voice.vbs'
    $line = 'CreateObject("WScript.Shell").Run "wscript.exe //B ""' + (Join-Path $srv 'run-server.vbs') + '""", 0, False'
    Set-Content -Encoding ASCII $launcher $line
  }

  # 5. (re)start it now
  Say '5/5 Starting the PC voice...'
  # stop a copy that's already running (its keep-alive loop first, then the server)
  Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { ($_.Name -eq 'cmd.exe' -and $_.CommandLine -like '*start-server.cmd*') -or ($_.Name -eq 'python.exe' -and $_.CommandLine -like '*loudbook_server.py*serve*') } |
    Sort-Object { $_.Name -ne 'cmd.exe' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep 1
  Set-Content (Join-Path $lb 'run.on') 'on' 
  $log = Join-Path $lb 'server.log'
  $before = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
  Start-Process wscript.exe -ArgumentList '//B', ('"' + (Join-Path $srv 'run-server.vbs') + '"')
  $ok = $false
  $limit = if (Test-Path $natFlag) { 900 } else { 180 }
  for ($i = 0; $i -lt $limit; $i++) {
    Start-Sleep 1
    if (Test-Path $log) {
      $fs = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite'); $fs.Seek($before, 'Begin') | Out-Null
      $new = (New-Object IO.StreamReader($fs)).ReadToEnd(); $fs.Close()
      if ($new -match 'ready on port') { $ok = $true; break }
      if ($new -match 'Traceback') { Write-Host $new; break }
    }
  }
  if (-not $ok) {
    if (Test-Path $log) { Write-Host '  ---- server.log (end) ----'; Get-Content $log -Tail 25 | ForEach-Object { Write-Host "  $_" } } else { Write-Host '  (no server.log was written)' }
    throw "the PC voice didn't start (see $log)"
  }
  Say 'The PC voice is running, and starts by itself when you sign in to Windows.'
  $lines = (Get-Content $log -Tail 8) -join "`n"
  if ($lines -match 'too slow to keep up') { Say 'The natural voice is too slow on this graphics card, so Kokoro reads.' }
  elseif ($lines -match 'Chatterbox') { Say 'The natural voice is on. Choose it on the phone in settings.' }
  if (-not $env:LB_CI) {
    Write-Host ''
    & $uv run --no-sync --project $srv python (Join-Path $srv 'loudbook_server.py') pair
  }
} catch {
  Write-Host ''
  Write-Host "  Setup stopped: $($_.Exception.Message)" -ForegroundColor Red
  if ($env:LB_CI) { exit 1 }
}
