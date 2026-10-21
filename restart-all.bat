@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0runtime\control.ps1" -Action Restart %*
exit /b %ERRORLEVEL%
