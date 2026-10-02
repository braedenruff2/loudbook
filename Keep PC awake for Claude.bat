@echo off
rem Keeps this PC from going to sleep while it is plugged in, so Claude can reach it from your phone.
rem The screen still turns off as usual. Undo with "Let PC sleep again.bat".
powercfg /change standby-timeout-ac 0
powercfg /change hibernate-timeout-ac 0
echo.
echo Done. This PC stays awake while plugged in (the screen still turns off).
echo Claude can now reach it whenever you message from your phone.
pause
