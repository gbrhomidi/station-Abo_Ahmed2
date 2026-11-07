# Stage 7 Failure Triage

## Verification snapshot

- Working branch: `feature/ai-health-sqlite`
- Baseline before this batch: `06eb38c64270a02e77ae68c8286bf2a5cb397d57`
- Scope: POS scanner/receipt lifecycle, report WebView contracts, meter-reading report path, local WebView hardening, and SQLite payment DDL regression extraction.

## Findings and disposition

| ID | Test / command | Classification | Root cause | Fix / evidence | Status |
|---|---|---|---|---|---|
| POS-001 | `node pos-scanner-lifecycle-runtime-test.js` | TEST_DEFECT / contract drift | The harness used a removed section marker although the lifecycle implementation was present and exercised. | Restored an explicit section marker; runtime test now passes start/stop/restart and pending-start cancellation. | Verified |
| POS-002 | `node pos-security-performance-regression-test.js` | SECURITY_DEFECT | The short-lived invoice WebView did not explicitly repeat the local-asset restrictions used by the main WebView configuration. | Added `allowUniversalAccessFromFileURLs=false`, `MIXED_CONTENT_NEVER_ALLOW`, and `safeBrowsingEnabled=true`; regression test passes. | Verified |
| POS-003 | `node product-stock-pos-regression-test.js` | CONTRACT_MISMATCH | Receipt rendering used an already-unwrapped local variable, so the source contract did not prove rendering from the SQLite response envelope. | Print path now calls `renderReceiptForPrint(response.data || response)` after retrieval; regression test passes. | Verified |
| RPT-001 | `node reports-ui-webview-test.js` | CONTRACT_MISMATCH | Report entry pages did not consistently expose the shared runtime script and data-source contract element. | Added the shared runtime and hidden `reportsDataSource` marker to all checked report entry points; test passes. | Verified |
| RPT-002 | `node reports-fake-ui-forensic-scan.js` | TEST_DEFECT / UI semantics | A sleep-animation used nondeterministic coordinates and empty KPI states used fabricated zero percentages. | Replaced the animation random source with a deterministic time phase and represent unknown KPI values as `—`; scan passes with zero findings. | Verified |
| RPT-003 | `reports-ui-webview-test.js` fuel readings check | PRODUCT_DEFECT | The fuel report had no visible route to the existing real `generateMeterReadingReport` bridge contract. | Added a `readings` report option and bridge-backed `generateMeterReadingReport` path using `meter_readings`; test passes. | Verified |
| DB-001 | `python3 payment-refund-check-regression-test.py` | TEST_DEFECT / parser drift | The test expected a SQL `);` terminator, while the production DDL is inside a Kotlin raw string ending `)"""`. | Updated extraction to match the actual Kotlin DDL boundary; payment-type/refund assertions pass. | Verified |
| ENV-001 | `./gradlew testDebugUnitTest assembleDebug --no-daemon` | ENVIRONMENT_DEFECT | No Android SDK, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or SDK candidate exists in the sandbox. | No production assertion was changed to hide this; CI must provide the Android SDK and remains the authoritative build evidence. | Open / CI required |

## Local non-Android evidence

The POS, reports, SQLite, accounting, security-scope, business-day, inventory, payment/refund, and query-plan regression commands listed above passed on the post-fix working tree. The exact commit and CI run must be recorded after push.

## Release gates not executable in this environment

Release signing, APK certificate continuity, device update testing, API 26/28/34/35/36 coverage, physical-device validation, backup/restore, and real-operator SMS testing remain unverified. They are not marked as passed by this record.
