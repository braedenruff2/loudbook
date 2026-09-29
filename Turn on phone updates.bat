@echo off
setlocal EnableExtensions
title Loudbook - phone updates
cd /d "%~dp0"
echo.
echo   Loudbook phone updates
echo   ======================
echo   This puts the app's code in a GitHub repository you own. GitHub builds the app each
echo   time it changes, and Loudbook on your phone installs the new version by itself.
echo.

where git >nul 2>nul
if errorlevel 1 (
  echo   Git isn't installed on this PC. Opening its download page: install it with the
  echo   default options, then double-click this file again.
  start "" "https://git-scm.com/download/win"
  echo.
  pause
  exit /b 1
)

set "REPO="
if exist ".git\" for /f "usebackq delims=" %%u in (`git config --get remote.origin.url`) do set "REPO=%%u"
if defined REPO goto have_repo

echo   Step 1. On GitHub, make a new repository:
echo             name: loudbook      visibility: Public
echo           Leave everything else as it is (no README), then click "Create repository".
echo           Opening that page now...
start "" "https://github.com/new?name=loudbook&visibility=public"
echo.
echo   Step 2. Copy the new repository's address from the browser's address bar
echo           (it looks like https://github.com/YOUR-NAME/loudbook) and paste it here.
echo.
set /p "REPO=  Address: "
if not defined REPO ( echo   No address given. & pause & exit /b 1 )
set "REPO=%REPO:"=%"
set "REPO=%REPO: =%"
if /i "%REPO:~-4%"==".git" set "REPO=%REPO:~0,-4%"
if /i "%REPO:~-1%"=="/" set "REPO=%REPO:~0,-1%"

if not exist ".git\" (
  git init -q
  git checkout -q -B main
)
git config user.name >nul 2>nul || git config user.name "Loudbook"
git config user.email >nul 2>nul || git config user.email "loudbook@users.noreply.github.com"
git remote remove origin >nul 2>nul
git remote add origin "%REPO%.git"

:have_repo
set "PAGE=%REPO%"
if /i "%PAGE:~-4%"==".git" set "PAGE=%PAGE:~0,-4%"
echo.
echo   Uploading the app's code to %PAGE%
echo   (if a GitHub sign-in window opens, sign in and allow it)
git add -A
git commit -q -m "Loudbook" >nul 2>nul
git push -u origin main
if errorlevel 1 (
  echo.
  echo   The upload didn't go through. Check the address and that you're signed in to
  echo   GitHub, then run this again.
  pause
  exit /b 1
)

rem From now on, changes to this folder go up by themselves every 5 minutes, without a window.
schtasks /create /f /tn "Loudbook publish" /sc minute /mo 5 /tr "wscript.exe //B \"%~dp0tools\publish.vbs\"" >nul
if errorlevel 1 (
  echo   Couldn't set up the automatic upload. Changes will only go up when you run this file.
) else (
  echo   Automatic upload is on: changes in this folder go to GitHub every 5 minutes.
)

echo.
echo   Done. GitHub is building the app now (about 5 minutes the first time):
echo   %PAGE%/actions
echo.
echo   Then, on your phone, open this page and tap Loudbook.apk:
echo   %PAGE%/releases/latest
echo.
start "" "%PAGE%/actions"
pause
