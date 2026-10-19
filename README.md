# AirShare for Android

A local-network file-transfer Android app. It can receive files from Android's share menu, provide selected files for browser download, and accept browser uploads from an iPhone/computer on the same Wi-Fi.

## Compatibility

This is **not** a native Apple AirDrop implementation and will not appear as an AirDrop target in the iPhone share sheet. Apple controls AirDrop compatibility. This project provides a browser-based local-network fallback without requiring an iPhone app.

## Build APK with GitHub Actions

1. Create a GitHub repository and upload all files from this project.
2. Open **Actions** and enable workflows if prompted.
3. Select **Build Android APK** → **Run workflow**.
4. Download the `AirShare-debug-apk` artifact from the completed run.
5. Unzip it and install `app-debug.apk` on Android.

This workflow builds a debug APK. A distributable release needs a protected signing key and release-signing configuration; never commit keystores or passwords to the repository.

## Use

1. Install and open AirShare on Android.
2. Connect both devices to the same Wi-Fi.
3. Open the displayed URL in Safari on iPhone.
4. Upload from iPhone to Android in the browser page. To send Android files to iPhone, select files in AirShare or use Android's Share menu and choose AirShare, then download them from the browser page.

## Limitations and security

- Transfers use local HTTP, not end-to-end encryption. Use a trusted Wi-Fi network and stop the app after use.
- The URL contains a random token. Anyone who obtains it and can reach the phone on the network may access the transfer page.
- Files are held in memory; avoid very large files.
- Some Wi-Fi networks isolate devices and prevent local transfers.
- This source project has not been device-tested in this environment. GitHub Actions is intended to build the APK; a successful build and real-device testing are still required before calling it production-ready.

## Development

Android Gradle Plugin 8.7.3, Kotlin 2.0.21, min Android 8.0 (API 26).
