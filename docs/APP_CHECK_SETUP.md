# Firebase App Check setup (free-first)

## Production Release

- Provider: Firebase App Check with reCAPTCHA Enterprise (Android score-based key).
- Package: `com.aistudio.dieselstationsms.kxmpzq`.
- Firebase Android BoM: 35.1.0 (documented minimum for this provider: 34.17.0).
- The Android reCAPTCHA provider is currently Preview and may change.
- In the Google Cloud project linked to Firebase, enable the reCAPTCHA Enterprise API and create an Android score-based key. Keep package-name verification enabled and enable support for distribution outside Google Play because this app is distributed directly/sideloaded.
- Register that key in Firebase Console → App Check → Apps for this Android app.
- The supplied public Android site key is configured as the Release build default. It is a public client identifier, not a secret.
  - A different key can override it with Gradle property `-PRECAPTCHA_ENTERPRISE_SITE_KEY=...` or environment variable `RECAPTCHA_ENTERPRISE_SITE_KEY`.
  - The key must also be registered against this Android app in Firebase Console → App Check. Source-code configuration alone does not register it with Firebase.
- Do not enable App Check enforcement until real-device token acquisition and backend verification are tested.
- reCAPTCHA Enterprise has a no-cost quota of 10,000 assessments/month; usage above the quota can incur charges if billing is enabled. Keep the project on the no-cost plan and do not link billing without explicit approval. Quota exhaustion can interrupt optional cloud requests, not local SQLite workflows.

## Debug and CI

- Debug builds select Firebase App Check Debug Provider automatically.
- Run the Debug build, obtain its debug token from Logcat, and register it under Firebase Console → App Check → Manage debug tokens.
- Keep debug tokens private. Never commit them to this repository, put them in an APK, or distribute a Debug-provider build as production.
- If CI needs to call a protected backend, store its debug token only in GitHub Actions encrypted secrets. Do not print it in logs.

## Security boundary

The Debug provider deliberately bypasses device/app attestation. A registered debug token is a bearer credential. Shipping this provider in a public Release APK would let anyone extract/use the bypass and defeat App Check. Therefore this project compiles the Debug provider only into the Debug variant. Release never falls back to Debug if reCAPTCHA is missing or unavailable.

Cloud Run verifies App Check tokens server-side using Firebase Admin SDK and rejects missing/invalid tokens. A client-side provider alone does not secure a custom backend.

## Offline guarantee

App Check initialization is best-effort and must not block app startup. SQLite, sales, inventory, accounting, SMS, and local station workflows remain available without Firebase, network access, or a reCAPTCHA key.
