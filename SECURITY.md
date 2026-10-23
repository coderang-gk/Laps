# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Threat Model & Architecture

Laps is engineered to run **100% on-device** without any cloud servers, relays, or analytics trackers.

1. **Authentication Options:**
   - **COROS OAuth 2.0 / MCP Token (Recommended):** The athlete connects using standard OAuth 2.0 Bearer tokens. Laps never sees, stores, or handles the user's password.
   - **Direct HTTPS Sync (Legacy):** Laps communicates directly with `teamapi.coros.com` over HTTPS. Passwords and tokens are stored in Android private app sandboxing (`Context.MODE_PRIVATE`).
2. **Local Sandboxing:**
   - On uncompromised Android devices, data stored in private app storage is accessible only by the application's unique Linux UID assigned by the OS.
   - No local storage scheme is completely impenetrable against compromised (rooted) devices with superuser malware. For maximum security, we recommend using the **COROS OAuth 2.0 Bearer token** path.
3. **Zero Telemetry:**
   - Laps contains zero advertising SDKs, analytics tracking (e.g., Firebase, Google Analytics), or crash telemetry relays. No fitness data leaves your device except direct API queries to COROS.

## Reporting a Vulnerability

If you discover a potential security vulnerability in Laps, please report it privately:

- **Email:** `coderang.gk@gmail.com`
- **Subject:** `[SECURITY] Laps Vulnerability Report`

Please include:
- A description of the issue and potential impact.
- Steps to reproduce or a proof of concept.
- Affected device manufacturer and Android OS versions.

We will acknowledge receipt within 48 hours and work with you to patch and verify the fix before any public disclosure.
