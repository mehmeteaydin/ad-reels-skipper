# Spec 0005 — Fast and Smooth Sponsored Reel Skipping

- Status: In progress
- Mode: strict
- Plan: `specs/plans/0005-plan.md`
- Self-critique: done

## Intent
Instagram Reels users need sponsored Reels to be skipped with near-instant responsiveness and without causing UI stutter or frame drops inside Instagram during video playback and scrolling. Currently, traversing up to hundreds of accessibility nodes over Binder IPC on every window event, repeating full tree extractions while a skip is pending, and using conservative timing defaults (`400 ms` delay, `200 ms` center-screen swipe, `1200 ms` cooldown) can stall Instagram's UI thread and make ad skipping feel sluggish. Success means accessibility tree traversal stops immediately once Reels context and a sponsored indicator are confirmed, redundant intermediate window scans during a pending skip are eliminated, and faster delay (`50..2000 ms`, default `150 ms`), flick gesture (`100 ms`, off-center safe path), and cooldown (`700 ms`) parameters skip sponsored Reels smoothly and rapidly while verifying active Reels context and sponsored presence via early-exit inspection before dispatching a swipe (`INV-2`) and preserving zero external data flow (`INV-1`). Adding new dashboard screens, non-Instagram app support, or network capabilities remains out of scope.

## Requirements
- **Fast skip delay range and default (`BR-5`)**: The user-configurable skip delay supports `50 ms` to `2000 ms` in `50 ms` increments, with a default of `150 ms` for new installations or unset preferences. Values below `50 ms` clamp to `50 ms`, values above `2000 ms` clamp to `2000 ms`, and values between steps snap to the nearest `50 ms` increment.
- **Responsive cooldown and event throttle windows (`BR-4`)**: After a skip gesture completes, subsequent detections are suppressed for a `700 ms` cooldown window (`DEFAULT_COOLDOWN_MS = 700 ms`). Incoming window events outside a pending skip are throttled to at most one tree inspection every `120 ms` (`DEFAULT_THROTTLE_INTERVAL_MS = 120 ms`).
- **Bounded early-exit window inspection (`BR-1`, `BR-3`, `INV-2`)**: Window inspection visits at most `160` visible nodes (`DEFAULT_MAX_VISIBLE_NODES = 160`) and at most `400` total nodes (`MAX_TOTAL_VISITED = 400`) per inspection. Traversal terminates immediately as soon as both active Reels viewer context and at least one active sponsored keyword match are confirmed in the current window, recycling all visited and remaining queued non-root nodes on Android API < 34.
- **Pending-skip IPC suppression and early-exit pre-dispatch verification (`INV-2`)**:
  - Once a sponsored Reel is detected and a skip timer is scheduled (`isSkipPending = true`), intermediate accessibility events from `com.instagram.android` are ignored without extracting the accessibility node tree while the timer is pending.
  - If an event arrives from a non-target package (`packageName != "com.instagram.android"`) or the Master Toggle is turned OFF while a skip is pending, the pending timer is cancelled immediately.
  - When the scheduled skip timer fires across any configured delay (`50..2000 ms`), the service verifies that the active window root is non-null with `packageName == "com.instagram.android"` before performing a bounded early-exit window inspection to confirm Reels context and an active sponsored indicator remain present, aborting cleanly if either is absent or if the foreground package left Instagram.
- **High-velocity off-center upward flick gesture (`INV-2`)**: The programmatic upward swipe executes over `100 ms` along a vertical path at `65%` of screen width (`X = 65%`) from `78%` to `18%` of screen height (`Y = 78% -> 18%`), avoiding center-bottom ad CTA overlays and right-edge action buttons.
- **Dashboard skip delay slider alignment**: The Compose dashboard (`HomeScreen`) slider operates over `50..2000 ms` (`50 ms` steps, default `150 ms` display) while preserving all existing states (`S-1`..`S-4`), lifetime skip counter behavior (`BR-7`), and custom keyword validation (`BR-6`).

## User experience
- **Flow**:
  1. User opens `Ad Reels Skipper` and views the single-screen Material 3 dashboard.
  2. On a fresh install, the **Skip Delay** card displays `150 ms` and its slider spans `50 ms` to `2000 ms` in `50 ms` increments (`38` intermediate steps).
  3. While watching Instagram Reels, when a sponsored Reel appears, the service skips it after the configured delay (`50..2000 ms`, default `150 ms`) using a fast `100 ms` upward flick without stalling Instagram playback.
