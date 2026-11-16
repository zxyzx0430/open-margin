@echo off
setlocal
rem Local/dev fallback: build OpenMargin-Setup.exe with Inno Setup.
rem Build-Installer.ps1 locates ISCC.exe or offers winget installation.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build-Installer.ps1" %*
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" pause
exit /b %RC%
