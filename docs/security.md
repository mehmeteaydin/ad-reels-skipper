# Security

> **Template — filled during bootstrap.** Baseline rules agents must honor in every plan and review.

## Secrets
- Secrets never enter the repo, specs, prompts, or chat. `.env` is gitignored; provide `.env.example`.
- Agents never print secret values, even when debugging.

## Input & output
- **Screen content**: Accessibility node hierarchies are inspected in-memory only. Node texts are never stored, logged, or serialized.
- **User input**: Custom keywords entered in settings are trimmed, validated for length (1-50 characters), and sanitized against regex injection.
- **Log safety**: Logcat output in debug mode must never dump entire view hierarchies or arbitrary text; logs are restricted to operation milestones (e.g. "Ad detected", "Gesture dispatched").

## AuthN / AuthZ
- **OS Enforcement**: The service is guarded by `android.permission.BIND_ACCESSIBILITY_SERVICE` so only the Android system framework can bind to it.
- **Least-Privilege Accessibility & Manifest Scope**:
  - `accessibility_service_config.xml` restricts event listening strictly to `com.instagram.android` and omits auxiliary multi-window inspection flags (`flagRetrieveInteractiveWindows`, `flagDefault`), reading only the active window (`rootInActiveWindow`).
  - `AndroidManifest.xml` declares zero `<uses-permission>` elements and sets `android:allowBackup="false"` so local preferences cannot be backed up off-device.
- **Zero Network Guarantee**: The app does not declare or request `android.permission.INTERNET`. The OS forbids all outbound and inbound network traffic.
- **Signing Secrets**: Optional local release APK signing uses untracked `keystore.properties` or `SKIPPER_KEYSTORE_*` environment variables (`keystore.properties`, `*.jks`, and `*.keystore` are gitignored and must never enter the repo).

## Dependencies
- **Minimal footprint**: Only official AndroidX, Jetpack Compose, KotlinX Coroutines, and DataStore libraries are permitted.
- **Approval policy**: Adding any third-party library requires explicit human approval, license verification (permissive Apache-2.0 or MIT only), and CVE audit.
- **Networking libraries banned**: Inclusion of HTTP clients (OkHttp, Retrofit, Ktor, Volley) or analytics SDKs is strictly prohibited.

## Review lens
Security is a mandatory dimension of every independent review (see `prompts/review.md`), not a
separate afterthought phase.
