@echo off
rem Keeps the Loudbook PC voice running: brings it back up after an update (exit code 3) or a
rem crash. Started with no window by run-server.vbs. Delete %LOCALAPPDATA%\Loudbook\run.on to stop.
setlocal
set "LB=%LOCALAPPDATA%\Loudbook"
set "UV_PROJECT_ENVIRONMENT=%LB%\venv"
set "PYTHONUTF8=1"
:again
if not exist "%LB%\run.on" exit /b 0
set "EXTRA="
if exist "%LB%\natural.on" set "EXTRA=--extra natural"
"%LB%\bin\uv.exe" sync --quiet --python 3.12 --project "%~dp0." %EXTRA% >> "%LB%\server.log" 2>&1
"%LB%\bin\uv.exe" run --no-sync --project "%~dp0." python -u "%~dp0loudbook_server.py" serve >> "%LB%\server.log" 2>&1
if %errorlevel%==3 goto again
timeout /t 20 /nobreak >nul
goto again
