<div align="center">
  <img src="assets/icon.png" width="96" height="96" alt="Laps Logo" />
  <h1>Laps</h1>
  <p><strong>Open-source Android companion for COROS runners, bringing training metrics & planned workouts to home-screen widgets with local-first data handling.</strong></p>
  <p>100% on-device. Zero cloud relays, zero tracking, no Google developer console setup.</p>

  <p>
    <a href="https://github.com/coderang-gk/Laps/actions/workflows/ci.yml"><img src="https://github.com/coderang-gk/Laps/actions/workflows/ci.yml/badge.svg" alt="CI Status" /></a>
    <a href="https://github.com/coderang-gk/Laps/releases/latest"><img src="https://img.shields.io/github/v/release/coderang-gk/Laps?color=blue&label=Release" alt="Latest Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT" /></a>
  </p>

  <p>
    <a href="#overview">Overview</a> •
    <a href="#screenshots">Screenshots</a> •
    <a href="#features">Features</a> •
    <a href="#setup">Setup</a> •
    <a href="ROADMAP.md">Roadmap</a> •
    <a href="CONTRIBUTING.md">Contributing</a> •
    <a href="SECURITY.md">Security</a>
  </p>
</div>

---

## Overview

**Laps** is an open-source, fully self-contained Android client that connects your **COROS Training Hub** account with your on-device **Google Calendar** and delivers 12 dynamic **Material 3 Expressive** home screen widgets.

Unlike solutions requiring self-hosted Python servers, webhook relays, or Google Cloud OAuth verification consoles, Laps runs **entirely on your phone**:
- Communicates directly with COROS Training Hub endpoints.
- Reads and updates planned runs straight through Android's native `CalendarContract`.
- Builds periodized 80/20 training schedules and writes workouts to your calendar in one tap.
- Analyzes training load, fatigue, and missed workouts locally using an EvoLab physiological engine, with optional BYO Gemini AI reasoning.

---

## Screenshots

| <img width="256" src="assets/screenshot_hub.jpg"> | <img width="256" src="assets/screenshot_coaching.jpg"> | <img width="256" src="assets/screenshot_missed_resolution.jpg"> |
|:---:|:---:|:---:|
| Training Hub | Post-Run Coaching | Adaptive Resolution |
| <img width="256" src="assets/screenshot_plan_generator.jpg"> | <img width="256" src="assets/screenshot_widgets_catalog.jpg"> | <img width="256" src="assets/screenshot_widgets_sculptural.jpg"> |
| 80/20 Plan Generator | Material 3 Widgets | Sculptural Dials |

---

## Features

### Direct On-Device COROS Integration
- **Direct HTTPS Authentication**: Connects directly and securely with official COROS Training Hub endpoints (`teamapi.coros.com`) strictly on-device. Zero middleman cloud relays, zero external telemetry.
- **Why Direct Auth?**: COROS does not currently offer a public consumer OAuth 2.0 portal for independent mobile applications (its enterprise Partner API and MCP OAuth server enforce HTTPS redirect URIs and pre-registered enterprise client credentials). Laps handles all authentication directly from your device, storing credentials exclusively in Android's private sandbox.
- **Advanced Token Support**: Developers who maintain an active COROS Model Context Protocol (MCP) Bearer token can optionally authenticate via raw token.
- Fetches real-time workout telemetry: distance, pace, elevation, cadence, training load, aerobic/anaerobic TE, and heart rate.
- Synchronizes EvoLab metrics: Marathon Level, Lactate Threshold Pace & HR, Running Fitness, Fatigue, and Load Impact.

### Zero-Setup Google Calendar Sync
- Uses Android's built-in `CalendarContract` provider.
- No `credentials.json`, service accounts, or Google Cloud OAuth verification screens needed.
- Reads upcoming scheduled workouts from any local or Google calendar on your device.
- Directly updates event titles and timestamps when workouts are completed, shifted, or skipped.

