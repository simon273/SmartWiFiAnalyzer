@echo off
rem Install the debug APK on the connected phone, grant Wi-Fi scan permissions and start the app.
rem Run build.bat first (or use build_install.bat).
setlocal
cd /d "%~dp0"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "PKG=com.shimonfiltser.smartwifianalyzer"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK%" (
    echo APK not found: %APK%
    echo Run build.bat first.
    exit /b 1
)

"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo *** INSTALL FAILED - is the phone connected with USB debugging allowed? ***
    exit /b 1
)

"%ADB%" shell pm grant %PKG% android.permission.ACCESS_FINE_LOCATION
"%ADB%" shell pm grant %PKG% android.permission.NEARBY_WIFI_DEVICES
"%ADB%" shell am start -n %PKG%/.MainActivity
