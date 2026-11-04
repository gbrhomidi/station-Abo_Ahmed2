const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

(async function runAcceptance() {
  const runtimePath = path.join(__dirname, 'app/src/main/assets/assets-local/js/export-runtime.js');
  const runtimeSource = fs.readFileSync(runtimePath, 'utf8');
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    runScripts: 'outside-only',
    url: 'https://station.test/'
  });
  const { window } = dom;
  window.Blob = Blob;
  window.btoa = btoa;
  window.URL.createObjectURL = () => 'blob:acceptance-test';
  window.URL.revokeObjectURL = () => {};
  window.eval(runtimeSource);

  const runtime = window.StationExportRuntime;
  assert(runtime, 'export runtime should initialize');

  runtime.openPrint({ filename: 'acceptance-report', nativePrint: () => JSON.stringify({ success: true }) });
  let overlay = window.document.querySelector('.station-export-backdrop');
  assert(overlay && overlay.classList.contains('show'), 'print sheet should open before submission');
  window.document.querySelector('.station-export-method').click();
  assert(!overlay.classList.contains('show'), 'successful Android print launch must synchronously dismiss the overlay');

  // Returning from Android Print UI must leave the web page available for another action.
  runtime.openPrint({ filename: 'acceptance-report-2', nativePrint: () => JSON.stringify({ success: true }) });
  overlay = window.document.querySelector('.station-export-backdrop');
  assert(overlay.classList.contains('show'), 'a subsequent print request must remain interactive');
  window.document.querySelector('.station-export-method').click();
  assert(!overlay.classList.contains('show'), 'subsequent print request must also dismiss its overlay');

  const exportBlob = {
    type: 'text/csv',
    arrayBuffer: () => Promise.resolve(new Uint8Array([97, 44, 98]).buffer),
    text: () => Promise.resolve('a,b')
  };
  let capturedBase64 = null;
  let resolveLargeSave = null;
  window.AndroidInterface = {
    chooseExportFolder: (_filename, _mime, base64) => {
      capturedBase64 = base64;
      window.onExportFolderResult({ success: true, path: '/documents/acceptance.csv' });
      if (resolveLargeSave) resolveLargeSave();
      return JSON.stringify({ success: true, status: 'picker_opened' });
    }
  };
  runtime.openPreparedExport({ filename: 'acceptance.csv', mime: 'text/csv', blob: exportBlob, formats: ['csv'] });
  window.document.querySelector('.station-export-method').click();
  await new Promise(resolve => setTimeout(resolve, 0));
  overlay = window.document.querySelector('.station-export-backdrop');
  assert(!overlay.classList.contains('show'), 'successful Android folder export callback must synchronously dismiss its overlay');

  const activitySource = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
  const resultHandler = activitySource.indexOf('private fun handleLaunchedActivityResult');
  const exportHandlerStart = activitySource.indexOf('if (requestCode == exportFolderPickerRequestCode)', resultHandler);
  const exportHandlerEnd = activitySource.indexOf('if (requestCode == databaseBackupFolderPickerRequestCode)', exportHandlerStart);
  const exportHandler = activitySource.slice(exportHandlerStart, exportHandlerEnd);
  const ioStart = exportHandler.indexOf('withContext(Dispatchers.IO)');
  const decodeAt = exportHandler.indexOf('Base64InputStream(');
  const writeAt = exportHandler.indexOf('openOutputStream(target');
  assert(exportHandler.includes('lifecycleScope.launch'), 'SAF persistence must not run in the ActivityResult callback body');
  assert(ioStart >= 0 && decodeAt > ioStart && writeAt > ioStart, 'streaming Base64 decoding and document writing must execute on Dispatchers.IO');
  assert(exportHandler.includes('Base64InputStream') && exportHandler.includes('ByteArray(64 * 1024)'), 'native decode must use a bounded streaming buffer');
  assert(exportHandler.includes('AsciiStringInputStream(encoded)'), 'native decoder must stream from the bridge String without an intermediate full-size byte array');
  assert(!exportHandler.includes('Base64.decode(encoded'), 'native SAF export must not allocate a full decoded byte array');
  assert(!exportHandler.includes('encoded.byteInputStream'), 'native SAF export must not duplicate the full Base64 payload as bytes');
  assert(exportHandler.includes('phase=completed bytes=') && exportHandler.includes('elapsed_ms='), 'export completion logs must include safe size/timing diagnostics');
  assert(exportHandler.includes('phase=failed exception=') && !exportHandler.includes('DebugLogger.logException("ExportFolderPicker"'), 'SAF diagnostics must not log raw provider exceptions or possible paths');
  assert(activitySource.includes('phase=launch_timeout elapsed_ms=') && activitySource.includes('phase=launch_completed elapsed_ms='), 'print launch must log latency and timeout outcomes');
  assert(activitySource.includes('private val exportJobs = Collections.synchronizedSet'), 'export jobs must have lifecycle tracking independent of backgroundJob');
  for (const method of ['exportOperationalCsv', 'exportToCSV', 'exportAllData']) {
    const start = activitySource.indexOf(`fun ${method}(`);
    const end = activitySource.indexOf('@JavascriptInterface', start + 1);
    const body = activitySource.slice(start, end < 0 ? undefined : end);
    assert(body.includes('activity.trackExportJob(job)'), `${method} must track its job separately`);
    assert(!body.includes('activity.backgroundJob?.cancel()'), `${method} must not cancel unrelated activity work`);
  }

  // Large-payload resource regression: arrayBuffer must not be used when Blob streams exist.
  const largeBytes = Buffer.alloc(8 * 1024 * 1024, 0x5a);
  const largeBlob = new Blob([largeBytes], { type: 'text/csv' });
  largeBlob.arrayBuffer = () => { throw new Error('streaming path should not materialize arrayBuffer'); };
  let resolveSaved;
  const saved = new Promise(resolve => { resolveSaved = resolve; });
  resolveLargeSave = resolveSaved;
  const rssBefore = process.memoryUsage().rss;
  const encodeStarted = process.hrtime.bigint();
  runtime.openPreparedExport({ filename: 'large-acceptance.csv', mime: 'text/csv', blob: largeBlob, formats: ['csv'] });
  window.document.querySelector('.station-export-method').click();
  await saved;
  const encodeMs = Number(process.hrtime.bigint() - encodeStarted) / 1e6;
  const rssDeltaMb = (process.memoryUsage().rss - rssBefore) / (1024 * 1024);
  assert(capturedBase64 && Buffer.from(capturedBase64, 'base64').equals(largeBytes), 'streaming export must preserve all bytes for a large Blob');
  assert(!window.document.querySelector('.station-export-backdrop').classList.contains('show'), 'large export callback must close the export overlay');

  console.log(`Android export/print lifecycle and large-payload resource regression PASS (bytes=${largeBytes.length}, encode_ms=${encodeMs.toFixed(1)}, host_rss_delta_mb=${rssDeltaMb.toFixed(1)})`);
  dom.window.close();
})().catch(error => { console.error(error); process.exitCode = 1; });
