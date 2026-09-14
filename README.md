# Smart Expense Tracking

An Android app for turning receipt photos into reviewed expense records and exporting them to OneDrive. The intended Windows-side workflow then processes the handoff files and updates an Excel budget workbook.

## Features

- Import receipt images, extract expense details, review them, and correct them before export.
- Use the privacy-first local LiteRT-LM provider when its Gemma 4 E2B model is installed, or explicitly opt in to a saved OpenAI-compatible provider.
- Manage multiple provider profiles securely and import/export their configuration without credentials.
- Connect a personal Microsoft account and publish a normalized receipt JPEG before its versioned JSON handoff file, with retry support.
- Keep Main focused on receipt work while Settings contains provider, OneDrive, and image-preprocessing configuration.

## Prerequisites

- Android Studio with Android SDK Platform 35 and Build Tools installed.
- JDK 17 or the JDK bundled with Android Studio.
- A connected Android device or emulator (Android 9 / API 28 or newer) to run the app.

## Setup and run

1. Clone the repository and open it in Android Studio, or use the Gradle wrapper from the repository root.
2. Set `sdk.dir` in `local.properties` to your Android SDK location if Android Studio has not created it.
3. Build and install the debug app:

   ```powershell
   .\gradlew.bat :android-app:assembleDebug
   .\gradlew.bat :android-app:installDebug
   ```

4. In the app, open Settings to configure an optional remote provider or connect and verify OneDrive before exporting receipts.

Local on-device extraction additionally requires `gemma-4-E2B-it.litertlm` in the app's private models directory. The Android LiteRT-LM device path is still being validated; remote providers are optional and require explicit opt-in.

## Verify

Run the JVM tests and assemble the Android app:

```powershell
.\gradlew.bat test :android-app:assembleDebug
```

The repository also includes contract fixtures and PowerShell checks in `fixtures/` and `scripts/`.

## Implementation status

The Android receipt workflow, provider profile management, OneDrive authentication, image-first paired export, and provider configuration transfer are implemented and have unit-test coverage. Physical-device validation has confirmed the main workflow, remote LM Studio extraction, profile transfer, and OneDrive export.

Phase 1 integration work remains in progress. The main open items are validating on-device LiteRT-LM receipt extraction and completing the Windows Hermes/PowerShell Excel update flow, including workbook mapping and duplicate handling. See [the current status](docs/current%20status.md), [the implementation plan](docs/PLAN.md), and [the requirements](docs/requirements.md) for details.
