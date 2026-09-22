const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const posSource = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/pos.html'), 'utf8');
const lifecycleStart = posSource.indexOf('let html5Qrcode;');
const lifecycleEnd = posSource.indexOf('// ============================================================\n// 6. دوال المنتجات والجدول');
assert(lifecycleStart >= 0 && lifecycleEnd > lifecycleStart, 'تعذر استخراج منطق دورة ماسح POS من المصدر');
const lifecycleSource = posSource.slice(lifecycleStart, lifecycleEnd);

const flush = () => new Promise(resolve => setImmediate(resolve));

function createHarness() {
    const dom = new JSDOM(`<!doctype html><html><body>
        <div id="qr-reader"></div><button id="stopScanBtn"></button><button id="toggleScanBtn"></button><button id="toggleFlashBtn"></button>
    </body></html>`, { runScripts: 'outside-only', url: 'https://pos.test/' });
    const { window } = dom;
    const instances = [];
    let startImplementation = () => Promise.resolve();

    class MockHtml5Qrcode {
        constructor(elementId) {
            this.elementId = elementId;
            this.startCalls = 0;
            this.stopCalls = 0;
            instances.push(this);
        }
        static getCameras() { return Promise.resolve([]); }
        start() { this.startCalls += 1; return startImplementation(this); }
        stop() { this.stopCalls += 1; return Promise.resolve(); }
    }

    window.Audio = class { play() { return Promise.resolve(); } };
    window.Html5Qrcode = MockHtml5Qrcode;
    window.Html5QrcodeSupportedFormats = { CODE_128: 1, CODE_39: 2, EAN_13: 3, EAN_8: 4, UPC_A: 5, UPC_E: 6, QR_CODE: 7 };
    window.setTimeout = () => 0;
    window.console = console;
    window.eval(lifecycleSource);
    return {
        window,
        instances,
        setStartImplementation: implementation => { startImplementation = implementation; },
    };
}

async function testStartStopStart() {
    const harness = createHarness();
    const { window, instances } = harness;
    await window.initBarcodeScanner();
    assert.strictEqual(instances.length, 1, 'يجب إنشاء مثيل واحد عند بدء الماسح');
    assert.strictEqual(instances[0].startCalls, 1, 'يجب بدء المثيل الأول مرة واحدة');
    assert.strictEqual(window.document.getElementById('stopScanBtn').style.display, 'block', 'يجب إظهار زر الإيقاف بعد بدء الكاميرا');

    const firstStop = window.stopScanner();
    const repeatedStop = window.stopScanner();
    assert.strictEqual(firstStop, repeatedStop, 'الإيقاف المتكرر يجب أن يشترك في الوعد نفسه');
    await firstStop;
    assert.strictEqual(instances[0].stopCalls, 1, 'يجب إيقاف المثيل الأول مرة واحدة');
    assert.strictEqual(window.document.getElementById('stopScanBtn').style.display, 'none', 'يجب إخفاء زر الإيقاف بعد تحرير الكاميرا');

    await window.initBarcodeScanner();
    assert.strictEqual(instances.length, 2, 'يجب إنشاء مثيل جديد فقط بعد تحرير السابق');
    assert.strictEqual(instances[1].startCalls, 1, 'يجب بدء المثيل الجديد مرة واحدة');
    await window.stopScanner();
    assert.strictEqual(instances[1].stopCalls, 1, 'يجب تحرير مثيل إعادة البدء');
}

async function testStopDuringPendingStart() {
    const harness = createHarness();
    const { window, instances, setStartImplementation } = harness;
    let resolveStart;
    setStartImplementation(() => new Promise(resolve => { resolveStart = resolve; }));
    const pendingStart = window.initBarcodeScanner();
    await flush();
    assert.strictEqual(instances.length, 1, 'يجب عدم إنشاء أكثر من مثيل أثناء بدء معلق');

    const firstStop = window.stopScanner();
    const repeatedStop = window.stopScanner();
    assert.strictEqual(firstStop, repeatedStop, 'إيقاف البدء المعلق لا يجوز أن ينشئ وعد إيقاف ثانياً');
    await firstStop;
    resolveStart();
    await pendingStart;

    assert.strictEqual(instances[0].stopCalls, 1, 'إلغاء البدء يجب ألا يستدعي إيقاف المثيل المحرر مرة ثانية');
    assert.strictEqual(window.document.getElementById('stopScanBtn').style.display, 'none', 'إلغاء البدء يجب أن يعيد الواجهة إلى حالة غير نشطة');
}

async function main() {
    await testStartStopStart();
    await testStopDuringPendingStart();
    assert(posSource.includes("window.addEventListener('pagehide', function () { stopScanner(); });"), 'يجب إيقاف الماسح عند pagehide');
    assert(posSource.includes('stopScanner();\n    generateInvoiceNumber();'), 'إعادة ضبط الفاتورة يجب أن تطلب تحرير الماسح');
    console.log('POS scanner lifecycle runtime regression: PASS');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
