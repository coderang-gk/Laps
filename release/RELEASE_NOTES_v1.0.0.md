# Laps v1.0.0 — Open-Source Android Companion for COROS Runners

We are excited to announce the first official release of **Laps** (`v1.0.0`), an open-source Android companion app bringing rich glanceable training metrics, recovery statistics, and upcoming workouts directly to your Android home screen via native Material 3 widgets.

---

### Highlights

* **6 Widget Categories / 12 Styles**:
  * **Weekly Mileage Tracker**: Progress ring, weekly km total, remaining target, and daily breakdown bar chart.
  * **Recovery Radar**: Live battery gauge, training load, exertion status, readiness advisory.
  * **Upcoming Workout**: Daily scheduled workout card, target pace, distance, and quick action shortcuts.
  * **Activity Heatmap**: Contribution grid of training consistency over the past 90 days.
  * **Marathon Countdown**: Race day countdown clock with phase-specific training tips.
  * **Zone Spectrum**: Polarization ratios across Aerobic, Threshold, and VO2 Max zones.
* **Flexible Modes**:
  * **Metrics Only Mode (Default)**: Use Laps purely for widgets displaying your live COROS weekly mileage, acute load, and HR zones without needing a structured training plan.
  * **Training Plan Mode**: Connect an on-device Google Calendar to generate 80/20 structured periodized schedules and resolve missed workouts dynamically.
* **Hermetic Local-First Architecture**:
  * Zero analytics, zero ad networks, zero external telemetry relays.
  * Direct TLS/HTTPS communication with official COROS endpoints on-device.
  * All credentials and session tokens stay strictly inside Android's private app sandbox.
  * Complete atomic credential purge upon sign-out.
* **Autonomous Background Refresh**:
  * Integrated with Android `WorkManager` for battery-efficient 3-hour background synchronization.

---

### Integrity & Verification

| Asset | File | SHA-256 Checksum |
| :--- | :--- | :--- |
| **Release APK** | `Laps-v1.0.0.apk` | `1e00a5715dc2e9ad7ddc94b03278509c34117b8ad203f5fc70c549e140dd77e6` |

To verify the integrity on your machine before installing:
```bash
shasum -a 256 Laps-v1.0.0.apk
```

---

### Requirements & Installation

1. Requires **Android 8.0 (API 26)** or higher (Material You theming dynamically active on Android 12+).
2. Download `Laps-v1.0.0.apk` from the Assets below.
3. Open the file on your Android device and allow installation from your file manager or browser.
4. Launch Laps, connect your COROS account, and add your preferred widgets from your launcher's widget picker.
5. *Tip for Samsung / Xiaomi / OnePlus devices*: Set Laps battery usage to **Unrestricted** in Android App Info for continuous 3-hour widget refreshes.

---

### Disclaimers & Independent Status

Laps is an independent, community-developed open-source project and is not affiliated with, authorized, maintained, or endorsed by COROS Wearables Inc. All training telemetry is queried directly from official Training Hub endpoints on-device.
