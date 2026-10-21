@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0control.ps1" -Action Stop
set "runtimeExitCode=%ERRORLEVEL%"
if not "%runtimeExitCode%"=="0" pause
exit /b %runtimeExitCode%
