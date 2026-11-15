# Conventions

> Baseline rules and standards for the `ad-reels-skipper` project. Every rule here is enforced by tooling or verified during code review.

## Language
- Chat language (interviews, sessions): Turkish (`tr`)
- Document language (docs/, specs, plans, ADRs, review and verify reports): English (`en`)
- Always English (protocol, not prose): ANEW core files, template headings and field labels, `Status:` values (Draft / Approved / In progress / Shipped), `Approved by / on:`, `Source:`.
- Code identifiers, branch names, commit messages: English
- Agent messages (handoff summaries, refusals, `Next:` lines): chat language (Turkish); command names, file paths, and protocol fields verbatim.

## Language & framework versions
- **Language**: Kotlin 2.0+
- **Build System**: Gradle 8.x with Kotlin DSL (`build.gradle.kts`)
- **Android Target**: Compile SDK 35, Target SDK 35, Min SDK 26 (Android 8.0 Oreo)
- **UI Toolkit**: Jetpack Compose (using Compose BOM) with Material 3
- **Asynchrony**: Kotlin Coroutines & Flow
- **Persistence**: Jetpack DataStore Preferences
- **Architecture**: MVVM / Clean Architecture separation (pure domain engine without Android dependencies for fast JVM unit tests)

## Naming
- **Packages**: `com.adreels.skipper.*` (`service`, `domain`, `data`, `ui`)
- **Classes & Interfaces**: PascalCase (e.g. `ReelAdDetector`, `SkipperAccessibilityService`, `PreferencesRepository`)
- **Functions & Properties**: camelCase (e.g. `evaluateNode()`, `isServiceEnabled`)
- **Composable Functions**: PascalCase (e.g. `DashboardScreen()`, `SettingsCard()`)
- **Unit Tests**: PascalCase ending with `Test` (e.g. `ReelAdDetectorTest`), using descriptive test names with backticks (e.g. ``fun `skips reel when sponsored keyword detected`()``)
- **Branches**: `feature/<spec-no>-<short-name>`, `refactor/<spec-no>-<short-name>`, `fix/<report-id>-<short-name>`, `trivial/<short-name>`, `chore/bootstrap`
- **Commits**: Conventional Commits referencing the active plan step (e.g. `feat(detector): keyword matching rule [plan 0001/2]`)

## Error handling
- **Fail-safe background operation**: The accessibility service runs alongside user interaction. An error during node inspection or gesture dispatch must never crash the service or disrupt the user's phone.
- **Safe traversal**: Wrap UI node traversal and gesture dispatch in defensive exception handling; log non-fatal warnings locally.
- **Graceful degradation**: If gesture dispatch fails (e.g. screen locked or device temporarily unresponsive), record telemetry locally and silently recover on the next user event.

## Data rules
- **Zero remote telemetry**: No analytics, telemetry, or external payloads are collected or stored.
- **Preferences**: Stored via Jetpack DataStore Preferences.
- **Timing & Delays**: Durations stored in milliseconds (`Long` or `Int`). Valid skip delay range: 100 ms to 2000 ms (default: 400 ms).
- **Keywords**: Normalized as lowercase, trimmed strings. Stored as `Set<String>`.

## Known traps
- **AccessibilityNodeInfo memory leaks**: Always release or recycle `AccessibilityNodeInfo` objects where appropriate, especially on older Android API versions.
- **Event flooding**: `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` triggers rapidly during scroll animations. Node analysis must be debounced / throttled to preserve CPU and battery.
- **Instagram view ID volatility**: Never bind detection rules to internal view IDs (e.g. `R.id.text_view`), as Instagram obfuscates and changes them with each release. Rely solely on visible text, content descriptions, and semantic node structure.

## Enforced by tooling
- Android Lint (`./gradlew lintDebug --no-daemon`) for Android/Kotlin static analysis.
- JUnit 5, Robolectric Compose UI tests, and XML manifest/config verification (`./gradlew testDebugUnitTest --no-daemon`).
- `./scripts/check` as the single verification gate (strict `kotlinc` warnings and Ktlint/Detekt deferred in `specs/backlog.md` as `B-2`).
