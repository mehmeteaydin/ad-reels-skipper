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

- **B-2** — Configure `kotlinc` strict warnings (`allWarningsAsErrors`) and Ktlint/Detekt static analysis in Gradle build and `scripts/check.conf` — Source: spec 0001, review round 2 (docs/conventions.md:46-49) — Severity: Low — Recommendation: Add `allWarningsAsErrors` and Ktlint/Detekt tasks after walking skeleton — Deferred by: mortvivant, 2026-11-15, outside walking skeleton scope after aligning `docs/conventions.md`
- **B-5** — Harden `.github/workflows/check.yml` with explicit `permissions: contents: read`, scoped `chmod`, and `setup-gradle` caching in `doctor` job — Source: spec 0001, review round 2 (.github/workflows/check.yml:1-52) — Severity: Info — Recommendation: Add explicit read-only token permissions and Gradle wrapper caching to CI `doctor` job — Deferred by: mortvivant, 2026-11-15, CI workflow operates cleanly as-is
