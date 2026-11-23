# Spec 0007 — Weekly Daily Skip History and Archive View

- Status: Shipped
- Mode: strict
- Plan: `specs/plans/0007-plan.md`
- Self-critique: done

## Intent
Users of `Ad Reels Skipper` want to see how many sponsored Reels were automatically skipped on each day of the current week, have the weekly record automatically start fresh every Monday, and still be able to inspect previous weeks on demand. Today the dashboard shows only a single cumulative lifetime skip counter (`BR-7`), giving no visibility into daily or weekly ad-skipping trends. Success means every completed skip increments both the existing lifetime counter and the local count for the current local calendar date, the dashboard displays an at-a-glance Monday–Sunday weekly breakdown for the current week, transitioning into a new Monday–Sunday calendar week automatically resets the active weekly view to zero while archiving previous weeks locally (retaining up to the 12 most recent past weeks with recorded skips), and users can open a history view to inspect any archived week's daily breakdown. Cloud sync, CSV export, manual editing/clearing of individual days, and storing any ad text or creator metadata remain out of scope (`INV-1`).

## Requirements
- **Daily skip recording alongside lifetime counter (`BR-7`, `INV-1`)**: Whenever an upward skip gesture completes (`onCompleted`), the app atomically increments the existing lifetime skip counter by `1` and increments the local skip count for the device's current local calendar date (`YYYY-MM-DD`) by `1`. Cancelled, failed, or aborted skips increment neither counter. No ad text, creator name, or screen content is ever recorded alongside the count.
- **Monday–Sunday weekly grouping and automatic weekly rollover**: Weeks run Monday through Sunday in the device's local calendar. The active week always represents `[currentWeekMonday .. currentWeekSunday]` containing the device's current local date. As soon as the local date enters a new Monday–Sunday week (when a skip completes or when the user opens/resumes the dashboard), the active weekly summary automatically starts at `0` for all seven days (`Mon`–`Sun`) while strictly past weeks (`weekStart < currentWeekMonday`) with at least `1` skip (`weeklyTotal > 0`) become archived past weeks. Entries with dates strictly after `currentWeekSunday` (e.g., after a manual device clock rollback) are safely excluded from current and archived totals.
- **Bounded local archive retention**: Up to the 12 most recent archived past weeks (weeks strictly before the active week that recorded at least `1` skip) are retained locally on-device. Older archived weeks beyond the 12-week limit are pruned automatically when new skips are persisted. Malformed, negative, or corrupted persisted history entries are ignored fail-safely without crashing or resetting unrelated user preferences.
- **Dashboard current-week summary card**: The main Compose dashboard displays a **Weekly Activity** card below the Metrics / Master Toggle card showing:
  - The current week's Monday–Sunday date range (`MMM d – MMM d`, including year labels when spanning a year boundary) and total skipped Reels for the current week.
  - All seven days (`Mon` through `Sun`) with each day's skip count, a proportional bar indicator, and visual highlight on today.
  - A **History** action button to open the past-weeks archive view.
- **Archived past-weeks history view**: Tapping **History** opens a dialog/view (**Past Weeks**) listing strictly past archived weeks (`totalSkips > 0`) from newest to oldest (never duplicating the active week or empty `0`-skip gap weeks), each showing its Monday–Sunday date range and weekly total. When archived weeks exist, the newest archived week is pre-selected to display its `Mon`–`Sun` daily breakdown, and selecting any archived week switches the displayed daily breakdown to that week. When no archived past weeks exist, `"No past weeks recorded yet."` is displayed.
- **Existing behavior preserved**: Lifetime skip counter value is preserved across updates, all existing dashboard states (`S-1`..`S-4`), UI language consistency (English labels matching existing controls), accessibility service detection/skip behavior, and strict privacy invariants (`INV-1`, `INV-2`) remain unchanged.

## User experience
- **Flow**:
  1. User opens `Ad Reels Skipper` and sees the existing dashboard cards plus the new **Weekly Activity** card showing the current Monday–Sunday week (`Mon`–`Sun` counts + weekly total) with today highlighted.
  2. As sponsored Reels are automatically skipped in Instagram, today's bar/count and the weekly total increase alongside the lifetime counter.
  3. On a new Monday, opening or resuming the app displays a fresh `0`-skip current week while moving the previous week (if `totalSkips > 0`) into the archive.
  4. User taps **History** on the **Weekly Activity** card to inspect past weeks (newest first, with the newest archived week pre-selected), taps any archived week to switch the `Mon`–`Sun` daily breakdown, and closes the **Past Weeks** dialog to return to the dashboard.
