@echo off
rem Sends any changes in the Loudbook-android folder to GitHub (run every 5 minutes by the
rem "Loudbook publish" scheduled task that "Turn on phone updates.bat" sets up).
cd /d "%~dp0.."
if not exist ".git\" exit /b 0
git add -A >nul 2>&1
git diff --cached --quiet
if errorlevel 1 git commit -q -m "Update %date% %time%" >nul 2>&1
git push -q origin main > "%~dp0last-publish.txt" 2>&1
