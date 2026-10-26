# Laps v1.1.0 — Material Expressive Dashboard & Studio Release

We are excited to release **Laps v1.1.0**, a milestone update that brings Google's Material 3 Expressive design language to life across the entire app. This release introduces an interactive daily breakdown hero, fluid screen transitions, a live interactive Widgets Studio, gesture-driven horizontal tab navigation in the Plan Race Hub, bespoke dock branding, and refined Material 3 dropdown menus.

---

### What's New in v1.1.0

* **Material 3 Expressive Training Hub Dashboard**:
  * **Interactive 7-Day Daily Breakdown**: Visual bar chart featuring tactile spring physics (`dampingRatio = 0.72f`, `stiffness = 450f`). Tap any day of the week to smoothly expand session details, completed/planned distances, and workout type badges.
  * **Pulsating Live Sync Aura**: Micro-animated glowing aura indicator on the sync status pill when syncing with COROS Training Hub.
  * **Dynamic Metric Pods**: Sculptural cards with high-contrast colored indicators for Acute Load (Productive/Optimal), Threshold Pace, and Weekly Target Progress.
  * **Spring-Animated Weekly Progress Bar**: Smoothly animates progress toward your weekly volume target.

* **Fluid App Navigation & Shared Transitions**:
  * Seamless `AnimatedContent` navigation with expressive slide-in and fade physics between the Training Hub, Widgets Studio, Plan Race Hub, Run Detail, Settings, and Setup Wizard.
  * Synchronized bottom navigation bar transitions.

* **Reimagined Widgets Studio**:
  * **Live Dual-Theme Mode Switcher**: Preview all 12 widgets instantly in **Auto**, **Light**, or **Dark** mode directly inside the Studio before placing them on your home screen.
  * **Tactile Spring Physics**: Spring scale animation (`0.95f` scale on tap) for an authentic haptic feel when browsing widgets.
  * **Active Style Indicator**: Clear `ACTIVE` badge with checkmark highlighting your currently pinned home screen widget configuration.

* **Swipeable Plan Race Hub**:
  * Replaced static tab switching with a high-performance `HorizontalPager`. Users can swipe fluidly between **Schedule**, **Race Strategy**, and **Customizer**, fully synchronized with the tab row and the floating action button.
  * Added bottom clearance padding (`96.dp`) on both the Schedule and Race Strategy tabs so the Extended Floating Action Button never obscures race-day fueling notes or final week workouts.

* **Material 3 Region & Calendar Dropdowns**:
  * Replaced cramped, text-wrapping server region chips with an elevated Material 3 `OutlinedCard` Dropdown menu with active checkmark indicators for Global/US, Europe, Singapore/Asia, and China.

* **Bespoke Laps Dock Branding**:
  * Replaced the generic trending line icon in the bottom dock with the official **Laps running track oval and pacer dot**, featuring layered Material 3 alpha depth.

---

### Integrity & Verification

| Asset | File | SHA-256 Checksum |
| :--- | :--- | :--- |
| **Release APK** | `Laps-v1.1.0.apk` | `0d2c2f91a8d8774d0f1d1478b1e2acb888115dfd49dcfc44d6ceef7b04e1266f` |

To verify file integrity:
```bash
shasum -a 256 Laps-v1.1.0.apk
```

---

### Requirements & Installation

1. Requires **Android 8.0 (API 26)** or higher (Material You dynamic theming active on Android 12+).
2. Download `Laps-v1.1.0.apk` from the Assets below.
3. Open the file on your Android device to install or update.
4. All existing COROS logins, cached EvoLab data, and calendar sync settings carry over seamlessly.
