# Architecture

> Architectural design and module boundaries for `ad-reels-skipper`. Agents read this file before planning; design boundaries defined here are strictly enforced.

## System overview
`ad-reels-skipper` is a native Android application that runs a background Accessibility Service to automatically detect and skip sponsored Instagram Reels. The architecture follows Clean Architecture with a strict decoupling between Android platform APIs and core detection logic. The ad detection engine is implemented as a pure Kotlin module with zero Android framework dependencies, ensuring fast, deterministic JVM-based unit testing. The user interface is built with Jetpack Compose following the unidirectional data flow (UDF) pattern.

## Modules / components and ownership

| Module / Package | Single responsibility | Owns |
|---|---|---|
| `service` | Android AccessibilityService lifecycle, event ingestion, and gesture dispatch | `AccessibilityService` subclass, gesture execution (`dispatchGesture`), event filters |
| `domain` | Pure business logic: keyword matching, debounce/cooldown timing, detection decision | Detection algorithms, keyword rules, decision models (`SkipDecision`), debounce state |
| `data` | Persistent local storage for user preferences and skip metrics | DataStore preferences (skip delay, keyword list, master toggle, skip counters) |
| `ui` | Compose screens, permission onboarding guide, view models | Composables (Dashboard, Settings), ViewModels, UI state models |

## Communication rules
- **Service to Domain**: The `service` captures accessibility events from Instagram (`com.instagram.android`), extracts textual/structural metadata into lightweight pure Kotlin domain models, and invokes the `domain.AdDetector`.
- **Domain to Service**: The `domain.AdDetector` returns an immutable decision (`SkipDecision.Skip` or `SkipDecision.Ignore`). If `Skip`, the `service` initiates a gesture dispatch after the configured delay.
- **Service to Data**: On successful skips, `service` increments the local skip counter via the `data` repository.
- **UI to Data**: The `ui` layer observes settings and metrics via Kotlin Coroutine `StateFlow` exposed by the `data` layer and updates preferences via repository methods.

## Forbidden dependencies (make them testable)
- **Domain isolation**: The `domain` module/package must NEVER import any Android framework classes (`android.*`). All inputs to domain logic must use pure Kotlin data models or interfaces.
- **UI & Service isolation**: The `ui` layer must NEVER directly invoke accessibility service methods or dispatch gestures. Communication occurs exclusively through shared local state in `data`.
- **Zero network**: No layer in the application may import or use networking libraries (`java.net.*`, `okhttp3.*`, `retrofit2.*`, etc.). The application is strictly offline.

## Deliberately out of scope
- Non-Instagram platforms (YouTube Shorts, TikTok, Facebook Reels) are deferred until Instagram detection is fully stabilized.
- On-screen OCR or machine-learning based image recognition (detection is strictly based on accessibility node text and semantic structure).
- Cloud synchronization, user accounts, and remote configuration.
- Root or ADB-level privileged operations.