- **States**:
  - `S-1 (Permission Required)`: Accessibility service is disabled in Android system settings; warning card and `"Enable in Accessibility Settings"` CTA are displayed alongside the `50..2000 ms` (default `150 ms`) delay slider.
  - `S-2 (Service Active / Skipping Enabled)`: Accessibility service is enabled and Master Toggle is ON; active status card, lifetime skipped count, `50..2000 ms` delay slider, and keyword chips are displayed.
  - `S-3 (Service Active / Skipping Paused)`: Accessibility service is enabled and Master Toggle is OFF; paused status card is displayed and incoming events are ignored (`BR-2`).
  - `S-4 (Invalid Keyword Input)`: User submits an invalid or duplicate custom keyword; inline validation error is shown without mutating persisted keywords.
- **Design reference**: none — the flow above is the design.
- **Accessibility**: All interactive controls preserve explicit content descriptions, `testTag` identifiers, and minimum `48.dp` touch targets.

## Constraints & out of scope
- Sensitive area: `Accessibility Service` (`docs/domain.md`) — Zero `android.permission.INTERNET`, package filtering strictly confined to `com.instagram.android`, and zero logging or persistence of inspected screen node text (`INV-1`, `INV-2`).
- Supersedes: `0002/AC-4`, `0002/AC-5`, `0003/AC-1`, `0003/AC-5` (updates default/minimum skip delay from `400 ms` / `100 ms` to `150 ms` / `50 ms`, cooldown from `1200 ms` to `700 ms`, throttle from `150 ms` to `120 ms`, node caps from `500` / `2000` to `160` / `400` with early exit, and swipe geometry from `200 ms` / `X = 50%` / `Y = 75% -> 20%` to `100 ms` / `X = 65%` / `Y = 78% -> 18%`). Shipped specs in `specs/done/` remain untouched (`AGENTS.md` Rule 7), and `docs/domain.md` is updated to match.
- Out of scope:
  - Support for applications other than `com.instagram.android`.
  - User-facing dashboard controls for swipe coordinates, gesture duration, cooldown, or node limits.
  - Network access, analytics, or external dependencies.

## Acceptance criteria
- [ ] AC-1 — Given `SkipperConfig` and `DataStoreSkipperSettingsRepository`, the default configuration uses `skipDelayMs = 150`, `MIN_SKIP_DELAY_MS = 50`, `MAX_SKIP_DELAY_MS = 2000`, `SKIP_DELAY_STEP_MS = 50`, `cooldownMs = 700`, `throttleIntervalMs = 120`, and `maxVisibleNodes = 160`, clamping out-of-range delay values to `50..2000 ms` and snapping non-step values to the nearest `50 ms` increment (`BR-4`, `BR-5`).
- [ ] AC-2 — Given an `AccessibilityNodeInfo` tree in `com.instagram.android`, `AccessibilityTreeExtractor` visits at most `160` visible nodes and `400` total nodes, stops traversal immediately via early exit as soon as both Reels context and an active sponsored keyword match are found, and recycles all visited and remaining queued non-root nodes on Android API < 34 (`BR-1`, `BR-3`).
- [ ] AC-3 — Given a newly confirmed sponsored Reel that schedules a delayed skip (`isSkipPending = true`), subsequent `com.instagram.android` accessibility events while the skip is pending do not trigger accessibility tree extraction (`shouldInspectSnapshot` returns `false`), whereas a non-target package event or Master Toggle OFF cancels the pending skip immediately; when the scheduled timer fires for any delay (`50..2000 ms`), the service verifies that the active root window is non-null with `packageName == "com.instagram.android"` before performing a bounded early-exit window inspection, and aborts without swiping if the foreground app changed, Reels context disappeared (e.g., Comments, Profile, or Home feed), or the sponsored indicator is no longer present (`INV-2`).
- [ ] AC-4 — Given positive display dimensions `(W, H)`, `SwipeGestureDispatcher` computes `centerX = 0.65f * W`, `startY = 0.78f * H`, `endY = 0.18f * H`, and `durationMs = 100L`, dispatches the upward gesture, opens the `700 ms` cooldown window and increments the persisted lifetime skip counter by `1` only in `onCompleted`, and clears pending/in-flight state without incrementing the counter on invalid bounds, cancellation, or dispatch failure (`BR-4`, `BR-7`, `INV-2`).
- [ ] AC-5 — Given the Compose dashboard (`HomeScreen`) across states `S-1`..`S-4`, the Skip Delay slider displays `150 ms` by default and adjusts across `50..2000 ms` in `50 ms` steps (`steps = 38`), verified by Robolectric Compose UI tests and headless screenshots for `S-1`, `S-2`, `S-3`, and `S-4`.
- [ ] AC-6 — Given the repository documentation and verification suite, `docs/domain.md` reflects the updated `Skip Delay` (`50 ms – 2000 ms`, default `150 ms`) and `Cooldown Window` (`700 ms`), all architecture/privacy boundaries (`INV-1`, `INV-2`) hold, and both `./scripts/check` and `./scripts/check --fast` exit `0`.

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
