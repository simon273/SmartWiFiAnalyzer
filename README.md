# Smart WiFi Analyzer

A lightweight Wi-Fi analyzer for Android, written in Kotlin with no third-party libraries.

## Features

- **Networks** — nearby access points sorted by signal: SSID, BSSID, security, channel, frequency, channel width, Wi-Fi standard (4/5/6/6E/7) and signal strength in dBm. Your current network is highlighted.
- **Channels** — networks drawn as curves over the channels they occupy.
- **Time** — signal level of each network over the last 2 minutes.
- **Rating** — interference score for every channel and a recommended channel (1/6/11 on 2.4 GHz).
- 2.4 / 5 / 6 GHz bands, pause/resume, dark theme.

## Requirements

- Android 10 (API 29) or newer.
- Location must be on and the Location / Nearby devices permissions granted — Android does not return scan results otherwise.
- Android limits foreground apps to 4 scans per 2 minutes. For faster updates turn off **Developer options → Wi-Fi scan throttling**.

## Build

Requires the Android SDK (path in `local.properties`, created by Android Studio) and JDK 17+.

```bat
build.bat           :: build app\build\outputs\apk\debug\app-debug.apk
install.bat         :: install on the connected phone, grant permissions and start
build_install.bat   :: both
```

`build.bat` uses the JDK bundled with Android Studio unless `JAVA_HOME` is set. The project also opens directly in Android Studio.

## Author

Shimon Filtser — shimon.filtzer@gmail.com
