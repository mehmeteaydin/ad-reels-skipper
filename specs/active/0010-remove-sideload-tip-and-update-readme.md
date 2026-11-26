# Spec 0010 — Remove Android 13+ Sideload Tip from Dashboard and Update README (mini)

- Status: Approved
- Mode: strict
- Plan: `specs/plans/0010-plan.md`
- Self-critique: done
- Source: user-request-2026-11-26-ui-bottom-cleanup-readme
- Design: none — removes the trailing Android 13+ Sideloaded Install Tip section from `BackgroundReliabilityCard` (`S-10a`/`S-10b`)
- Supersedes: 0008/AC-3

## Intent
The trailing **"Android 13+ Sideloaded Install Tip"** block and **"Open App Info"** button at the bottom of the dashboard's **Background Reliability** card clutter the daily UI once the app is installed, while the repository's root `README.md` still contains the original workspace template instead of documenting **Ad Reels Skipper**. Removing the trailing sideload tip from the bottom card keeps the dashboard focused on live reliability status (Battery, Keep-Alive Notification, and Xiaomi/OEM Autostart), and rewriting `README.md` moves the Android 13+ sideloading tip into complete, accurate project documentation.

## Changed behavior
- [ ] CB-1 — `BackgroundReliabilityCard` (`HomeScreen.kt`) omits the `"Android 13+ Sideloaded Install Tip"` title, description, trailing divider, and `"Open App Info"` button; `MainActivity.kt` no longer passes `onOpenAppInfo`, `DashboardTags.OPEN_APP_INFO_BUTTON` is removed, and the unused string resources (`restricted_settings_tip_title`, `restricted_settings_tip_desc`, `open_app_info`) are removed from `strings.xml`, verified by updated `DashboardReliabilityTest` assertions (`assertDoesNotExist`) and fresh `S-10a`/`S-10b` headless screenshots.
- [ ] CB-2 — `README.md` accurately documents **Ad Reels Skipper** in English (`docs=en`): product overview, privacy guarantees and exact 3-permission allow-list (zero `INTERNET`), feature summary (auto-skip, weekly activity & history archive, skip delay, custom keywords, foreground keep-alive), installation & setup guide (including Android 13+ "Allow restricted settings" sideloading steps and Xiaomi MIUI/HyperOS background reliability checklist), local build and optional release signing setup, and verification commands (`./scripts/check`, `./scripts/doctor`).
- [ ] CB-3 — `./scripts/check`, `./scripts/check --fast`, and `./scripts/doctor` succeed with zero failures (including Android Lint unused-resource check and Detekt with 0 issues).

## Preserved behavior
- [ ] PB-1 — Inside `BackgroundReliabilityCard`, Battery Optimization status/CTA, Keep-Alive Notification status/CTA, and Xiaomi/OEM Autostart guidance + `"Open Autostart Settings"` remain intact with `48.dp` minimum touch targets and `S-10a` -> `S-10b` live refresh on resume.
- [ ] PB-2 — `MainActivity.openAppInfoSettings()` remains intact as the internal fallback so Battery, Notification, and Autostart settings intents still fall back cleanly to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` on `ActivityNotFoundException` / `SecurityException` without crashing.
- [ ] PB-3 — Existing dashboard states (`S-1`..`S-9`, including `S-1` initial-viewport visibility without scrolling), `SkipperAccessibilityService` silent foreground keep-alive, manifest 3-permission allow-list, release signing contract, `domain` zero-`android.*` purity, `ui` zero-`AccessibilityService` isolation, `INV-1`/`INV-2`, and `specs/done/` immutability remain intact.

## Out of scope
- Changes to `SkipperAccessibilityService`, ad detection logic, `AndroidManifest.xml`, or signing configuration.
- Adding new UI cards, settings, or translations.
- Editing any shipped spec under `specs/done/`.

## Definition of Done
- [ ] `scripts/check` green
- [ ] Independent review done; real findings fixed, noise rejected with written rationale, deferred ones in `specs/backlog.md`
- [ ] Criterion ↔ evidence table complete for CB-* **and** PB-* (UI: before/after screenshots, agent-captured, judged at SHIP)
- [ ] Spec moved to `specs/done/` (immutable there)
