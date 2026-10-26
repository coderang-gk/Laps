# Laps v1.0.2 — Open-Source Android Companion for COROS Runners

We are pleased to release **Laps v1.0.2**, introducing native OAuth 2.0 authentication, automatic adaptive dark/light launcher icons, an in-app theme switcher, high-contrast light mode widget palettes, and Material 3 Expressive UI enhancements.

---

### What's New in v1.0.2

* **Secure In-App OAuth 2.0 Authentication**:
  * Seamless one-tap COROS account sign-in via secure Chrome Custom Tabs and PKCE callback (`laps://auth/callback`).
  * Full support for international COROS regional server clusters (Global / US, Europe, Singapore / Asia, China).
  * Direct email & password authentication remains available as a secondary fallback.

* **Automatic Dark / Light Adaptive Launcher Icon**:
  * Automatically switches between sleek dark and crisp light launcher icons based on your Android system dark mode setting using native Android resource qualifiers.
  * Zero launcher icon duplication across modern Android launchers.

* **In-App Theme Selection**:
  * Choose between **System Auto**, **Light**, or **Dark** mode directly within Laps Settings.

* **High-Contrast Light Mode Widget Styling**:
  * Dedicated, crisp light mode color palette across all 12 widget styles.
  * Pure white surfaces, deep slate typography, and vibrant accents ensure high readability outdoors under direct sunlight.

* **Material 3 Expressive Plan Hub FAB**:
  * "Edit Plan / Build Plan" is now an extended Floating Action Button (FAB) anchored above the scrollable content in the Plan Race Hub for quick, ergonomic access.

* **Upcoming Workout & Calendar Enhancements**:
  * Improved on-device calendar sync and workout parsing for structured interval distances.
  * Cleaned up workout cards with non-wrapping distance badges and accurate remaining distance indicators.

---

### Integrity & Verification

| Asset | File | SHA-256 Checksum |
| :--- | :--- | :--- |
| **Release APK** | `Laps-v1.0.2.apk` | `e1b405da449281f0b982b711df1face07234d8674b70d397cb7e90c10c825c6b` |

To verify file integrity:
```bash
shasum -a 256 Laps-v1.0.2.apk
```

---

### Requirements & Installation

1. Requires **Android 8.0 (API 26)** or higher (Material You dynamic theming active on Android 12+).
2. Download `Laps-v1.0.2.apk` from the Assets below.
3. Open the file on your Android device to install or update.
4. If updating from a previous version, your settings and cached widgets will carry over automatically.
