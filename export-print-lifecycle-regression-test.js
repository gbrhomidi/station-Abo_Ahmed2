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
  window.AndroidInterface = {
    chooseExportFolder: () => {
      window.onExportFolderResult({ success: true, path: '/documents/acceptance.csv' });
      return JSON.stringify({ success: true, status: 'picker_opened' });
    }
  };
  runtime.openPreparedExport({ filename: 'acceptance.csv', mime: 'text/csv', blob: exportBlob, formats: ['csv'] });
  window.document.querySelector('.station-export-method').click();
  await new Promise(resolve => setTimeout(resolve, 0));
  overlay = window.document.querySelector('.station-export-backdrop');
  assert(!overlay.classList.contains('show'), 'successful Android folder export callback must synchronously dismiss its overlay');

  console.log('Android export/print lifecycle acceptance regression PASS');
  dom.window.close();
})().catch(error => { console.error(error); process.exitCode = 1; });
