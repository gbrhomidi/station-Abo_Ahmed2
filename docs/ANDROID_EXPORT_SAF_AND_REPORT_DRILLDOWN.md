# Android Export/SAF, Resource Checks, and Report Drilldown

## Scope and evidence

This note describes the Android/WebView export and print paths, the current report source-trace contract, and the checks run for the changes. It distinguishes repository-level evidence from production telemetry.

**Crashlytics evidence is not available from this checkout/session.** The Android app module does not apply the Google Services or Firebase Crashlytics Gradle plugin, does not declare the Crashlytics runtime, and this checkout contains no Firebase app configuration file. The session has no enabled Firebase/Crashlytics connector. A Firebase BOM version entry in the version catalog alone does not enable Crashlytics. Consequently, no claim is made here that production Crashlytics has zero SAF or I/O exceptions. `DebugLogger` writes to Android Logcat and, in developer mode, the in-app console; those are not Crashlytics logs.

To complete a production-log review, provide an authorized Crashlytics Issues/Events export for the target app and release (including occurrence time, stack trace, device/OS and event breadcrumbs), or enable an authorized Firebase access path. Redact user records, exported file contents, tokens, and other sensitive data before sharing.

## SAF export pipeline

1. The WebView export runtime accepts a `Blob`. When `Blob.stream()` is supported, it encodes chunks whose byte lengths are aligned to Base64 triplets. Only a bounded chunk is converted to a temporary binary string at a time; the final Base64 string remains necessary for the existing JavaScript bridge API. Older WebViews use the `arrayBuffer()` fallback.
2. `chooseExportFolder` opens the Android Storage Access Framework directory picker. The selected URI and Base64 payload are held only until the picker result is handled; they are then cleared from the pending fields.
3. On a successful picker result, the app runs document creation, Base64 decoding, and output writes on `Dispatchers.IO`, not on the UI thread. `AsciiStringInputStream` exposes the ASCII bridge string without first copying the entire Base64 payload into another byte array. Android `Base64InputStream` decodes into a fixed 64 KiB buffer and writes each block to the selected document.
4. The WebView callback is posted after the I/O operation completes. Picker cancellation and I/O failure return an explicit unsuccessful callback. Export jobs are tracked separately from the Activity's unrelated `backgroundJob`; Activity destruction cancels tracked jobs.

No export content is intentionally written to diagnostics. A successful SAF write logs its phase, decoded byte count, and elapsed milliseconds. Print launch logs elapsed milliseconds and whether the launch acknowledgment succeeded; timeout and interrupted-wait outcomes are logged separately. The document title, selected path, and file bytes are not included in the success/performance diagnostics. I/O failures are reported to the user callback and logged by exception category according to the code path; these local logs are not a substitute for Crashlytics.

## Report Source Trace v2

`SourceTraceContract.VERSION` is **2**. A source request carries `source_table`, positive `source_id`, `reference_code`, `station_id`, `document_type`, `date_scope` (`from_date`/`to_date`), and optional `report_context`.

The web page validates the input shape, but this is not the trust boundary. `AndroidInterface.openReportOperationalDocument` resolves the authenticated user's current station and delegates to `DatabaseHelper.resolveSourceTraceContract`. SQLite verifies that the source exists, belongs to the current station, and matches the supplied reference and document type; date bounds are also checked. Supported source tables and their fixed destination screens are listed in that resolver. The page uses the returned `operational_screen`, checks it against a client-side allow-list, and passes the verified identity/context to the operational screen. Never replace this with an operational ID-only deep link or trust a destination supplied by the caller.

The current regression test is `reports-drilldown-regression-test.js`. It checks the actual v2 bridge/resolver contract, station/source validation, destination allow-listing, and identity/date-scope handoff. It intentionally does not assert the removed `getReportDrilldown` groups/transactions API.

## Resource/performance regression checks

Run the host-side acceptance test from the repository root:

```bash
node export-print-lifecycle-regression-test.js
```

It exercises the WebView export runtime with an 8 MiB Blob, disables that Blob's `arrayBuffer()` path so the stream path is required, checks that the exact payload survives Base64 round-trip, and prints elapsed conversion time plus a host-process RSS delta. It also asserts that native SAF decoding/writing is dispatched to I/O, uses `Base64InputStream` and a fixed buffer, avoids full decoded/Base64 byte-array copies, and that print launch latency outcomes are instrumented.

**Interpret the RSS number as a host-side regression signal only.** The test runs in Node/jsdom, not Android WebView or the Android SAF provider; its RSS includes test/runtime overhead, varies by runtime and garbage collection, and is not a measurement of Android PSS or Java heap. Android device validation still requires a debug APK, a representative device/emulator with Android SDK/ADB available, and Android Studio Memory Profiler or Perfetto. Profile repeated exports at representative sizes (for example 8, 32, and 64 MiB where supported), watching Java heap, native heap, PSS, UI-frame stalls, and provider write duration. Record device/API level and export size; do not include the exported data itself in traces.

Other relevant host-side checks:

```bash
node reports-drilldown-regression-test.js
node reports-backend-capability-test.js
node reports-unbounded-query-test.js
node reports-module-contract-test.js
node reports-script-syntax-test.js
node reports-production-verification.js
```

Android/Robolectric tests cannot be claimed as run unless the Android SDK is installed and Gradle test tasks complete successfully. In an environment without `ANDROID_HOME`/`adb`, report that limitation explicitly.
