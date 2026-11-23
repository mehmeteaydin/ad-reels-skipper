# Spec 0006 — Reliable Reels Tree Traversal and Smooth Swipe Tuning (mini)

- Status: Shipped
- Mode: strict
- Plan: `specs/plans/0006-plan.md`
- Self-critique: done
- Source: `device-report-0005-no-skip-regression`
- Design: none — no UI layout or visual change.
- Supersedes: `0005/AC-1`, `0005/AC-2`, `0005/AC-4`

## Intent
On real Android devices, Instagram Reels renders hundreds of visible unlabeled layout container nodes (`FrameLayout`, `LinearLayout`, `ViewGroup`, Litho `ComponentHost`) and off-screen `ViewPager2` siblings before the visible `"Sponsored"`/`"Sponsorlu"` and `"Reels"` nodes appear. Because Spec 0005 counted unlabeled visible containers against `maxVisibleNodes = 160` and capped traversal at `MAX_TOTAL_VISITED = 400`, tree extraction terminated before reaching sponsored labels and recorded zero skips on-device. Success means unlabeled containers never consume the visible-node budget, traversal supports real Instagram hierarchy depths (`MAX_TOTAL_VISITED = 2000`) while keeping immediate early exit on match, organic-scroll throttling calms to `180 ms` to reduce background Binder IPC stutter, and the upward swipe (`X = 55%`, `Y = 72% -> 20%`, `150 ms`) starts cleanly above bottom ad CTA overlays while remaining fast and reliable across OEMs.

## Changed behavior
- [x] CB-1 — Given an `AccessibilityNodeInfo` tree containing 200+ visible unlabeled layout containers (`text` and `contentDescription` both null or blank) and/or 400+ hidden off-screen nodes preceding visible `"Reels"` and active sponsored keyword nodes, `AccessibilityTreeExtractor` skips allocating and counting unlabeled visible containers against `maxVisibleNodes` (`160` labeled visible nodes), traverses up to `MAX_TOTAL_VISITED = 2000` nodes, collects both `"Reels"` and the sponsored keyword node, stops immediately via early exit once both match (with zero child queries on the matched node or 2000th visited node), and recycles all visited and remaining queued non-root nodes on Android API < 34 (`BR-1`, `BR-3`).
- [x] CB-2 — Given `SkipperConfig`, `DEFAULT_THROTTLE_INTERVAL_MS` is `180L` (reducing background Binder IPC frequency during continuous organic Reels playback) while keeping `DEFAULT_SKIP_DELAY_MS = 150L`, `MIN_SKIP_DELAY_MS = 50L`, `MAX_SKIP_DELAY_MS = 2000L`, `SKIP_DELAY_STEP_MS = 50L`, `DEFAULT_COOLDOWN_MS = 700L`, and `DEFAULT_MAX_VISIBLE_NODES = 160` (`BR-4`, `BR-5`).
- [x] CB-3 — Given positive display dimensions `(W, H)`, `SwipeGestureDispatcher` computes `centerX = 0.55f * W`, `startY = 0.72f * H`, `endY = 0.20f * H`, and `durationMs = 150L`, starting cleanly in the open video area above bottom ad CTA bars while avoiding right-edge controls and remaining reliable across OEM gesture recognizers (`INV-2`).

## Preserved behavior
- [x] PB-1 — Pending-skip IPC suppression (`shouldInspectSnapshot` returns `false` while `isSkipPending` or `isGestureInFlight`), immediate cancellation on non-target package or Master Toggle OFF, exception-safe pre-extraction `safePackageName(freshRoot) == TARGET_PACKAGE` guard, unified early-exit pre-dispatch verification across `50..2000 ms`, `700 ms` cooldown, and `onCompleted`-only lifetime skip counter increment remain intact (`0005/AC-3`, `BR-2`, `BR-4`, `BR-7`, `INV-2`).
- [x] PB-2 — Compose dashboard (`HomeScreen`, `DashboardViewModel`, `strings.xml`) preserves `150 ms` default display, `50..2000 ms` slider (`38` steps), states `S-1`..`S-4`, and custom keyword validation (`0005/AC-5`, `BR-5`, `BR-6`).
- [x] PB-3 — Privacy and architecture boundaries (`INV-1`, `INV-2`), `android:packageNames="com.instagram.android"`, zero `INTERNET` permission, zero node-text logging/persistence, aligned `docs/conventions.md` (`150 ms` stroke duration), and `./scripts/check` + `./scripts/check --fast` green remain intact (`0005/AC-6`).

## Out of scope
- Sensitive area: `Accessibility Service` (`docs/domain.md`) — Zero `android.permission.INTERNET`, strict `com.instagram.android` confinement, and zero logging/persistence of screen text remain unchanged.
- Dashboard UI layout changes, new settings controls, non-Instagram apps, or editing shipped specs under `specs/done/` (`AGENTS.md` Rule 7).

## Definition of Done
- [x] `scripts/check` green
- [x] Independent review done; real findings fixed, noise rejected with written rationale, deferred ones in `specs/backlog.md`
- [x] Criterion ↔ evidence table complete for CB-* **and** PB-*
- [x] Spec moved to `specs/done/` (immutable there)
