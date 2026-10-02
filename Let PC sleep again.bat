@echo off
rem Puts sleep back to normal: the PC sleeps after 30 minutes of not being used (plugged in).
powercfg /change standby-timeout-ac 30
echo.
echo Done. The PC goes to sleep after 30 minutes of not being used again.
pause
