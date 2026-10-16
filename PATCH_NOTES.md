# Maker-Checker hardening patch

Base branch: `feature/ai-health-sqlite`
Base commit inspected: `56b147aba88e95ab33c49bd68a81fcc2cd3d8583`
Source file at base matched GitHub blob SHA: `12619f5990ccfe3e233484c8e789dd7c9ec7033b`

## Changes
- Prevent direct journal posting from bypassing a configured Maker-Checker threshold or an existing pending request.
- Prevent direct journal, payment, and expense reversals from bypassing their configured thresholds/pending requests.
- Keep the actual mutation inside the existing SQLite transaction; approval execution uses private core methods, not public bypassable entry points.
- Enforce active user's station scope for user-initiated posting/reversal while preserving existing system/internal calls that intentionally pass user ID `0`.
- Revalidate the pending approval target's current state and amount at approval time; changed journal amounts cannot be posted under a stale approval.
- Extend `ApprovalControlRobolectricTest.kt` for bypass prevention, stale amount rollback, and using the approval queue to post before reversal.

## Validation performed
- `node journal-station-scope-contract-test.js` — PASS.
- `python finance-accounting-integrity-regression-test.py` — PASS.
- Android Robolectric test execution was attempted with `:app:testDebugUnitTest --tests com.aistudio.dieselstationsms.kxmpzq.ApprovalControlRobolectricTest`; it could not start because the environment cannot resolve `services.gradle.org` to download Gradle 8.11.1. No Android test/build result is claimed.

## Application
The ZIP contains the two full replacement files at their repository-relative paths plus a unified patch. Review and apply to `feature/ai-health-sqlite`; do not apply to another branch.
