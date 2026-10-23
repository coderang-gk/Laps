# Laps v1.0.0 — Open-Source Android Companion for COROS Runners

We are excited to announce the first official release of **Laps** (`v1.0.0`), an open-source Android companion app bringing rich glanceable training metrics, upcoming workouts, and recovery statistics directly to your Android home screen via native widgets.

---

### Highlights

* **6 Widget Categories / 12 Styles**:
  * **Weekly Mileage Tracker**: Progress ring, target mileage, remaining km, daily breakdown bar chart.
  * **Recovery Radar**: Live battery gauge, training load, exertion status, readiness advisory.
  * **Upcoming Workout**: Daily scheduled workout card, target pace, distance, and quick action shortcuts.
  * **Activity Heatmap**: Contribution grid of training consistency over the past 90 days.
  * **Marathon Countdown**: Race day countdown clock with phase-specific training tips.
  * **Zone Spectrum**: Polarization ratios across Aerobic, Threshold, and VO2 Max zones.
* **Authentication Options**:
  * **COROS OAuth 2.0 / MCP Bearer Token**: Connect securely through `mcp.coros.com` without sharing account passwords.
  * **Direct Training Hub Sync**: Direct fallback integration for immediate on-device calendar retrieval.
* **Hermetic Local-First Architecture**:
  * Zero analytics, zero ad networks, zero external telemetry relays.
  * All credentials and cache tokens stay strictly inside Android's private app sandbox.
  * Complete atomic credential purge upon sign-out.
* **Autonomous Background Refresh**:
  * Integrated with Android `WorkManager` for battery-efficient 3-hour background synchronization.

---

### Integrity & Verification

| Asset | File | SHA-256 Checksum |
| :--- | :--- | :--- |
| **Release APK** | `Laps-v1.0.0.apk` | `6f0057eed0e3100acbb94246fd17c444e8c948f808db0fdad2d8e8a6bc685e90` |

To verify the integrity on your machine before installing:
```bash
shasum -a 256 Laps-v1.0.0.apk
```

---

### Requirements & Installation

1. Requires **Android 8.0 (API 26)** or higher.
2. Download `Laps-v1.0.0.apk` from the Assets below.
3. Open the file on your Android device and allow installation from your file manager or browser.
4. Launch Laps, connect your COROS account, and add your preferred widgets from the Android Home Screen widget picker.

---

### External Testing & Feedback

We are looking for 10–20 independent COROS runners to test widget refresh reliability across different Android phone manufacturers (Pixel, Samsung, Xiaomi, OnePlus). If you encounter any widget sync issues or battery optimization hiccups, please [open an issue on GitHub](https://github.com/coderang-gk/Laps/issues).
