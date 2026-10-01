@echo off
rem Removes every phone's access to the Loudbook PC voice (pair again with "Pair a phone.bat").
setlocal
set "UV_PROJECT_ENVIRONMENT=%LOCALAPPDATA%\Loudbook\venv"
"%LOCALAPPDATA%\Loudbook\bin\uv.exe" run --quiet --python 3.12 --project "%~dp0pc-server" python "%~dp0pc-server\loudbook_server.py" forget
pause