- **States**:
  - `S-1`..`S-4` (existing dashboard permission, active, paused, and invalid keyword states — preserved).
  - `S-5 (Current Week Empty)`: Current week has `0` skips across `Mon`–`Sun`; weekly total shows `0`, all seven daily counts show `0`, and today is highlighted.
  - `S-6 (Current Week With Skips)`: Current week has skips recorded on one or more days; weekly total equals the sum of `Mon`–`Sun` counts, and each day's bar scales proportionally against the week's highest daily count.
  - `S-7 (Past Weeks History Empty)`: User opens **History** when no past weeks have archived skips; `"No past weeks recorded yet."` is displayed with a close control.
  - `S-8 (Past Weeks History With Archive & Detail)`: User opens **History** when one or more past weeks exist; archived weeks appear newest-first (newest pre-selected) with date range and weekly total, and selecting another archived week updates the displayed `Mon`–`Sun` daily breakdown.
- **Design reference**: none — the flow above is the design.
- **Accessibility**: The **History** button, archive week rows, and close control provide minimum `48.dp` touch targets, explicit `testTag` identifiers, and descriptive content descriptions (including day name + skip count summaries).

## Constraints & out of scope
- Sensitive area: `Accessibility Service` (`docs/domain.md`) — Zero `android.permission.INTERNET`, strict `com.instagram.android` event confinement, and zero logging or persistence of screen node text (`INV-1`, `INV-2`). Persisted metrics store strictly local calendar dates and integer counts.
- Out of scope:
  - Cloud sync, user accounts, or exporting/sharing statistics.
  - Manual editing, resetting, or deleting individual historical days.
  - Monthly/yearly charts or per-keyword breakdown statistics.
  - Editing shipped specs under `specs/done/` (`AGENTS.md` Rule 7).

## Acceptance criteria
- [x] AC-1 — Given a completed skip gesture (`onCompleted`) on local date `D`, both the lifetime skip counter and the daily count for `D` increment by `1` in local persistence, whereas cancelled, failed, or aborted skips increment neither counter (`BR-7`, `INV-1`).
- [x] AC-2 — Given recorded daily counts spanning one or more Monday–Sunday calendar weeks and a reference current date `today`, pure domain aggregation computes: (a) the current week's exact `[currentWeekMonday .. currentWeekSunday]` `Mon`–`Sun` seven-day breakdown and weekly total (all `0` on a fresh Monday rollover), and (b) archived past weeks strictly before `currentWeekMonday` that have `weeklyTotal > 0` sorted newest-first, while safely ignoring dates strictly after `currentWeekSunday`.
- [x] AC-3 — Given daily skip records spanning more than 12 past calendar weeks with skips, local persistence prunes archived weeks older than the 12 most recent archived weeks while retaining the current week and the 12 newest archived weeks, and recovers fail-safely without crashing or losing unrelated preferences when stored history data is malformed or contains negative counts.
- [x] AC-4 — Given the Compose dashboard in states `S-5` (current week empty) and `S-6` (current week with skips), the **Weekly Activity** card displays the current Monday–Sunday date range, accurate weekly total, `Mon`–`Sun` counts with today highlighted, and refreshes its active week when the activity resumes on a new day/week, verified by Robolectric Compose UI tests and headless screenshots (`S-5`, `S-6`).
- [x] AC-5 — Given the **History** action on the **Weekly Activity** card in states `S-7` (empty archive) and `S-8` (archived weeks present), tapping **History** opens **Past Weeks** displaying `"No past weeks recorded yet."` when no archive exists, or lists archived past weeks (`totalSkips > 0`, never the active week) newest-first with the newest week pre-selected and allows switching between archived weeks to view each week's `Mon`–`Sun` daily breakdown, verified by Robolectric Compose UI tests and headless screenshots (`S-7`, `S-8`).
- [x] AC-6 — Given all existing features and project invariants, existing dashboard states (`S-1`..`S-4`), `Lifetime Skipped Reels` persistence across upgrades, `docs/domain.md` (`BR-7` update), `INV-1`/`INV-2` boundaries, and both `./scripts/check` and `./scripts/check --fast` exit `0`.

## Definition of Done
- [x] Every acceptance criterion mapped to proof (test or reproducible observation)
- [x] `scripts/check` green
- [x] Independent review done; real findings fixed, noise rejected with written rationale, deferred ones in `specs/backlog.md`
- [x] Docs / ADRs updated if behavior or architecture changed
- [x] Spec moved to `specs/done/` (it becomes immutable there)

## Scorecard (fill at ship — honest numbers make the process improvable)
| Metric | Value |
|---|---|
| Spec revisions | 1 |
| Fix rounds | 1 |
| Review findings: real / noise / deferred | 5 real / 0 noise / 0 deferred |
| Regressions introduced | 0 |
| Bugs escaped to production | 0 |
