# Contributing to Laps

Thank you for your interest in contributing to **Laps**! We welcome bug fixes, documentation improvements, test coverage expansions, and widget design refinements from athletes and Android developers alike.

---

## 🏗️ Getting Started

### Prerequisites
- **JDK:** 21 (Temurin or Android Studio JBR recommended)
- **Android SDK:** Compile SDK 36, Target SDK 36, Min SDK 31 (Android 12+)
- **Build Tool:** Gradle wrapper included (`./gradlew`)

### Building from Source
```bash
# Clone the repository
git clone https://github.com/coderang-gk/Laps.git
cd Laps

# Run unit tests (hermetic JVM execution in <400ms)
./gradlew test

# Build debug APK
./gradlew assembleDebug
```

The resulting debug binary will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🧪 Testing Philosophy

Laps follows a **hermetic testing architecture**:
- **Zero Heavy Device Mocks:** Business logic, calendar manipulation, and physiological EvoLab heuristics are decoupled from heavy Android framework classes via lightweight interfaces (e.g. `CalendarStore`).
- **In-Memory Fakes:** Unit tests execute against pure-JVM in-memory fakes (`FakeCalendarStore`) rather than slow, brittle emulator mocks.
- **Fast Feedback:** The entire unit test suite must execute in **<1 second** on standard hardware.

When submitting new features or bug fixes, please include corresponding unit tests in `app/src/test/`.

---

## 📐 Project Structure

```
app/src/main/java/io/github/coderang/laps/
├── calendar/             # Android CalendarContract query, insert, shift & skip + PlanGenerator
├── coach/                # EvoLab physiological heuristics & Gemini AI reasoning
├── coros/                # COROS API client (OAuth 2.0 / MCP tokens + direct HTTPS sync)
├── data/                 # Domain models, preferences, and local data repository
├── notifications/        # Local post-run notifications
├── sync/                 # WorkManager background periodic sync worker
├── ui/                   # Material 3 Expressive theme and widget canvas renderer
├── widgets/              # AppWidgetProviders for the 6 home screen slots
└── MainActivity.kt       # Training Hub, Widget Catalog, and Settings UI
```

---

## 🔀 Submitting a Pull Request

1. **Fork & Branch:** Create a branch from `main` with a descriptive name (e.g., `fix/widget-resize-alignment` or `test/calendar-shift-edge-cases`).
2. **Keep PRs Focused:** Aim for small, isolated pull requests that solve one problem well.
3. **Verify CI:** Ensure `./gradlew test` and `./gradlew assembleDebug` pass locally before opening your PR.
4. **Descriptive Description:** Explain *what* changed, *why* it changed, and how to verify it on an Android device.
