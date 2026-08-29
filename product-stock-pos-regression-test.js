const fs = require('fs');
const path = require('path');

const root = __dirname;
const read = (...parts) => fs.readFileSync(path.join(root, ...parts), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

const products = read('app/src/main/assets/screens/products.html');
const pos = read('app/src/main/assets/screens/pos.html');
const bridge = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');

expect(products.includes("AndroidInterface.getProductPage(JSON.stringify(p))"), 'بطاقات المنتجات يجب أن تطلب ملخصاً حقيقياً من SQLite');
expect(products.includes('loadStats(page.summary || {})'), 'بطاقات المنتجات يجب أن تعرض ملخص صفحة SQLite');
expect(!products.includes('id="current_stock" name="current_stock" placeholder="المخزون الحالي" readonly'), 'حقل المخزون الحالي لا يجوز أن يبقى للقراءة فقط');
expect(products.includes("AndroidInterface.adjustProductStock(Number(p.product_id || 0), Number(p.target_quantity))"), 'تعديل المخزون يجب أن يعبر جسر التسوية الموثق');
expect(products.includes("delete payload.quantity"), 'تعديل المنتج لا يجوز أن يكتب رصيد المخزون مباشرة');
expect(bridge.includes('fun adjustProductStock(productId: Long, targetQuantity: Double): String'), 'جسر تسوية المخزون غير موجود');
expect(db.includes('fun adjustProductStock(productId: Long, targetQuantity: Double, stationScopeId: Int, userId: Long)'), 'تسوية المخزون يجب أن تكون ضمن SQLite وسياق المحطة');
expect(db.includes('put("movement_type", "adjustment")'), 'تعديل المخزون يجب أن ينشئ حركة تسوية قابلة للتدقيق');
expect(db.includes('getOrCreateDefaultWarehouse(db, stationScopeId)'), 'POS يجب أن يضمن مستودعاً تشغيلياً للمحطة');
expect(db.includes('ensureInventoryBaselineForProduct(db, productId, warehouseId, stationScopeId)'), 'POS يجب أن يهيئ رصيد المنتج قبل خصمه');
expect(db.includes('put("quantity", productTotal)'), 'كل حركة مخزون يجب أن تزامن الرصيد المعروض للمنتج');
expect(pos.includes('idempotency_key: activeSaleOperationKey'), 'POS يجب أن يرسل مفتاح idempotency لترحيل الفاتورة');
expect(pos.includes('renderReceiptForPrint(response.data || response)'), 'طباعة POS يجب أن تبني الإيصال من فاتورة SQLite المسترجعة');
expect(pos.includes("apiCall('retrieveInvoice', { invoice_number: invoiceNumber })"), 'طباعة الإيصال يجب أن تقرأ الفاتورة من SQLite');
expect(pos.includes('async function waitForTorchCapabilities()'), 'فلاش POS يجب أن ينتظر مسار الكاميرا بعد بدء الماسح');
expect(pos.includes('file:///android_asset/assets-local/js/html5-qrcode.min.js'), 'ماسح POS يجب أن يحمل مكتبة html5-qrcode المحلية فقط');
expect(pos.includes('function barcodeFormats()'), 'إعداد الماسح يجب أن يقيد القراءة بصيغ باركود مدعومة');
expect(pos.includes('formatsToSupport = formats'), 'إعداد الماسح يجب أن يمرر صيغ الباركود إلى html5-qrcode');
expect(pos.includes("{ facingMode: { exact: 'environment' } }, { facingMode: 'environment' }"), 'ماسح POS يجب أن يطلب الكاميرا الخلفية أولاً ثم يطبق تراجعاً متوافقاً');
expect(pos.includes('let scannerStopPromise = null;'), 'يجب تتبع إيقاف الماسح لمنع إنشاء جلسات كاميرا متزامنة');
expect(pos.includes('if (scannerStopPromise) {\n        return scannerStopPromise.then(function () { return initBarcodeScanner(); });\n    }'), 'إعادة تشغيل الماسح يجب أن تنتظر تحرير جلسة الكاميرا السابقة');
expect(pos.includes('if (html5Qrcode === instance) html5Qrcode = null;'), 'إيقاف الماسح يجب أن يحرر مثيل html5-qrcode بعد وقف الكاميرا');
expect(pos.includes("const scannerInstance = html5Qrcode || new Html5Qrcode('qr-reader', { verbose: false });"), 'يجب أن تحتفظ جلسة بدء الماسح بمثيل محلي لتجنب سباق الإيقاف');
expect(pos.includes('await Promise.resolve(scannerInstance.stop()).catch(function () {});'), 'إلغاء أو فشل بدء الماسح يجب أن يحرر الكاميرا والمثيل المحلي');
expect(pos.includes('stopScanner();\n    generateInvoiceNumber();'), 'إعادة ضبط فاتورة POS يجب أن توقف الكاميرا ومحرك فك الباركود');
expect(pos.includes("Array.from(document.querySelectorAll('#productsTableBody tr'))"), 'لا يجوز استخدام الباركود الخام داخل محدد CSS');
expect(pos.includes('function escapePosText(value)'), 'يجب تهريب بيانات المنتج قبل عرضها في POS');
expect(!pos.includes('onclick="showProductDetailsCanvas(${JSON.stringify(product)'), 'لا يجوز تمرير بيانات المنتج إلى معالج HTML مضمن');
expect(pos.includes('function openCreditCustomerModal()'), 'البيع الآجل يجب أن يعرض مودال اختيار العميل');
expect(pos.includes('openCreditCustomerModal();'), 'تغيير نوع الدفع إلى آجل يجب أن يفتح مودال العميل');
expect(pos.includes("getElementById('openCreditCustomerModalBtn').addEventListener('click', openCreditCustomerModal)"), 'يجب إتاحة فتح مودال العميل مجدداً من واجهة البيع الآجل');
expect(pos.includes('function renderCreditCustomerResults(query = \'\')'), 'مودال العميل يجب أن يعرض نتائج عملاء SQLite الفعلية');
expect(pos.includes("showToast('الفلاش غير متاح في جلسة الكاميرا الحالية؛ يمكنك متابعة المسح أو استخدام إضاءة خارجية', 'warning')"), 'غياب الفلاش لا يجوز أن يعطل ماسح الباركود');
const inlineScripts = [...pos.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
expect(inlineScripts.length > 0, 'POS يجب أن يحتوي منطق تهيئة الماسح');
inlineScripts.forEach((script, index) => {
    try { new Function(script); } catch (error) { throw new Error(`خطأ JavaScript في POS، كتلة ${index + 1}: ${error.message}`); }
});
console.log('Product stock and POS integration regression PASS.');
