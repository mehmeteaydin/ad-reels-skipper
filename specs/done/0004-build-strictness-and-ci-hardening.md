# Spec 0004 — Build Strictness and CI Hardening

- Status: Shipped
- Mode: strict
- Plan: `specs/plans/0004-plan.md`
- Self-critique: done

## Intent
Project maintainers and automated quality gates need compiler warnings, code-style drift, and static-analysis defects to fail fast during local and CI verification, while ensuring the GitHub Actions workflow runs with least-privilege permissions and deterministic Gradle caching (`B-2`, `B-5`). Success means every Kotlin compilation target (`main` and `test`) enforces zero warnings (`allWarningsAsErrors`), automated Kotlin style and static-analysis checks run as part of the `lint [fast]` verification step, and the CI workflow declares read-only repository permissions (`contents: read`), caches Gradle in the `doctor` job, and scopes executable permission changes strictly to executable scripts. Application runtime behavior, user interface flows, accessibility service detection logic, and library version upgrades (`GradleDependency` notices) remain deliberately out of scope.

## Requirements
- **Zero-warning Kotlin compilation (`B-2`)**: All Kotlin compilation tasks across main and test source sets treat compiler warnings as errors (`allWarningsAsErrors`). Existing deprecation warnings in test helpers are resolved cleanly so the entire project compiles with zero warnings, and any newly introduced compiler warning fails the build.
- **Automated Kotlin style and static analysis in the verification contract (`B-2`)**: Deterministic Kotlin code-style and static-analysis checks (Ktlint and Detekt) validate all project Kotlin sources (`app/src/main/kotlin` and `app/src/test/kotlin`) and are wired into the `lint [fast]` step of `scripts/check.conf` alongside Android Lint. Coding conventions documentation (`docs/conventions.md`) reflects that strict compiler warnings and Ktlint/Detekt static analysis are actively enforced rather than deferred.
- **Least-privilege CI permissions and scoped script execution (`B-5`)**: The GitHub Actions workflow (`.github/workflows/check.yml`) declares explicit top-level read-only permissions (`permissions: contents: read`) and grants execute permissions (`chmod +x`) only to executable scripts (`gradlew`, `scripts/check`, `scripts/doctor`, `scripts/residue`, `scripts/selftest`, `scripts/init`), never to configuration files (`scripts/check.conf`).
- **Deterministic Gradle setup in CI `doctor` job (`B-5`)**: The `doctor` job in `.github/workflows/check.yml` configures Gradle via `gradle/actions/setup-gradle@v4` prior to running `./scripts/doctor --strict` and `./scripts/selftest`, matching the `check` job's caching and wrapper setup.
- **Process residue hygiene on `scripts/check.conf` change**: Because `scripts/check.conf` is updated, all verification steps continue to run without leaving background Gradle daemons or orphan child processes (`./scripts/residue` and `./scripts/residue --fast` clean).

## User experience
none — no user interface.

## Constraints & out of scope
- Zero external runtime data flow (`INV-1`): Build-time static analysis plugins apply strictly to Gradle build/test execution and must not add any runtime dependencies, permissions, or network libraries to the packaged Android APK.
- Backlog closures: Closes `B-2` (`kotlinc` `allWarningsAsErrors` and Ktlint/Detekt static analysis in Gradle and `scripts/check.conf`) and `B-5` (`.github/workflows/check.yml` `permissions: contents: read`, scoped `chmod`, and `setup-gradle` in `doctor` job), leaving `specs/backlog.md` with zero open deferred findings.
- Split: proposed `0004-build-strictness` (`B-2`) + `0005-ci-hardening` (`B-5`); kept as one — mortvivant, both items harden the project's single verification and CI contract within 5 acceptance criteria.
- Out of scope:
  - Application runtime behavior, UI layout, or Accessibility Service logic changes.
  - Upgrading AndroidX, Kotlin, or Gradle dependency versions (`GradleDependency` lint notices remain informational).

## Acceptance criteria
- [x] AC-1 — Given any Kotlin source file in `app/src/main/kotlin` or `app/src/test/kotlin`, Kotlin compilation enforces `allWarningsAsErrors = true` so the project compiles with zero warnings and fails compilation with a non-zero exit code if a compiler warning emitted by `kotlinc` (such as an unhandled `@Deprecated` API usage) is introduced (`B-2`).
- [x] AC-2 — Given the `lint [fast]` step in `scripts/check.conf`, running `./scripts/check --fast` executes Ktlint, Detekt, and Android Lint (`lintDebug`) across the project, passes with zero violations on the codebase, and fails with a non-zero exit code if an unused declaration (`val`, property, parameter, or import), code-style violation, or static-analysis defect is introduced (`B-2`).
- [x] AC-3 — Given `.github/workflows/check.yml`, the workflow declares top-level `permissions: contents: read`, includes `gradle/actions/setup-gradle@v4` in both the `check` and `doctor` jobs, and scopes `chmod +x` strictly to executable scripts (`gradlew`, `scripts/check`, `scripts/doctor`, `scripts/residue`, `scripts/selftest`, `scripts/init`) excluding `scripts/check.conf`, verified by automated unit test (`B-5`).
- [x] AC-4 — Given the packaged application dependencies and `docs/conventions.md`, static-analysis tooling adds zero runtime APK dependencies or permissions (`INV-1`), `docs/conventions.md` documents the active `allWarningsAsErrors` and Ktlint/Detekt enforcement, and closed entries `B-2` and `B-5` are removed from `specs/backlog.md` (`B-2`, `B-5`).
- [x] AC-5 — Given the updated `scripts/check.conf`, both `./scripts/check` (full) and `./scripts/check --fast` exit `0`, and both `./scripts/residue` and `./scripts/residue --fast` report `RESIDUE CLEAN`.

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
| Fix rounds | 2 |
| Review findings: real / noise / deferred | 4 / 0 / 0 (plus 2 / 0 / 0 in Verify round 1) |
| Regressions introduced | 0 |
| Bugs escaped to production | 0 |
