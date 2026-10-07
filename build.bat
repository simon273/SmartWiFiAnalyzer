@echo off
rem Rebuild the debug APK: app\build\outputs\apk\debug\app-debug.apk
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 (
    echo.
    echo *** BUILD FAILED ***
    exit /b 1
)
echo.
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
