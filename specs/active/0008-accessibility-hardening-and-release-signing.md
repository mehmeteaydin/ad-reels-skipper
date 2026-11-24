# Spec 0008 — Accessibility Hardening, Background Reliability, and Release Signing

- Status: In progress
- Mode: strict
- Plan: `specs/plans/0008-plan.md`
- Self-critique: done

## Intent
Users installing and running `Ad Reels Skipper` across different Android devices need the accessibility configuration narrowed strictly to active Instagram window inspection without auxiliary multi-window flags or app-backup exposure, and need locally signed release builds so standard Android package installers and device security scanners do not reject shared APKs as debug builds. On OEM Android ROMs, users also experience misleading `"Service Active"` status or `"Not working"` service disconnects after swiping the app away from Recent Apps or under battery optimization. Success means the app enforces least-privilege Instagram-only active-window access, protects the background service from Recent Apps task swipe-kills, detects disconnected ("Service Stopped") states accurately with live recovery and clear battery/Android 13+ restricted-settings guidance, and supports optional local release APK signing. Declaring `INTERNET` or additional `<uses-permission>` entries, showing persistent foreground-service notifications, monitoring non-Instagram apps, or logging/persisting screen text remain deliberately out of scope (`INV-1`, `INV-2`).

## Requirements
- **Least-privilege accessibility & manifest hardening (`BR-1`, `INV-1`, `INV-2`)**: Accessibility event delivery remains strictly scoped to `com.instagram.android`, active-window content retrieval and gesture execution remain enabled, and all auxiliary accessibility flags (`flagRetrieveInteractiveWindows` and `flagDefault`) are omitted. Application backup is disabled (`allowBackup="false"`), zero `<uses-permission>` entries exist in the manifest, and the accessibility service declares `stopWithTask="false"`.
- **Recent Apps swipe-kill protection**: The dashboard activity is excluded from Recent Apps (`excludeFromRecents="true"`) so closing the dashboard or clearing Recent Apps does not terminate the running accessibility process on OEM ROMs.
- **Three-state service status & live recovery (`S-1`, `S-2`, `S-9`)**:
  - `Permission Required` (`S-1`): Service is disabled in Android Accessibility Settings; displays `"Permission Required"` and `"Enable Accessibility Service"` (preserving the existing height/layout of `ServiceStatusCard` so all existing `S-1` initial-viewport elements remain visible without scrolling).
  - `Service Active` (`S-2`): Service is enabled in Android settings and actively bound/connected in the app process; displays `"Service Active"`.
  - `Service Stopped` (`S-9`): Service is listed as enabled in Android settings but is not bound/connected in the running app process (killed/malfunctioning state); displays `"Service Stopped"`, recovery instructions to toggle the service off and back on in Accessibility Settings, and a `"Reconnect in Accessibility Settings"` action. If the OS binds/connects the service while the dashboard is open, the UI transitions live to `"Service Active"`; if the OS unbinds or destroys the service, the UI updates accordingly.
