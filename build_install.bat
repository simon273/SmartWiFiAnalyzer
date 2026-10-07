@echo off
rem Rebuild, install on the phone and start the app.
call "%~dp0build.bat" || exit /b 1
call "%~dp0install.bat"
