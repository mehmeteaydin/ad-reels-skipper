# Spec 0003 — Settings Persistence and Dashboard UI

- Status: Approved
- Mode: strict
- Plan: `specs/plans/0003-plan.md`
- Self-critique: done

## Intent
Instagram users want direct control over how `ad-reels-skipper` detects and skips sponsored Reels, the ability to add custom ad labels in their own language, and visibility into how many sponsored Reels have been automatically skipped. This feature provides a single-screen dashboard where users can check whether the Android Accessibility Service permission is active (with one-tap navigation to system accessibility settings when disabled), toggle automatic skipping on or off (`Master Toggle`), adjust the `Skip Delay` (`100 ms – 2000 ms`), manage custom keywords alongside built-in defaults (`Sponsored`, `Sponsorlu`, `Paid partnership`), and view their lifetime skipped-Reels count (`Skip Metrics`). All preferences and metrics persist locally on the device and take effect immediately in the active service without any network access or off-device data flow (`INV-1`). Cloud sync, user accounts, multi-app support beyond Instagram, and OCR/image-based detection remain deliberately out of scope.

## Requirements
- **Service permission status and onboarding navigation**: The dashboard displays whether the system accessibility service is currently enabled. When disabled, it displays a permission-required prompt with an action that opens the system accessibility settings screen; when the user returns to the app, the displayed permission status refreshes automatically.
- **Master Toggle persistence and live enforcement (`BR-2`)**: The `Master Toggle` defaults to `ON` (`true`), persists across app restarts, and immediately governs service behavior. When turned `OFF`, the dashboard shows a paused state and the service discards incoming events without dispatching gestures or incrementing the skip counter.
- **Configurable `Skip Delay` with boundary clamping (`BR-5`)**: The `Skip Delay` defaults to `400 ms`, is adjustable in `50 ms` steps within the inclusive range `100 ms – 2000 ms`, and persists across restarts. Any out-of-range value supplied to the domain or persistence boundary is clamped to `100 ms – 2000 ms`.
- **Built-in and custom keyword management (`BR-3`, `BR-6`)**: The built-in keywords (`"Sponsored"`, `"Sponsorlu"`, `"Paid partnership"`) are always present, displayed as read-only defaults, and cannot be deleted. Users can add and remove custom keywords that persist locally and are unioned with the built-in keywords for detection.
- **Custom keyword validation and normalization (`BR-6`)**: Leading and trailing whitespace is trimmed from candidate custom keywords before validation. Inputs that are blank, contain no letters or digits (punctuation/whitespace only), are shorter than `2` characters or longer than `40` characters after trimming, or duplicate an existing built-in or custom keyword under Turkish/ASCII case-insensitive comparison (`İ`/`I`/`ı`/`i` folded) are rejected with an inline validation message and leave the saved keyword set unchanged. Valid custom keywords clear the input field and any prior validation error upon addition.
- **Lifetime skip counter persistence and increment (`BR-7`)**: The lifetime skipped-Reels counter defaults to `0`, persists across restarts, and increments by `1` exclusively when a skip gesture completes (`onCompleted`) — never when a pending skip is cancelled before delay, aborted on pre-dispatch re-evaluation, or rejected/cancelled by the OS.
- **Corrupted local storage recovery**: If persisted local data cannot be read due to corruption or I/O failure, the system falls back safely to default settings (`Master Toggle = ON`, `Skip Delay = 400 ms`, built-in keywords only, `Skipped Reels = 0`) without crashing.
- **App identity and UI/manifest hygiene (`B-1`, `B-4`, `B-6`)**: The application declares a custom launcher icon instead of the framework fallback icon (`B-1`), wraps UI screens in Material 3 theming with localized string resources and verified launcher intent-filter metadata (`B-4`), and captures deterministic headless screenshots for every dashboard state (`B-6`).

