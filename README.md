# NetBox

## Build Android APKs on Windows

Run Gradle commands from the repository root (the folder containing `gradlew.bat`), not from the top-level `build` directory.

### Build separate APKs for each ABI

In PowerShell:

```powershell
.\gradlew.bat --refresh-dependencies :app:assembleDebug
```

The four APKs are written to:

```text
app\build\outputs\apk\debug\
```

Expected files:

- `app-arm64-v8a-debug.apk` — ARM 64-bit
- `app-armeabi-v7a-debug.apk` — ARM 32-bit
- `app-x86-debug.apk` — x86 32-bit
- `app-x86_64-debug.apk` — x86 64-bit

Each APK contains native libraries for its matching ABI. Install the APK that matches the target device.

### Copy APKs to the repository-level build folder

To collect all four APKs into one easy-to-find folder:

```powershell
.\gradlew.bat :app:collectDebugApks
```

The files are copied to:

```text
build\apks\debug\
```

The top-level `build\reports\problems\problems-report.html` is only a Gradle diagnostics report, not an APK. If no APKs appear, check whether Gradle ended with `BUILD SUCCESSFUL`; dependency-resolution or compilation errors prevent APK generation.
