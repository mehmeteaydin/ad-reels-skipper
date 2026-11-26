# Spec 0009 — Foreground Keep-Alive Protection and OEM Autostart Guidance

- Status: Shipped
- Mode: strict
- Plan: `specs/plans/0009-plan.md`
- Self-critique: done
- Supersedes: 0008/AC-1

## Intent
Users running `Ad Reels Skipper` on OEM Android ROMs — especially Xiaomi Android 13 (MIUI/HyperOS) — still have the background accessibility service killed when tapping "Clear All" in Recent Apps because the app currently runs without a Foreground Service keep-alive notification and hides its dashboard card from Recent Apps (`excludeFromRecents="true"`), preventing optional Recent Apps card locking. Success means `SkipperAccessibilityService` promotes itself to a silent, low-priority Foreground Service whenever connected and enabled, removes `excludeFromRecents="true"`, and guides users through Android 13+ notification permission and Xiaomi/OEM Autostart setup directly from the dashboard's **Background Reliability** card. Adding `INTERNET` or unrelated permissions, displaying dynamic or inspected screen content in notifications, or monitoring non-Instagram applications remain deliberately out of scope (`INV-1`, `INV-2`).

## Requirements
- **Exact least-privilege permission allow-list & manifest update (`Supersedes: 0008/AC-1`, `INV-1`, `INV-2`)**:
  - `AndroidManifest.xml` declares **exclusively** `android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, and `android.permission.POST_NOTIFICATIONS` (exact allow-list; zero `INTERNET` or other permissions).
  - `SkipperAccessibilityService` declares `android:foregroundServiceType="specialUse"` alongside its special-use subtype property (`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), while preserving `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` and `android:stopWithTask="false"`.
  - `MainActivity` omits `android:excludeFromRecents="true"` so users on Xiaomi/OEM devices can keep and optionally lock the dashboard card in Recent Apps.
  - `android:allowBackup="false"` and least-privilege `accessibility_service_config.xml` (`packageNames="com.instagram.android"`, `canRetrieveWindowContent="true"`, `canPerformGestures="true"`, zero `accessibilityFlags`) remain strictly preserved.
- **Silent Foreground Keep-Alive lifecycle (`BR-2`, `INV-1`)**:
  - When `SkipperAccessibilityService` connects (`onServiceConnected`) and `Master Toggle` is `ON`, it creates a silent low-importance notification channel (`IMPORTANCE_MIN`, no sound, vibration, or launcher badge) and calls foreground promotion (`startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+ and 2-argument `startForeground` on API 26–33, covering Xiaomi Android 13 / API 33) using a static, privacy-safe ongoing notification whose tap action opens `MainActivity` via an immutable `PendingIntent`.
  - When `Master Toggle` is switched `OFF`, or when `onUnbind` or `onDestroy` executes, the service immediately stops foreground mode and removes the ongoing notification (`stopForeground(STOP_FOREGROUND_REMOVE)`). Turning `Master Toggle` back `ON` while connected immediately resumes foreground keep-alive mode.
  - Any `RuntimeException` or `SecurityException` thrown during foreground promotion or removal is caught fail-safely so the accessibility service continues operating without crashing.
- **Dashboard Keep-Alive Notification & OEM Autostart setup (`S-10`)**:
  - Inside the bottom **Background Reliability** card (below `KeywordsCard`, preserving `S-1` initial-viewport height without scrolling):
    - **Keep-Alive Notification section**: Displays `"Keep-Alive Notification Active"` when notifications are enabled (including by default on Android < 13); when disabled on Android 13+, displays `"Keep-Alive Notification Off"` with an `"Enable Keep-Alive Notification"` button (`48.dp` min touch target) that triggers the Android 13+ notification permission request or opens system app notification / App Info settings if permission cannot be requested inline (failing silently if unavailable), refreshing on activity resume and permission callback.
    - **OEM Autostart section**: Displays concise Xiaomi/OEM Autostart and Recent Apps lock guidance alongside an `"Open Autostart Settings"` button (`48.dp` min touch target) that attempts Xiaomi's MIUI Autostart activity (`com.miui.securitycenter` / `com.miui.permcenter.autostart.AutoStartManagementActivity`) and falls back cleanly to App Info (`ACTION_APPLICATION_DETAILS_SETTINGS`) on non-Xiaomi devices or on `ActivityNotFoundException` / `SecurityException` without crashing.
- **Preserved behavior**: Existing states `S-1`..`S-9`, `S-1` initial-viewport visibility without scrolling, weekly skip history, custom keywords, skip delay, conditional release signing, and `BR-1`..`BR-7` / `INV-1` / `INV-2` remain intact.

## User experience
- **Flow**:
  1. Fresh user (including Xiaomi Android 13) opens `Ad Reels Skipper` (`S-1`), scrolls to the bottom **Background Reliability** card (`S-10a`), enables **Keep-Alive Notification**, sets Battery to **Unrestricted**, opens **Autostart Settings**, and enables the Accessibility service.
  2. Once connected with `Master Toggle` `ON` (`S-2`), a silent ongoing notification (`IMPORTANCE_MIN`) keeps the service process prioritized across "Clear All" in Recent Apps; users can also optionally lock the app card in Recent Apps.
  3. Tapping the ongoing notification returns to `MainActivity`; disabling `Master Toggle` or disconnecting the service cleanly removes the ongoing notification (`S-3`, `S-9`).
- **States**:
  - `S-1`..`S-9` (existing dashboard states — preserved).
  - `S-10a (Background Reliability — Needs Setup)`: Bottom card showing `"Battery Optimization Active"` + `"Configure Battery Settings"`, `"Keep-Alive Notification Off"` + `"Enable Keep-Alive Notification"`, Xiaomi/OEM `"Open Autostart Settings"`, and Android 13+ `"Open App Info"`.
  - `S-10b (Background Reliability — Configured)`: Bottom card showing `"Battery Unrestricted"` (warning CTA hidden), `"Keep-Alive Notification Active"` (warning CTA hidden), plus Xiaomi/OEM `"Open Autostart Settings"` and `"Open App Info"`.
- **Design reference**: none — the flow above is the design.
- **Accessibility**: Minimum `48.dp` touch targets, explicit `testTag` identifiers, and descriptive accessible labels on all new controls.

## Constraints & out of scope
- Sensitive area: `Accessibility Service` (`docs/domain.md`, `docs/security.md`).
- Zero `android.permission.INTERNET`; exact 3-permission allow-list only (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`); zero dynamic or inspected screen text in notifications (`INV-1`, `INV-2`).
- Strict architectural isolation: zero `android.*` imports in `domain`, and zero `AccessibilityService` or `dispatchGesture` references in `ui` (`docs/architecture.md`).
- Shipped specs under `specs/done/` remain immutable (`AGENTS.md` Rule 7).
- Out of scope:
  - Non-Instagram platforms (YouTube Shorts, TikTok, Facebook Reels).
  - Root or ADB-privileged operations.
  - Third-party notification or OEM helper dependencies.
  - Play Store publishing automation.

