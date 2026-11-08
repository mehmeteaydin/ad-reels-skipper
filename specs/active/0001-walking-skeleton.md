# Spec 0001 — Walking Skeleton

- Status: Approved
- Mode: strict
- Plan: `specs/plans/0001-plan.md`
- Self-critique: done

## Intent
Establish the minimal foundational Android project structure and Gradle toolchain for `ad-reels-skipper`. This walking skeleton provides a reproducible baseline that compiles the app, executes unit tests, registers the accessibility service structure, and activates the project's automated verification contract (`./scripts/check`). Full reel ad detection, gesture dispatching, and settings management are deliberately excluded and deferred to dedicated feature specs.

## Requirements
- The project toolchain must configure modern Android standards (Kotlin 2.x, Jetpack Compose, Gradle Kotlin DSL, Target SDK 35, Min SDK 26, JDK 17+).
- The application manifest must declare the application package and declare `SkipperAccessibilityService` guarded by `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- The manifest must enforce zero network access by strictly omitting `android.permission.INTERNET`.
- The application must provide a minimal initial Compose activity displaying the application title and basic status.
- The test harness must execute JVM-based unit tests verifying baseline components.
- The automated verification pipeline (`./scripts/check`) must run active steps (`lint [fast]`, `test [fast]`, `build`) and succeed with exit code 0.

## User experience
- Flow: The user opens the app from the Android launcher; the app displays the initial screen showing the application title and installation state.
- States: Initial launch screen.
- Design reference: none — the flow above is the design.
- Accessibility: Text elements follow Material 3 accessible contrast guidelines.

## Constraints & out of scope
- JDK requirement: OpenJDK 17 or higher for Gradle / AGP compatibility.
- Sensitive area: Accessibility Service (`docs/domain.md`).
- Out of scope:
  - Live Instagram accessibility event processing and ad skipping algorithms (Spec 0002).
  - User settings, custom keyword lists, and preference persistence (Spec 0003).
  - Production release signing configurations.

## Acceptance criteria
- [ ] AC-1 — Gradle toolchain builds the Android debug application (`assembleDebug`) without errors, generating the APK output.
- [ ] AC-2 — `AndroidManifest.xml` declares `SkipperAccessibilityService` requiring `android.permission.BIND_ACCESSIBILITY_SERVICE` and contains no `android.permission.INTERNET`.
- [ ] AC-3 — Initial Compose activity renders the application title "Ad Reels Skipper", verified by a headless component test.
- [ ] AC-4 — JVM unit test suite runs and passes cleanly via Gradle (`testDebugUnitTest`).
- [ ] AC-5 — `./scripts/check` and `./scripts/check --fast` run active steps and exit with code 0.

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
