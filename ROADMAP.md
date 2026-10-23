# Laps Product Roadmap

> **Product Promise:** An open-source Android companion for COROS runners, bringing training metrics and planned workouts to home-screen widgets, with local-first data handling.

---

## 🟢 Completed (v1.0.0)

- [x] **12 Material 3 Home Screen Widgets**: 6 slots covering Weekly Mileage, EvoLab Training Load, Next Workout, 80/20 Zone Balance, Race Countdown, and Command Center.
- [x] **Pure Material You Theming**: Automatic wallpaper dynamic color extraction for Android 12+ (API 31+).
- [x] **Zero-Cloud Calendar Sync**: Native `CalendarContract` integration reading planned workouts and updating completed/skipped/shifted states.
- [x] **Adaptive Missed-Workout Resolution**: Heuristic physiological coach providing context-aware **Drop** (protect Sunday long runs) and **Shift** flows.
- [x] **Dual Authentication Architecture**: Official COROS Model Context Protocol (MCP) Bearer token authentication + direct HTTPS fallback.
- [x] **Autonomous Background Sync**: Android `WorkManager` periodic synchronization every 3 hours with network awareness.
- [x] **Hermetic Testing Suite**: 13 pure-JVM unit tests running in <400ms via `FakeCalendarStore`.

---

## 🟡 In Progress (v1.1.0)

- [ ] **Direct-to-Watch Workout Push**: Writing periodized structured sessions straight to COROS devices via official Partner API / MCP write tools.
- [ ] **IzzyOnDroid & F-Droid Submission**: Publishing automated reproducible build metadata for the open-source Android catalog.
- [ ] **Widget Stale-Data Indicator**: Subtle visual indicator on launcher widgets if background synchronization hasn't succeeded in >12 hours (e.g., airplane mode).
- [ ] **Battery Optimization Guide**: In-app guidance for OEMs with aggressive background process killers (Xiaomi MIUI, Samsung OneUI, OnePlus OxygenOS).

---

## 🔵 Planned (v1.2.0+)

- [ ] **Multi-Calendar Support**: Color-coded categorization if the runner maintains separate calendars for workouts, races, and gym sessions.
- [ ] **CSV / FIT File Plan Import**: Ability to import external training plans (e.g. Pfitzinger, Jack Daniels VDOT, Hal Higdon) directly into Laps.
- [ ] **Intervals.icu Direct Sync**: Option to pull fitness, fatigue, and form curves directly from Intervals.icu alongside COROS telemetry.

---

## 🚫 Explicitly Out of Scope

To maintain privacy, reliability, and code simplicity, Laps will **not** include:
- **Centralized Cloud Relay Servers:** No user data or passwords will ever pass through an intermediary backend.
- **Social Feeds & Leaderboards:** Laps is a personal training tool, not a social network.
- **Advertising or Tracking SDKs:** Zero telemetry or ad networks will ever be packaged into binaries.
- **Paid Subscriptions / Paywalls:** All core functionality remains 100% free and open-source under the MIT license.