## User experience
- **Flow**:
  1. The user launches `Ad Reels Skipper` from the Android launcher and lands on the single-screen Material 3 Dashboard.
  2. At the top, the **Service Status Card** shows whether the Android Accessibility Service is enabled (`Service Active` vs `Permission Required`). When disabled, tapping `"Enable Accessibility Service"` launches the system Accessibility Settings screen; returning to the app refreshes the card automatically.
  3. Below the status card, the **Lifetime Skipped Reels** metric card displays the persisted count of skipped sponsored Reels (`0` initially) alongside the **Master Toggle** switch (`ON` = active, `OFF` = paused).
  4. The **Skip Delay** card displays the current delay in milliseconds (e.g., `"400 ms"`) and a `100 ms – 2000 ms` slider (`50 ms` increments) to adjust how long the service waits before swiping past a detected ad.
  5. The **Keywords** card lists the three read-only built-in keywords (`Sponsored`, `Sponsorlu`, `Paid partnership`) without delete buttons, followed by any user-added custom keywords (each with a remove action), a text field with an `"Add Keyword"` action, and an inline error message when invalid input is submitted.
- **States**:
  - `S-1` **Permission Required (Default Onboarding State)**: Service permission disabled; warning card with `"Enable Accessibility Service"` button visible; `Master Toggle` `ON`; `Skip Delay` at `"400 ms"`; built-in keywords visible; `Skipped Reels` at `0`.
  - `S-2` **Service Active & Master Toggle ON (Active State)**: Service permission enabled (`"Service Active"`); `Master Toggle` `ON`; non-zero `Skipped Reels` count (e.g., `12`); adjusted `Skip Delay` (e.g., `"600 ms"`); built-in keywords plus a removable custom keyword (e.g., `"Reklam"`).
  - `S-3` **Service Active & Master Toggle OFF (Paused State)**: Service permission enabled; `Master Toggle` `OFF` showing `"Skipping Paused"` status while preserving the user's `Skip Delay`, custom keywords, and `Skipped Reels` count.
  - `S-4` **Invalid Keyword Input (Validation Error State)**: User submits an invalid custom keyword (blank, `< 2` chars, `> 40` chars, punctuation-only, or Turkish/ASCII case-insensitive duplicate such as `"sponsorlu"`); inline validation error message is displayed and the keyword list remains unchanged.
- **Design reference**: none — the flow above is the design.
- **Accessibility**: Interactive controls (Master Toggle switch, Enable Accessibility Service button, Skip Delay slider, Add Keyword input/button, and custom keyword remove actions) provide explicit content descriptions or semantic labels and follow Material 3 contrast guidelines.

## Constraints & out of scope
- Sensitive area: Accessibility Service (`docs/domain.md`).
- Zero external data flow and zero content logging (`INV-1`): Only user-configured settings and the integer skip counter are persisted locally; screen content observed from Instagram is never logged, cached, or stored.
- Architectural boundaries (`docs/architecture.md`): `domain` contains zero `android.*` imports; `ui` never invokes `AccessibilityService` methods directly or dispatches gestures (communication occurs strictly via shared local state in `data`); zero networking libraries in any layer.
- Backlog closures: Closes `B-1` (custom adaptive/vector launcher icon in `AndroidManifest.xml`), `B-4` (`MaterialTheme`, `stringResource(R.string.app_name)`, backtick test naming, Robolectric note in `docs/testing.md`, and `<intent-filter>` XML assertion), and `B-6` (headless JVM screenshot capture for `S-1`…`S-4`).
- Split: proposed `0003-settings-persistence` + `0004-dashboard-ui`; kept as one — mortvivant, single-screen dashboard and local preferences form one cohesive user capability within 8 acceptance criteria.
- Out of scope:
  - Cloud backup/sync, remote configuration, or user accounts.
  - Non-Instagram platforms (TikTok, YouTube Shorts, Facebook Reels).
  - Reset/export analytics history or per-day charts (only the lifetime integer skip counter `BR-7` is in scope).
  - `B-2` (Ktlint/Detekt & `allWarningsAsErrors`) and `B-5` (CI workflow token/cache hardening), which remain in `specs/backlog.md`.

