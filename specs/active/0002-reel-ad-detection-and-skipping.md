# Spec 0002 — Reel Ad Detection and Skipping

- Status: Approved
- Mode: strict
- Plan: `specs/plans/0002-plan.md`
- Self-critique: done

## Intent
Enable `ad-reels-skipper` to automatically detect sponsored Reels inside Instagram and advance past them with a programmatic upward swipe. Instagram users want uninterrupted Reels browsing without manually swiping away advertisements, while keeping all screen inspection strictly on-device with zero network access or content logging. Success means sponsored Reels in the foreground Instagram Reels viewer are accurately identified via case-insensitive keyword rules (`Sponsored`, `Sponsorlu`, `Paid partnership`), skipped after the default 400 ms delay, and protected by a 1200 ms cooldown window so organic Reels and non-Reels screens are never accidentally skipped. User-configurable settings, custom keyword management, persistent DataStore state, and dashboard UI controls remain out of scope for this spec and are deferred to Spec 0003.

## Requirements
- **Target package confinement (`BR-1`, closes `B-3`)**: The service processes accessibility events exclusively when the normalized string value of the event package name (`event.packageName?.toString()`) equals `"com.instagram.android"`. Null events or events from any other package are ignored immediately.
- **Master Toggle gate (`BR-2`)**: Detection evaluates an active `Master Toggle` state (defaulting to `ON`). When `OFF`, incoming events are discarded immediately with zero gesture dispatch.
- **Visible node filtering and traversal bound (`SC-3`, `SC-4`)**: Only nodes currently visible to the user (`isVisibleToUser == true`) are inspected, up to a defensive maximum of `500` visible nodes per snapshot, short-circuiting as soon as both Reels viewer context and a `Sponsored Label` are confirmed. Off-screen preloaded nodes (`isVisibleToUser == false`) are ignored.
- **Reels viewer confinement (`INV-2`, `SC-2`)**: Detection and gesture dispatch occur only when the current window confirms active Reels viewer context — either a selected Reels navigation tab (`isSelected == true` on a `"Reels"` node) or visible Reels viewer semantics (such as a `"Reels"` viewer header or `"Reel by …"` content description). An unselected bottom-navigation `"Reels"` tab (`isSelected == false`) on the Home Feed, Stories, or profile screens must not satisfy Reels context and must never trigger a swipe.
- **Locale-safe keyword matching (`BR-3`, `BR-6`, `SC-1`)**: A `Sponsored Label` is matched when any visible node text or content description contains an active keyword (`"Sponsored"`, `"Sponsorlu"`, `"Paid partnership"`) using deterministic, locale-independent case folding (normalizing Turkish dotted/dotless `İ`/`I`/`ı`/`i` alongside `Locale.ROOT` lowercase) and whole-word or phrase boundary matching that ignores surrounding whitespace and punctuation (e.g., `"• Sponsored •"`, `"SPONSORLU"`), while rejecting substring collisions inside larger words (e.g., `"unsponsored"`).
- **Event throttling, `Skip Delay`, and pending skip cancellation (`BR-5`)**: Rapid accessibility events are throttled (default `150 ms` minimum evaluation interval). When a `Sponsored Reel` is confirmed, a single upward `Swipe Gesture` is scheduled after the default `Skip Delay` (`400 ms`). If a subsequent evaluation before the `400 ms` delay elapses indicates that the `Sponsored Label` or Reels viewer context is no longer present, the pending gesture is cancelled and no duplicate gesture is queued.
- **Cooldown protection (`BR-4`)**: After a `Swipe Gesture` is dispatched, subsequent skip triggers are suppressed for the duration of the `Cooldown Window` (default `1200 ms`).
- **Upward swipe geometry and fail-safe execution (`INV-1`, `INV-2`)**: The `Swipe Gesture` swipes vertically upward along the horizontal center of the screen (`X = 50%` of width, from `Y = 75%` height to `Y = 20%` height over `200 ms`). If the active window root is null, screen dimensions are non-positive, node traversal throws an exception, or the OS rejects/cancels the gesture, the service recovers silently without crashing and without logging any inspected node text or user content.

## User experience
none — no user interface (background accessibility service behavior; user-facing dashboard and settings UI are deferred to Spec 0003).

## Constraints & out of scope
- Sensitive area: Accessibility Service (`docs/domain.md`).
- Zero external data flow and zero content logging (`INV-1`): Never log, cache, or transmit inspected node text, content descriptions, usernames, or captions.
- Domain layer purity (`docs/architecture.md`): The `domain` package must contain zero `android.*` imports.
- Volatile view ID prohibition (`docs/conventions.md`): Detection must rely strictly on visible node text, content descriptions, and semantic state — never on obfuscated Instagram resource IDs (`R.id.*`).
- Backlog closure: Closes `B-3` (`specs/backlog.md`).
- Out of scope:
  - Jetpack DataStore persistence, custom user-added keywords, adjustable delay sliders, persistent lifetime skip counter storage, and Compose dashboard/settings screens (Spec 0003).
  - Non-Instagram platforms (TikTok, YouTube Shorts, Facebook Reels).

## Acceptance criteria
- [ ] AC-1 — Given an accessibility event whose package name is null or not `"com.instagram.android"` (evaluated via `.toString()`, closing `B-3`), or when the `Master Toggle` state is `OFF`, the system ignores the event and dispatches no gesture.
- [ ] AC-2 — Given visible (`isVisibleToUser == true`) node texts or content descriptions within an active Reels viewer window, the `Ad Detector` returns a skip decision when any visible node matches `"Sponsored"`, `"Sponsorlu"`, or `"Paid partnership"` across word/phrase boundaries with Turkish/ASCII case-insensitive normalization (including uppercase `"SPONSORLU"` and surrounding punctuation such as `"• Sponsored •"`), and returns an ignore decision for invisible (`isVisibleToUser == false`) nodes, partial-word substrings (`"unsponsored"`), or blank inputs.
- [ ] AC-3 — Given a window containing a sponsored keyword (`"Sponsored"` / `"Sponsorlu"`) where the only `"Reels"` node is an unselected navigation tab (`isSelected == false`) or where no Reels viewer context indicator (`isSelected == true` `"Reels"` tab, `"Reels"` viewer header, or `"Reel by …"` description) is present, the system returns an ignore decision and dispatches no gesture (`INV-2`).
- [ ] AC-4 — Given a `Sponsored Reel` is detected, the system throttles rapid event evaluations (`150 ms`), waits the `400 ms` `Skip Delay` before dispatching at most one `Swipe Gesture`, and cancels the pending gesture without swiping if a re-evaluation during the `400 ms` window shows the sponsored label or Reels context is no longer present.
- [ ] AC-5 — Given a `Swipe Gesture` has been dispatched, any subsequent `Sponsored Reel` detections within the `1200 ms` `Cooldown Window` are suppressed, and detection resumes once `1200 ms` have elapsed.
- [ ] AC-6 — Given a confirmed skip trigger and valid screen dimensions, the service dispatches an upward vertical swipe (`X = 50%` width, `Y = 75%` to `20%` height, `200 ms` duration), bounds node traversal to at most `500` visible nodes per snapshot, and recovers silently without crashing when the root window is null, bounds are invalid, or gesture dispatch returns false/cancelled.
- [ ] AC-7 — All files under `com.adreels.skipper.domain` contain zero `android.*` imports, and neither `domain` nor `service` logs or persists inspected screen text (`INV-1`).
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