### 80/20 Periodized Plan Generator
- Generates structured training plans for **5K**, **10K**, **Half Marathon**, and **Marathon**.
- Periodizes mileage across 6 to 16-week cycles with progressive overload and planned taper phases.
- Automatically calculates weekly quality splits (Threshold, Tempo, Strides) and Sunday Long Runs aligned to your EvoLab threshold pace.
- Publishes all structured workouts directly into your chosen Google Calendar in one tap.

### Dynamic Missed-Workout Resolution (Adaptive AI Coaching)
- When a scheduled training run is missed, Laps evaluates your 7-day acute training load, upcoming key sessions, and threshold pace.
- **Adaptive Coach Decision Engine**:
  - **Drop / Skip Flow**: Prevents dangerous training load stacking by marking the missed run `[SKIPPED]` directly in Google Calendar, maintaining the weekly 80/20 polarization without cramming.
  - **Shift Flow**: Pushes quality workouts to upcoming rest days while intelligently rewriting calendar event titles (`W3 Tue` $\rightarrow$ `W3 Wed`) and preserving scheduled start times (e.g., 6:30 AM).
  - **Long Run Protection Heuristics**: If the athlete's acute training load is elevated or a key Long Run is scheduled within 48 hours, the coach automatically flags a high-priority `[AI RECOMMENDED]` decision to drop intensity or convert it into a light shakeout rather than doubling up on fatigue.
- **Dual Reasoning Engine**: Powered offline by the built-in EvoLab physiological heuristics, with optional BYO **Gemini AI Coach** integration for personalized natural language recovery commentary.

### 12 Material 3 Expressive Home Screen Widgets
- Pure Material You dynamic theming (Android 12+ / API 31+): dynamically adopts your wallpaper's system accent colors across all app surfaces and widgets with seamless dark/light mode switching.
- Sculptural and geometric forms drawn with `androidx.graphics:graphics-shapes` (concentric dials, Cookie-12 scallops, contrast pods, split horizons).
- Six widget slots, each supporting multiple interchangeable styles:
  1. **Weekly Mileage**: Mileage progress, remaining target, and Zone 2 distribution.
  2. **Training Load**: Fatigue index, base fitness, and readiness status.
  3. **Next Workout**: Distance, workout type, target pace, and HR zone.
  4. **80/20 Zone Balance**: Aerobic base vs lactate threshold volume distribution.
  5. **Race Countdown**: Days remaining, goal time, and target race pace.
  6. **Command Center**: Comprehensive athlete overview with multi-day schedule.

---

## Setup

