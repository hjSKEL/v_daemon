@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0control.ps1" -Action Restart %*
set "runtimeExitCode=%ERRORLEVEL%"
if not "%runtimeExitCode%"=="0" pause
exit /b %runtimeExitCode%
