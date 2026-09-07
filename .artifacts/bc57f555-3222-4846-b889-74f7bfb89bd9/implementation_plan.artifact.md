# Gradle Upgrade to 9.7.0

The Build screen recommends upgrading to Gradle version 9.7.0. This plan covers the upgrade of the Gradle wrapper and any necessary alignment of the Android Gradle Plugin (AGP) to ensure build stability and performance, potentially addressing previous build timeout issues.

## User Review Required

> [!IMPORTANT]
> Gradle 9.7.0 is a newer release. While Android Studio recommends it, I will first verify that the current Android Gradle Plugin (AGP) version 8.7.3 is fully compatible or if a minor bump to AGP 8.9.x is recommended for better support of Gradle 9.x features.

## Proposed Changes

### Build System

#### [MODIFY] [gradle-wrapper.properties](file:///D:/Source/smart-expense-tracking/gradle/wrapper/gradle-wrapper.properties)
- Update `distributionUrl` to `https\://services.gradle.org/distributions/gradle-9.7.0-bin.zip`.

#### [MODIFY] [root build.gradle.kts](file:///D:/Source/smart-expense-tracking/build.gradle.kts)
- (Conditional) If sync fails or warnings appear, upgrade AGP from `8.7.3` to the latest stable version (e.g., `8.9.1`).

### Documentation

#### [MODIFY] [current status.md](file:///D:/Source/smart-expense-tracking/docs/current%20status.md)
- Add a maintenance task for the Gradle upgrade.

## Verification Plan

### Automated Tests
- Run `gradle :android-app:assembleDebug` to verify the Android build.
- Run `gradle test` to ensure existing JVM and Android unit tests still pass.

### Manual Verification
- Perform a Gradle Sync in Android Studio and ensure no warnings remain in the Build window.
- Verify that the `android-app` module compiles without the previous timeout issues.