## Acceptance criteria
- [x] AC-1 — `AndroidManifest.xml` declares exclusively the 3-permission allow-list (`android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, `android.permission.POST_NOTIFICATIONS`, zero `INTERNET`), declares `foregroundServiceType="specialUse"` + subtype property and `stopWithTask="false"` on `SkipperAccessibilityService`, omits `excludeFromRecents="true"` on `MainActivity`, keeps `allowBackup="false"`, and keeps `accessibility_service_config.xml` strictly scoped to `com.instagram.android` with no `accessibilityFlags`.
- [x] AC-2 — On both API 33 (Xiaomi Android 13) and API 35, `SkipperAccessibilityService` promotes to a silent ongoing foreground notification (`IMPORTANCE_MIN`, static privacy-safe text, immutable `MainActivity` `PendingIntent`) on `onServiceConnected` when `Master Toggle` is `ON`, stops foreground (`STOP_FOREGROUND_REMOVE`) on `Master Toggle OFF` / `onUnbind` / `onDestroy`, re-enters foreground when `Master Toggle` turns back `ON`, and catches `RuntimeException` / `SecurityException` fail-safely without crashing.
- [x] AC-3 — Inside `BackgroundReliabilityCard` (`S-10a`/`S-10b` below `KeywordsCard`), the dashboard displays `"Keep-Alive Notification Off"` + `"Enable Keep-Alive Notification"` (`48.dp` min height) when notifications are disabled on Android 13+, updates to `"Keep-Alive Notification Active"` once granted/resumed, and falls back safely to notification/App Info settings without crashing, verified by Robolectric Compose UI tests and screenshots (`S-10a`, `S-10b`).
- [x] AC-4 — Inside `BackgroundReliabilityCard`, `"Open Autostart Settings"` (`48.dp` min height) attempts Xiaomi MIUI Autostart (`com.miui.securitycenter` / `com.miui.permcenter.autostart.AutoStartManagementActivity`), falls back cleanly to `ACTION_APPLICATION_DETAILS_SETTINGS` on `ActivityNotFoundException` / `SecurityException`, and recovers silently if no settings activity exists.
- [x] AC-5 — Existing dashboard states (`S-1`..`S-9`, including `S-1` initial-viewport visibility without scrolling), release signing contract, `domain` zero-`android.*` purity, `ui` zero-`AccessibilityService` isolation, and `INV-1`/`INV-2` remain intact.
- [x] AC-6 — `./scripts/check`, `./scripts/check --fast`, and `./scripts/doctor` succeed with zero failures.

## Definition of Done
- [x] Every acceptance criterion mapped to proof (test or reproducible observation)
- [x] `scripts/check` green
- [x] Independent review done; real findings fixed, noise rejected with written rationale, deferred ones in `specs/backlog.md`
- [x] Docs / ADRs updated if behavior or architecture changed
- [x] Spec moved to `specs/done/` (it becomes immutable there)

## Scorecard (fill at ship — honest numbers make the process improvable)
| Metric | Value |
|---|---|
| Spec revisions | 0 |
| Fix rounds | 0 |
| Review findings: real / noise / deferred | 0 real / 0 noise / 0 deferred |
| Regressions introduced | 0 |
| Bugs escaped to production | 0 |
