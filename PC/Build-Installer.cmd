@echo off
setlocal
set "MAKENSIS=%ProgramFiles(x86)%\NSIS\makensis.exe"
if not exist "%MAKENSIS%" set "MAKENSIS=%ProgramFiles%\NSIS\makensis.exe"

if not exist "%MAKENSIS%" (
  echo NSIS was not found.
  echo Install it from https://nsis.sourceforge.io/Download and run this file again.
  pause
  exit /b 1
)

if not exist "%~dp0Installer\Output" mkdir "%~dp0Installer\Output"
"%MAKENSIS%" "%~dp0Installer\LAN-Send.nsi"
if errorlevel 1 (
  echo.
  echo The installer build failed. Review the messages above.
  pause
  exit /b 1
)

echo.
echo Installer created successfully:
echo %~dp0Installer\Output\LAN-Send-Setup-3.0.0.exe
pause
