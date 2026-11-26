# Ad Reels Skipper

**Privacy-first Android Accessibility Service that automatically detects and skips sponsored Instagram Reels — 100% on-device with zero internet permission.**

`Ad Reels Skipper` (`com.adreels.skipper`) monitors the active Instagram (`com.instagram.android`) window locally using Android's Accessibility API, detects sponsored Reels labels in-memory, and dispatches a natural upward swipe gesture after a configurable delay.

## Features

- **Automatic Sponsored Reel Skipping**: Detects sponsored Reels and dispatches an upward swipe gesture automatically when the **Auto-Skip Sponsored Reels** master toggle is on (`150 ms` default delay, configurable from `50 ms` to `2000 ms` in `50 ms` steps).
- **Built-in & Custom Keywords**: Ships with read-only built-in labels (`Sponsored`, `Sponsorlu`, `Paid partnership`) and supports user-defined custom keywords (`2`–`40` characters, case- and Turkish-locale-folded matching).
- **Live 3-State Service Status**: Distinguishes **Permission Required** (onboarding), **Service Active** (connected in-process), and **Service Stopped** (system killed the background binding, with one-tap reconnect to Accessibility Settings).
- **Weekly Activity & History**: Tracks lifetime skipped Reels plus a **Monday–Sunday** weekly bar chart and browsable **Past Weeks** archive, persisted locally via Jetpack Preferences DataStore.
- **Background Reliability & Silent Keep-Alive**:
  - Promotes `SkipperAccessibilityService` to a silent, low-priority ongoing Foreground Service (`IMPORTANCE_MIN`, `specialUse`) while connected and active so OEM "Clear All" cleanups do not stop ad detection.
  - Provides in-app **Background Reliability** shortcuts for **Battery Optimization** (`Unrestricted`), **Keep-Alive Notification** (`POST_NOTIFICATIONS`), and **Xiaomi / OEM Autostart Settings**.

## Privacy & Security Model

- **Zero Network Access**: `AndroidManifest.xml` does **not** declare `android.permission.INTERNET` and the app includes no HTTP clients, analytics, or telemetry SDKs.
- **In-Memory Inspection Only**: Accessibility node text is inspected strictly in memory to match ad keywords and is never stored on disk, logged, or transmitted.
- **Least-Privilege Permissions & Scope**:
  - Manifest declares exclusively three permissions: `android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, and `android.permission.POST_NOTIFICATIONS`.
  - `accessibility_service_config.xml` is restricted strictly to `com.instagram.android` (`canRetrieveWindowContent="true"`, `canPerformGestures="true"`, no interactive multi-window flags).
  - `android:allowBackup="false"` prevents local preferences from being backed up off-device.
  - `android:stopWithTask="false"` preserves service binding across task removal, paired with static privacy-safe foreground notification text.

## Installation & Device Setup

**Requirements:** Android 8.0+ (`minSdk 26`, `targetSdk 35`), Instagram for Android (`com.instagram.android`).

### 1. Sideloaded APK on Android 13+ ("Restricted setting")

When installing via APK outside a store on Android 13 or newer, Android initially locks Accessibility toggles for sideloaded apps:

1. Open Android **Settings → Apps → Ad Reels Skipper** (App Info).
2. Tap the **⋮** menu in the top-right corner and select **Allow restricted settings**.
3. Return to **Settings → Accessibility → Ad Reels Skipper** and enable the service.

### 2. Xiaomi (MIUI / HyperOS) & OEM Background Reliability

To prevent Xiaomi MIUI/HyperOS or other aggressive OEMs from stopping the service when tapping **Clear All** in Recent Apps:

1. Open **Ad Reels Skipper** and scroll to the **Background Reliability** card.
2. Tap **Enable Keep-Alive Notification** so the silent protection notification is permitted.
3. Tap **Configure Battery Settings** and set battery usage to **Unrestricted**.
4. Tap **Open Autostart Settings** and enable **Autostart** for **Ad Reels Skipper**.
5. Optionally open **Recent Apps**, press and hold the **Ad Reels Skipper** card, and tap the **Lock** icon.
6. Enable **Ad Reels Skipper** from the top card (**Enable Accessibility Service**).

## Architecture

The codebase separates Android platform integration from pure Kotlin domain rules:

| Package | Responsibility | Boundary |
|---|---|---|
| `com.adreels.skipper.domain` | Keyword matching/validation, cooldown & debounce rules, weekly skip aggregation | Pure Kotlin only — zero `android.*` / `androidx.*` imports |
| `com.adreels.skipper.data` | Local persistence for settings, keywords, weekly counts, and lifetime skips | Jetpack Preferences DataStore (`StateFlow`) |
| `com.adreels.skipper.service` | `SkipperAccessibilityService`, gesture dispatch, silent keep-alive foreground service | Bound via `BIND_ACCESSIBILITY_SERVICE`, `com.instagram.android` only |
| `com.adreels.skipper.ui` | Material 3 Jetpack Compose dashboard and `DashboardViewModel` | Unidirectional data flow — zero `AccessibilityService` or gesture API references |

Architectural boundaries are enforced automatically by unit tests (`DomainArchitectureTest`, `ManifestVerificationTest`).

## Building & Local Release Signing

**Prerequisites:** JDK 17 and Android SDK Platform / Build-Tools 35 (`ANDROID_HOME`).

```bash
# Build debug APK
./gradlew :app:assembleDebug --no-daemon
```

### Optional Local Release APK Signing

Release builds (`:app:assembleRelease`) stay unsigned by default unless all four release signing values are supplied locally via gitignored `keystore.properties` in the project root:

```properties
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Or via environment variables:

```bash
export SKIPPER_KEYSTORE_FILE=/path/to/your-release.jks
export SKIPPER_KEYSTORE_PASSWORD=...
export SKIPPER_KEY_ALIAS=...
export SKIPPER_KEY_PASSWORD=...
./gradlew :app:assembleRelease --no-daemon
```

> Never commit `keystore.properties`, `*.jks`, or `*.keystore` files.

## Verification & Project Docs

This repository uses a spec-driven engineering workflow (`AGENTS.md`, `docs/`, `specs/`):

```bash
./scripts/check --fast     # Unit/Robolectric tests, ktlint, Detekt, and Android Lint
./scripts/check            # Full verification: tests, lint, and APK build
./scripts/doctor           # Workspace structure and spec/plan gate checks (--strict in CI)
```