1. **Download & Install**: Grab the signed APK from the [Releases](https://github.com/coderang-gk/Laps/releases) page.
   - **SHA-256 (v1.0.0)**: `1e00a5715dc2e9ad7ddc94b03278509c34117b8ad203f5fc70c549e140dd77e6`
2. **Permissions**: Grant Calendar (optional) and Notification permissions when prompted so Laps can read your training schedule and update completed runs.
3. **Connect Account**: Open **Settings** and connect via **Direct Login** (or paste an optional MCP Bearer token).
4. **Choose Mode**:
   - **Metrics Only (Default)**: Keep calendar set to *"None (Metrics Only)"* to use Laps purely for glanceable home screen widgets.
   - **Training Plan Mode**: Select your Google Calendar if you wish to generate 80/20 structured workout plans and track upcoming runs.
5. **(Optional) Gemini Key**: If you wish to enable extended AI coaching commentary on missed runs, paste your Gemini API key in Settings.

---

## Architecture

```
io.github.coderang.laps/
├── calendar/             # Native Android CalendarContract query, insert, shift, skip & PlanGenerator
├── coach/                # EvoLab physiological heuristics & Gemini AI reasoning
├── coros/                # COROS API client (OAuth 2.0 / MCP tokens + direct HTTPS sync)
├── data/                 # Domain models (Athlete, Workout, EvoLab, Widgets) & SharedPreferences
├── notifications/        # Local Android post-run notifications
├── sync/                 # WorkManager autonomous 3-hour background sync worker
├── ui/                   # Material 3 slate-indigo / Material You palette & Canvas renderer
├── widgets/              # AppWidgetProviders for the 6 home screen slots
└── MainActivity.kt       # Training Hub, Widget Catalog, and Settings UI
```

---

## Building from Source

Requirements:
- Android Studio Ladybug or newer
- JDK 21+
- Android SDK 36 (Min SDK 31)

```bash
git clone https://github.com/coderang-gk/Laps.git
cd Laps
./gradlew assembleDebug
```

The resulting APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Testing & Reliability

Laps adopts a Canta-inspired hermetic testing architecture. All business logic, EvoLab physiological heuristics, 80/20 plan generation, COROS client auth, and calendar operations run on pure JVM with zero device emulator requirements using in-memory fakes (`FakeCalendarStore`):

```bash
./gradlew test
```

- **Execution Speed**: **22 unit tests** execute in **<500ms** on pure JVM.
- **Test Matrix**:
  - `CalendarSyncManagerTest` (7 tests): Skip flow (`[SKIPPED]` title tagging), Shift flow (automatic DOW title rewrite e.g. `W3 Tue` $\rightarrow$ `W3 Wed` and scheduled time preservation), metadata regex parsing.
  - `CorosApiClientTest` (5 tests): Bearer token authentication, blank token rejection, unauthenticated error handling, defensive activity & profile model defaults.
  - `EvoLabAnalyzerTest` (4 tests): High-intensity exhaustion heuristics, key Long Run protection, threshold swapping, aerobic base absorption.
  - `LapsDataModelsTest` (4 tests): Default sample validation, weekly mileage completion ratios, EvoLab productive status, 80/20 polarization integrity.
  - `PlanGeneratorTest` (2 tests): 80/20 Half-Marathon (40 workouts) & Marathon progressive overload generation.

---

## Privacy & Security

- **Zero Cloud Intermediaries**: Laps has zero analytics trackers, zero advertising SDKs, and zero telemetry relays. All network requests originate directly from your phone to official COROS endpoints over encrypted TLS/HTTPS.
- **Local On-Device Sandboxing**: All session tokens and credentials are stored exclusively in Android private app storage (`MODE_PRIVATE`). No third-party app on your device can access Laps's private files.
- **One-Tap Atomic Sign-Out**: Tapping **Clear Credentials / Sign Out** in Settings instantly purges all tokens, passwords, Gemini keys, and cached training metrics from disk and memory.

---

## Disclaimers & Known Limitations

> [!WARNING]
> **Independent Project**: Laps is an independent, community-developed open-source project. It is **not** affiliated with, authorized, maintained, or endorsed by COROS Wearables Inc. or Google LLC.

* **API Stability**: Because COROS does not provide a public, documented consumer REST API or self-serve OAuth portal for third-party mobile applications, Laps interacts directly with official Training Hub endpoints on-device. Upstream endpoint changes by COROS could disrupt synchronization.
* **Local Storage Disclosure**: While Android's private app sandbox protects credentials via Linux UID separation, no local storage mechanism is completely impervious to physical access on rooted or compromised devices. Avoid using the app on rooted phones with insecure superuser configurations.
* **Android Battery Optimization Policies**: Many device manufacturers (Samsung One UI, Xiaomi HyperOS, OnePlus OxygenOS) aggressively terminate background workers to save battery. For uninterrupted 3-hour widget updates, set Laps battery usage to **Unrestricted** in Android *App Info > Battery*.

---

## Roadmap

- [x] Autonomous silent background sync via Android `WorkManager` (v1.0)
- [x] Adaptive Missed-Workout Resolution (Skip & Shift heuristics with Google Calendar title rewrites) (v1.0)
- [x] First-class Metrics Only mode (Widgets without mandatory calendar training plans) (v1.0)
- [ ] Direct-to-Watch structured workout push via COROS Partner API / MCP write tools (v1.1)
- [ ] IzzyOnDroid & F-Droid repository distribution (v1.1)

---

## License

This project is licensed under the [MIT License](LICENSE).