- **Background reliability & Android 13+ sideloading setup card (`S-10`)**:
  - Placed below `KeywordsCard` at the bottom of the scrollable dashboard so existing `S-1`..`S-8` above-the-fold layouts remain unaffected.
  - Displays current battery optimization state: when battery optimization is active (not unrestricted), shows `"Battery Optimization Active"` warning and a `"Configure Battery Settings"` action opening system battery optimization settings (falling back to the app's system App Info page if unavailable, and failing silently if neither exists); when unrestricted, shows `"Battery Unrestricted"` confirmation without the warning button; refreshes on activity resume.
  - Displays a concise Android 13+ restricted-settings setup note (`App Info -> ⋮ -> Allow restricted settings`) with an `"Open App Info"` action opening the app's system App Info screen (failing silently if unavailable).
- **Optional local release APK signing**:
  - Supports release signing configured via untracked local `keystore.properties` or environment variables (`SKIPPER_KEYSTORE_FILE`, `SKIPPER_KEYSTORE_PASSWORD`, `SKIPPER_KEY_ALIAS`, `SKIPPER_KEY_PASSWORD`) only when all credentials and the keystore file are present; `.gitignore` excludes keystore and credential files (`keystore.properties`, `*.jks`, `*.keystore`). When absent (e.g., CI), release configuration remains unsigned and `./scripts/check` / `./scripts/check --fast` succeed unchanged.
- **Preserved behavior**: All existing dashboard states (`S-1`..`S-8`), weekly skip history, custom keywords, skip delay, lifetime counter, and ad-skipping rules (`BR-1`..`BR-7`, `INV-1`, `INV-2`) remain intact.

## User experience
- **Flow**:
  1. New user opens `Ad Reels Skipper` (`S-1` + `S-10` bottom card), uses `"Open App Info"` if needed on Android 13+ to allow restricted settings, configures unrestricted battery via `"Configure Battery Settings"`, and enables the accessibility service via `"Enable Accessibility Service"`.
  2. Once enabled and bound (`S-2`), the dashboard displays `"Service Active"`; leaving the app creates no Recent Apps card, protecting the running accessibility service when Recent Apps are cleared.
  3. If an OEM ROM kills or disconnects the service while the Android accessibility setting remains enabled (`S-9`), opening the dashboard displays `"Service Stopped"` with `"Reconnect in Accessibility Settings"` and transitions live back to `"Service Active"` as soon as the service reconnects.
- **States**:
  - `S-1`..`S-8` (existing dashboard states — preserved).
  - `S-9 (Service Stopped / Malfunctioning)`: Android accessibility setting is enabled, but the service is not connected in process; shows `"Service Stopped"` and `"Reconnect in Accessibility Settings"`.
  - `S-10 (Background Reliability & Sideload Setup — Battery Optimized vs. Battery Unrestricted)`: Bottom card showing `"Battery Optimization Active"` + `"Configure Battery Settings"` (`S-10a`), or `"Battery Unrestricted"` without the warning button (`S-10b`), alongside the Android 13+ restricted-settings note and `"Open App Info"` button.
- **Design reference**: none — the flow above is the design.
- **Accessibility**: Minimum `48.dp` touch targets, explicit `testTag` identifiers, and descriptive accessible labels/content descriptions on all new interactive controls.

## Constraints & out of scope
- Sensitive area: `Accessibility Service` (`docs/domain.md`, `docs/security.md`).
- Zero `android.permission.INTERNET` and zero `<uses-permission>` entries; zero screen-text logging or persistence (`INV-1`, `INV-2`).
- Zero secrets, keystores, or signing passwords committed to git.
- Strict architectural isolation: zero `android.*` imports in `domain`, and zero `AccessibilityService` or `dispatchGesture` references in `ui` (`docs/architecture.md`).
- Out of scope:
  - Persistent foreground-service notifications.
  - Play Store automated publishing or remote update distribution.
  - Non-Instagram platforms (YouTube Shorts, TikTok, Facebook Reels).
  - Editing shipped specs under `specs/done/` (`AGENTS.md` Rule 7).

## Acceptance criteria
- [ ] AC-1 — `accessibility_service_config.xml` scopes `packageNames` strictly to `"com.instagram.android"`, keeps `canRetrieveWindowContent="true"` and `canPerformGestures="true"`, omits `accessibilityFlags` (`flagRetrieveInteractiveWindows` and `flagDefault` absent), and `AndroidManifest.xml` declares `allowBackup="false"`, `excludeFromRecents="true"` on `MainActivity`, `stopWithTask="false"` on `SkipperAccessibilityService`, and zero `<uses-permission>` elements.
- [ ] AC-2 — When the accessibility service is listed as enabled in Android settings but is not connected in process (`S-9`), the dashboard displays `"Service Stopped"` with `"Reconnect in Accessibility Settings"` (dispatching `ACTION_ACCESSIBILITY_SETTINGS`), transitions live to `"Service Active"` as soon as `SkipperAccessibilityService` connects (`onServiceConnected`), and transitions out of `"Service Active"` if unbound/destroyed, verified by unit/Robolectric Compose UI tests and screenshot `S-9`.
- [ ] AC-3 — Below `KeywordsCard`, the **Background Reliability** card (`S-10`) displays `"Battery Optimization Active"` with `"Configure Battery Settings"` when battery optimizations apply, updates to `"Battery Unrestricted"` on resume once exempted, provides Android 13+ restricted-settings guidance with `"Open App Info"` (`ACTION_APPLICATION_DETAILS_SETTINGS` for the app package), and falls back safely without crashing when settings intents are unavailable, verified by Robolectric Compose UI tests and screenshots (`S-10a` optimized, `S-10b` unrestricted).
- [ ] AC-4 — `.gitignore` excludes `keystore.properties`, `*.jks`, and `*.keystore`, and `app/build.gradle.kts` configures release signing when valid local keystore credentials are supplied (producing a signed release APK via `assembleRelease`) while leaving builds and `./scripts/check` green when credentials are absent.
- [ ] AC-5 — Existing dashboard states (`S-1`..`S-8`, including `S-1` initial-viewport visibility without scrolling), `domain` zero-`android.*` purity, `ui` zero-`AccessibilityService` isolation, and `INV-1`/`INV-2` privacy guarantees remain intact.
- [ ] AC-6 — `./scripts/check`, `./scripts/check --fast`, and `./scripts/doctor` succeed with zero failures.

## Definition of Done
- [ ] Every acceptance criterion mapped to proof (test or reproducible observation)
- [ ] `scripts/check` green
- [ ] Independent review done; real findings fixed, noise rejected with written rationale, deferred ones in `specs/backlog.md`
- [ ] Docs / ADRs updated if behavior or architecture changed
- [ ] Spec moved to `specs/done/` (it becomes immutable there)

## Scorecard (fill at ship — honest numbers make the process improvable)
| Metric | Value |
|---|---|
| Spec revisions | |
| Fix rounds | |
| Review findings: real / noise / deferred | |
| Regressions introduced | |
| Bugs escaped to production | |
