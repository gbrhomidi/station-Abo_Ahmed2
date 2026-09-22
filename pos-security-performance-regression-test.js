const assert = require('assert');
const fs = require('fs');
const path = require('path');

const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8');
const pos = read('app/src/main/assets/screens/pos.html');
const activity = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const database = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');
const manifest = read('app/src/main/AndroidManifest.xml');
const smsConversation = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/SmsConversationManager.kt');

function mustContain(source, value, message) { assert(source.includes(value), message); }

// Scanner lifecycle: a completed sale or a page transition must return camera/decoder resources.
mustContain(pos, 'let scannerStopPromise = null;', 'يجب تتبع الإيقاف غير المتزامن للماسح');
mustContain(pos, 'window.addEventListener(\'pagehide\', function () { stopScanner(); });', 'يجب إيقاف الكاميرا عند مغادرة POS');
mustContain(pos, 'if (html5Qrcode === instance) html5Qrcode = null;', 'يجب تحرير مثيل قارئ الباركود بعد الإيقاف');
mustContain(pos, 'stopScanner();\n    generateInvoiceNumber();', 'لا يجوز بقاء الكاميرا نشطة بعد إعادة ضبط الفاتورة');
mustContain(pos, "Array.from(document.querySelectorAll('#productsTableBody tr'))", 'يجب عدم إدخال الباركود الخام في محدد CSS');
mustContain(pos, 'function escapePosText(value)', 'يجب تهريب اسم المنتج القادم من SQLite قبل عرضه');
assert(!pos.includes('onclick="showProductDetailsCanvas(${JSON.stringify(product)'), 'لا يجوز حقن JSON المنتج في سمة onclick');

// SQLite performance: POS lookups retain the unique stock-level key and the established movement filter index.
mustContain(database, 'UNIQUE(product_id, warehouse_id)', 'قيد رصيد المنتج/المستودع الفريد غير موجود');
mustContain(database, 'idx_inventory_movements_station_product ON inventory_movements(station_id, product_id, warehouse_id, is_deleted)', 'فهرس حركات المحطة/المنتج/المستودع غير موجود');

// Local bridge is restricted to assets and the app denies backup, cleartext, content URI access, and untrusted navigation.
mustContain(activity, 'allowContentAccess = false', 'لا يجوز منح WebView وصول content URI غير مطلوب');
mustContain(activity, 'allowUniversalAccessFromFileURLs = false', 'لا يجوز منح ملف HTML وصولاً عاماً إلى الأصول الخارجية');
mustContain(activity, 'mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW', 'يجب منع المحتوى المختلط');
mustContain(activity, 'safeBrowsingEnabled = true', 'يجب تفعيل التصفح الآمن صراحةً');
mustContain(activity, 'if (!isTrustedAssetUrl(url))', 'التنقل غير الموثوق يجب أن يُحجب');
mustContain(manifest, 'android:allowBackup="false"', 'لا يجوز تضمين قاعدة بيانات العملاء في نسخ Android الاحتياطية');
mustContain(manifest, 'android:usesCleartextTraffic="false"', 'يجب منع الاتصال النصي غير المشفر');
assert(!activity.includes('DebugLogger.info("Whitelist", "Added $phone")'), 'لا يجوز تسجيل رقم العميل كاملاً عند إضافته للقائمة البيضاء');
assert(!activity.includes('DebugLogger.info("Whitelist", "Removed $phone")'), 'لا يجوز تسجيل رقم العميل كاملاً عند حذفه من القائمة البيضاء');
mustContain(activity, 'maskPhoneForLog(phone)', 'يجب إخفاء الهاتف في سجل القائمة البيضاء');
mustContain(smsConversation, 'private fun maskPhoneForLog(phone: String)', 'يجب توفير إخفاء الهاتف لسجلات مسودات الرسائل');
assert(!smsConversation.includes('for phone=$phone'), 'لا يجوز تسجيل رقم الهاتف كاملاً عند فشل مسودة رسالة');
mustContain(smsConversation, 'for phone=${maskPhoneForLog(phone)}', 'يجب إخفاء الهاتف في سجلات مسودات الرسائل');

console.log('POS performance and local security regression: PASS');
