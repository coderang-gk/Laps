# Laps v1.0.1 — Regional Server Selection & Training Hub Calendar Sync

## What's Changed in v1.0.1

### 🔒 Security & Privacy (#1)
- **Zero Cross-Region Fallback**: Completely removed the fallback loop that attempted login across regional clusters on auth failures. Direct credentials and Bearer tokens are strictly transmitted only to the user's explicitly selected server. (Thanks to @filiperocha!)
- **Explicit Region Selection**: Added a server selector (`Global / US`, `Europe`, `China`) in both the initial onboarding modal and Settings.
- **On-Login Regional Verification**: Authentications now immediately verify token validity against the selected regional cluster. If a European account logs into the Global cluster (or vice versa), the app immediately alerts the athlete to switch regions rather than failing silently with `1019`.

### 📅 Live COROS Training Hub Calendar
- **Direct Calendar Schedule Query**: Hooked up live schedule sync (`/training/schedule/query`), displaying real scheduled workouts from your COROS Training Hub plan.
- **Real Race Countdown & Target Plan**: Race countdown automatically extracts your plan's target race date (`endDay`) and title directly from COROS, removing the default mock half-marathon fallback.
- **Accurate Distance Scaling**: Normalized workout distance parsing for structured interval and recovery workouts.

### ⚡️ Physiological Metrics
- **Rolling 7-Day Acute Training Load**: Shifted acute load from calendar week (Mon–Sun) to a rolling 7-day window so Monday morning metrics don't reset to 0/Resting before your first run of the week.

---

### 📦 Installation
Download and install `Laps-v1.0.1.apk` below. If updating from v1.0.0, your widgets and settings will be preserved.
