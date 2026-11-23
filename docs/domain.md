# Domain

> Domain concepts, ubiquitous language, and business rules for `ad-reels-skipper`.

## Ubiquitous language

| Term | Meaning | Notes / not to be confused with |
|---|---|---|
| `Reel` | A full-screen vertical video displayed within Instagram | Distinct from standard feed posts or ephemeral Stories. |
| `Sponsored Reel` | A Reel containing advertising or promotional content, marked with an ad label | Identified by presence of sponsored indicators. |
| `Sponsored Label` | Textual or semantic indicator (e.g. "Sponsored", "Sponsorlu") denoting an advertisement | Matches built-in or user-defined keyword sets. |
| `Ad Detector` | The pure domain logic component that evaluates node text against keyword rules | Contains no Android dependencies. |
| `Swipe Gesture` | A programmatic vertical swipe (bottom to top) simulating user scroll | Dispatched via `AccessibilityService.dispatchGesture`. |
| `Skip Delay` | User-configured delay in milliseconds before a detected ad is skipped | Range: 50 ms – 2000 ms (default: 150 ms). |
| `Cooldown Window` | Minimum quiet period after a gesture during which new skips are suppressed | Prevents multi-swiping caused by animation or event floods. |
| `Master Toggle` | Global user setting enabling or disabling automatic skipping | Controlled from the Compose dashboard. |
| `Weekly Skip History` | Local Monday–Sunday daily skip counts and up to 12 newest non-empty archived weeks | Stores strictly local calendar dates and integer counts (`BR-7`, `INV-1`). |

## Business rules
- **BR-1 (Target Confinement)**: Detection and gesture dispatch apply exclusively when the foreground application package is `com.instagram.android`.
- **BR-2 (Master Toggle State)**: When the Master Toggle is OFF, incoming events are discarded immediately with zero gesture dispatch and zero counter increment.
- **BR-3 (Keyword Matching)**: An ad is detected if any visible text node or semantic description contains any entry in the active Keyword Set. Matching is case-insensitive and ignores surrounding punctuation/whitespace.
- **BR-4 (Cooldown Protection)**: After a skip gesture is triggered, subsequent detection triggers are ignored for the duration of the Cooldown Window (default: 700 ms).
- **BR-5 (Configurable Delay)**: When an ad is confirmed, the swipe gesture executes after the exact configured `Skip Delay`.
- **BR-6 (Keyword Extensibility)**: User-defined keywords are persisted locally and unioned with default keywords (`Sponsored`, `Sponsorlu`, `Paid partnership`).
- **BR-7 (Skip Metrics)**: Each completed skip gesture (`onCompleted`) atomically increments both the local lifetime skip counter and the local daily skip count for the device's current calendar date by 1 (`Mon`–`Sun` active week plus up to 12 newest non-empty archived past weeks). Persisted metrics store strictly local calendar dates and integer counts (`INV-1`).

## Key domain invariants
- **INV-1 (Zero External Data Flow)**: No screen content, text, or user metadata observed by the service may ever be transmitted off-device or stored beyond the ephemeral evaluation of the current event.
- **INV-2 (No Unintended Swipes)**: The service must NEVER dispatch gestures when the user is outside the Instagram Reels viewer or when the app is in the background.

## Sensitive areas
- `Accessibility Service`: The Android Accessibility Service has broad platform privileges to observe UI hierarchies. To guarantee complete privacy and zero risk of data leakage, the project strictly enforces:
  1. No `android.permission.INTERNET` in `AndroidManifest.xml`.
  2. Accessibility event listening filtered strictly to `com.instagram.android`.
  3. No logging or persistent caching of inspected node text.
