# Smart Expense Tracking

An Android app for turning receipt photos into reviewed expense records, exporting them to OneDrive, and automatically updating an Excel budget workbook.

## Why this exists

Canada's open-banking policy is formally called the **Consumer-Driven Banking Regulations**. It is still being implemented: the Government of Canada pre-published the proposed Regulations for consultation in June 2026, ahead of their staged coming-into-force after final publication. See the Department of Finance Canada's [official update on the proposed Consumer-Driven Banking Regulations](https://www.canada.ca/en/department-finance/news/2026/06/government-pre-publishes-regulations-to-prevent-fraud-and-facilitate-the-next-phase-of-consumer-driven-banking.html).

Until secure, standardized bank-data sharing is broadly available, automatic expense-tracking apps cannot work consistently across Canadian financial institutions. I currently manage monthly spending in an Excel workbook, but entering transactions manually is tedious. This project automates that personal process while keeping ongoing costs minimal.

## Personal workflow

1. Take a picture of a receipt on a phone.
2. The Android app extracts receipt details with a chosen LLM provider: a privacy-first local model, a private or self-hosted OpenAI-compatible provider, or an explicitly enabled remote provider.
3. Review and correct the extracted transaction, then upload the normalized receipt image and its receipt-information handoff to OneDrive.
4. A five-minute cron job on a mini PC reads the synchronized handoff, updates the correct monthly sheet in the Excel workbook, and posts reportable results to a private Discord channel.

## Features

### 1. Capture and review on Android

- Import a receipt image and inspect the processed image with pinch or Zoom in/Zoom out controls.
- Review each distinct transaction separately, correct fields when needed, and export low-confidence receipts as `confirmed` or corrected receipts as `manual`.
- Keep receipt work on Main, with provider, OneDrive, image-preprocessing, and device-name settings in Settings.

### 2. Extract with a provider you control

- Use the privacy-first local LiteRT-LM provider when its Gemma 4 E2B model is installed, or explicitly opt in to a saved OpenAI-compatible provider.
- Manage multiple provider profiles securely and import or export their configuration without credentials.

### 3. Hand off the receipt through OneDrive

- Connect a personal Microsoft account and publish a normalized receipt JPEG before its versioned JSON handoff, with retry support.
- Preserve an image-first, paired handoff so the Windows processor never receives a processable receipt record without its image.

### 4. Update Excel and notify privately

- An agent harness such as Hermes Agent or OpenCode manages the five-minute cron schedule and the private Discord communication on the mini PC.
- Its deterministic script-only job processes synchronized OneDrive handoffs and updates the Excel workbook through desktop Excel.
- Empty runs stay quiet; inserts, duplicates, and review/error outcomes are delivered to the configured private Discord channel.

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
5. Enable Debug output in Settings to inspect the raw extraction response and each generated handoff JSON on Main.

Local on-device extraction additionally requires `gemma-4-E2B-it.litertlm` in the app's private models directory. The Android LiteRT-LM device path is still being validated; remote providers are optional and require explicit opt-in.

## Microsoft Graph OneDrive authentication

OneDrive export uses MSAL with a Microsoft Entra ID **public-client** registration for a personal Microsoft account. To create a registration for your own build:

1. In the [Microsoft Entra admin center](https://entra.microsoft.com/), open **App registrations** > **New registration**, select **Personal Microsoft accounts**, and copy the resulting **Application (client) ID**.
2. Open the registration's **Authentication** page. Under **Add a platform**, choose **Android** and enter this app's package name: `com.hugo.smartexpense.app`. Generate the signature hash from the keystore used to sign the APK, enter it, and copy the portal-generated `msauth://...` redirect URI. Enable **Allow public client flows** under **Advanced settings**.
3. Under **API permissions**, add Microsoft Graph **delegated** permission `Files.ReadWrite`. Do not add application permissions or create a client secret: an Android app is a public client and the user grants access to their own OneDrive.
4. Replace `client_id` and `redirect_uri` in `android-app/src/main/res/raw/auth_config_single_account.json`. Update the `BrowserTabActivity` intent-filter path in `android-app/src/main/AndroidManifest.xml` to the same URL-decoded signature hash (not the URL-encoded JSON value). Keep the existing `PersonalMicrosoftAccount` authority and `SINGLE` account mode.
5. Create the target OneDrive folders before connecting: `Documents/2_Others/Expenses_finance/logs` and `Documents/2_Others/Expenses_finance/receipt_images`. In the app, use **Connect** and then **Verify OneDrive access**.

The debug build validates that its signing certificate matches both configured redirect values. If it fails, register the hash for the keystore that signs the APK, then update the Entra registration, MSAL JSON, and manifest together. Release builds require a redirect registered for the release-signing certificate. See Microsoft's [Android redirect URI setup](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-redirect-uri), [MSAL Android configuration](https://learn.microsoft.com/en-us/entra/msal/android/configure-your-app), and [Microsoft Graph permissions reference](https://learn.microsoft.com/en-us/graph/permissions-reference#files-permissions).

## Verify

Run the JVM tests and assemble the Android app:

```powershell
.\gradlew.bat test :android-app:assembleDebug
```

The repository also includes contract fixtures and PowerShell checks in `fixtures/` and `scripts/`.

## Implementation status

The Android receipt workflow, provider profile management, OneDrive authentication, image-first paired export, and provider configuration transfer are implemented and have unit-test coverage. Physical-device validation has confirmed the main workflow, remote LM Studio extraction, profile transfer, and OneDrive export. The Windows PowerShell/Excel COM processor is implemented and verified against disposable copies of the real workbook. Its five-minute Hermes no-agent job is active, uses the repository PowerShell entry point through a thin external wrapper, and delivers reportable outcomes to Discord.

Phase 1 integration work remains in progress. The main open item is validating on-device LiteRT-LM receipt extraction. Run the Windows checks with `pwsh -NoLogo -NoProfile -NonInteractive -File scripts/test-handoff-contract.ps1` and `pwsh -NoLogo -NoProfile -NonInteractive -File scripts/test-hermes-excel-update.ps1`; the latter copies the real workbook to a disposable temp tree and never writes the source. See [the current status](docs/current%20status.md), [the implementation plan](docs/PLAN.md), and [the requirements](docs/requirements.md) for details.
