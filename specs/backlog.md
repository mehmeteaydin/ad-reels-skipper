# Backlog — deferred findings

Findings a human triaged as **defer**: real, but fixed later by decision (`workflows/feature-development.md`,
step 7). Nothing lands here without that decision; noise is rejected in the triage record, not parked here.

- A criterion moved out at the ship gate (R-08, `→ moved to B-<n>`) lands here too, by the
  human's ship decision; the entry names the spec and criterion.
- One entry per finding, newest last. Delete the entry in the commit of the work that closes it.
- An entry that grows into new behavior becomes a spec (`specs/active/`), not a fix.

<!-- Format (labels stay English):
- **B-<n>** — <finding, one line> — Source: spec <NNNN>, review round <n> (<file:line>) — Severity: <…>
  — Recommendation: <the reviewer's action> — Deferred by: <name>, <YYYY-MM-DD>, <why>
  A decision deferred at bootstrap (its REPORT) uses Source: bootstrap <YYYY-MM-DD>.
-->

- **B-1** — AndroidManifest.xml uses fallback framework icon (@android:drawable/sym_def_app_icon) instead of a custom app launcher icon — Source: spec 0001, review round 1 (app/src/main/AndroidManifest.xml:6-8) — Severity: Info — Recommendation: Add custom adaptive launcher icon during UI/dashboard spec — Deferred by: mortvivant, 2026-11-13, acceptable for walking skeleton
- **B-2** — Configure `kotlinc` strict warnings (`allWarningsAsErrors`) and Ktlint/Detekt static analysis in Gradle build and `scripts/check.conf` — Source: spec 0001, review round 2 (docs/conventions.md:46-49) — Severity: Low — Recommendation: Add `allWarningsAsErrors` and Ktlint/Detekt tasks after walking skeleton — Deferred by: mortvivant, 2026-11-15, outside walking skeleton scope after aligning `docs/conventions.md`
- **B-3** — Normalize `event.packageName?.toString()` before string comparison in `SkipperAccessibilityService.onAccessibilityEvent` — Source: spec 0001, review round 2 (app/src/main/kotlin/com/adreels/skipper/service/SkipperAccessibilityService.kt:10) — Severity: Info — Recommendation: Compare `event.packageName?.toString()` when implementing event matching — Deferred by: mortvivant, 2026-11-15, Spec 0002 detection work will expand this service
- **B-4** — Polish UI/test alignment (explicit `MaterialTheme`, `stringResource(R.string.app_name)`, backtick method name in `HomeScreenTest`, Robolectric note in `docs/testing.md`, `<intent-filter>` XML assertion) — Source: spec 0001, review round 2 (app/src/test/kotlin/com/adreels/skipper/ui/HomeScreenTest.kt:21) — Severity: Info — Recommendation: Apply theme/string resource polish and expanded manifest filter checks in subsequent specs — Deferred by: mortvivant, 2026-11-15, minor hygiene beyond walking skeleton AC-1..AC-5
- **B-5** — Harden `.github/workflows/check.yml` with explicit `permissions: contents: read`, scoped `chmod`, and `setup-gradle` caching in `doctor` job — Source: spec 0001, review round 2 (.github/workflows/check.yml:1-52) — Severity: Info — Recommendation: Add explicit read-only token permissions and Gradle wrapper caching to CI `doctor` job — Deferred by: mortvivant, 2026-11-15, CI workflow operates cleanly as-is
- **B-6** — Configure Android JVM screenshot testing (Paparazzi or Roborazzi) so Compose UI specs capture `S-1`…`S-n` visual evidence headlessly without requiring an emulator/device — Source: spec 0001, review round 2 (docs/testing.md:64-85) — Severity: Info — Recommendation: Add Roborazzi or Paparazzi screenshot capture before UI-heavy specs (`0003`) — Deferred by: mortvivant, 2026-11-15, AC-3 headless component test verified by HomeScreenTest with real-device visual fallback acknowledged at ship