## Acceptance criteria
- [ ] AC-1 — Given no prior saved settings (or corrupted local storage), the system initializes with default values (`Master Toggle = ON`, `Skip Delay = 400 ms`, built-in keywords `Sponsored`, `Sponsorlu`, `Paid partnership`, `Skipped Reels = 0`), persists user updates across restarts, clamps `Skip Delay` values outside `100..2000 ms` to the nearest bound (`100 ms` or `2000 ms`), and increments the persisted lifetime skip counter by `1` per completed skip (`BR-2`, `BR-5`, `BR-6`, `BR-7`).
- [ ] AC-2 — Given custom keyword operations, the system trims surrounding whitespace, adds valid custom keywords (`2..40` characters containing at least one letter or digit) in union with the built-in keywords, rejects blank, punctuation-only, `< 2` character, `> 40` character, or Turkish/ASCII case-insensitive duplicate keywords (`"sponsorlu"`, `"SPONSORED"`, or an existing custom keyword) without mutating the saved set, and allows removing only user-added custom keywords while keeping built-in keywords undeletable (`BR-6`).
- [ ] AC-3 — Given the `AccessibilityService` is running, it observes the persisted configuration (`Master Toggle`, `Skip Delay`, and unioned keyword set) dynamically so toggling `Master Toggle` to `OFF` immediately suppresses detection/swiping, updating `Skip Delay` or custom keywords changes detection/schedule timing without restarting the app, and only a completed upward swipe (`onCompleted`) increments the lifetime skip counter by `1` (cancelled, aborted, or failed swipes do not increment the counter) (`BR-2`, `BR-5`, `BR-6`, `BR-7`).
- [ ] AC-4 — Given state `S-1` (`Permission Required`), when the accessibility service is not enabled in Android system settings, the dashboard renders the permission-required warning card with the `"Enable Accessibility Service"` action (which dispatches the `android.settings.ACCESSIBILITY_SETTINGS` intent when tapped), refreshes the permission state on activity resume, and displays the default controls (`Master Toggle = ON`, `"400 ms"`, built-in keywords, `Skipped Reels = 0`), verified by component test and headless screenshot `S-1` (`B-6`).
- [ ] AC-5 — Given states `S-2` (`Service Active — Master Toggle ON`) and `S-3` (`Paused — Master Toggle OFF`), when the accessibility service is enabled, the dashboard renders `"Service Active"`, reflects live `Skipped Reels` count, `Skip Delay` slider changes (`50 ms` steps in `100..2000 ms`), and custom keyword additions/removals (`S-2`), and transitions to the paused indicator when `Master Toggle` is switched `OFF` while preserving all configured values (`S-3`), verified by component tests and headless screenshots `S-2` and `S-3` (`B-6`).
- [ ] AC-6 — Given state `S-4` (`Invalid Keyword Input`), when the user submits a blank, too-short (`< 2` chars), too-long (`> 40` chars), punctuation-only, or duplicate keyword on the dashboard, an inline validation error message is displayed, the keyword list is unchanged, and a subsequent valid keyword submission clears the error and adds the keyword, verified by component test and headless screenshot `S-4` (`B-6`).
- [ ] AC-7 — `com.adreels.skipper.domain` contains zero `android.*` imports, `com.adreels.skipper.ui` never references `AccessibilityService` or dispatches gestures directly, no package imports networking APIs (`INV-1`), and `AndroidManifest.xml` declares a custom launcher icon (`B-1`) and verified `MAIN`/`LAUNCHER` `<intent-filter>` with Material 3 theming and localized string resources (`B-4`).
- [ ] AC-8 — `./scripts/check` and `./scripts/check --fast` pass with exit code `0`.

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
