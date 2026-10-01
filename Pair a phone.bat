@echo off
rem Shows a one-time code to pair a phone with the Loudbook PC voice.
setlocal
title Loudbook - pair a phone
set "UV_PROJECT_ENVIRONMENT=%LOCALAPPDATA%\Loudbook\venv"
if not exist "%LOCALAPPDATA%\Loudbook\bin\uv.exe" (
  echo   Run "Set up PC voice.bat" first.
  pause
  exit /b 1
)
"%LOCALAPPDATA%\Loudbook\bin\uv.exe" run --quiet --python 3.12 --project "%~dp0pc-server" python "%~dp0pc-server\loudbook_server.py" pair
pause
