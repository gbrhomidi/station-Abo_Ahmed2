const fs = require('fs');
const path = require('path');
const root = __dirname;
const read = (...parts) => fs.readFileSync(path.join(root, ...parts), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

const pos = read('app/src/main/assets/screens/pos.html');
const stockLevels = read('app/src/main/assets/screens/stock-levels.html');
const movements = read('app/src/main/assets/screens/inventory-movements.html');
const stocktake = read('app/src/main/assets/screens/stocktake.html');
const alerts = read('app/src/main/assets/screens/inventory-alerts.html');
const bridge = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const db = read('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt');

expect(pos.includes("apiCall('getCustomers', {})"), 'POS يجب أن يجلب العملاء الحقيقيين من الجسر');
expect(pos.includes('const isCredit = paymentType === \'آجل\';'), 'POS يجب أن يميز البيع الآجل بوضوح');
expect(pos.includes('entityId = Number(document.getElementById(\'customerId\').value || 0);'), 'البيع الآجل يجب أن يربط الفاتورة بالعميل المختار');
expect(pos.includes('const paid = isCredit ? 0'), 'البيع الآجل الكامل لا يجوز أن يطلب مبلغاً نقدياً وهمياً');
expect(db.includes("type_code IN ('INDIVIDUAL','COMPANY','GOVERNMENT','TRANSPORT','CONTRACTOR')"), 'SQLite يجب أن يقبل العملاء فقط للبيع الآجل');
expect(db.includes('يتجاوز البيع الآجل الحد الائتماني للعميل'), 'SQLite يجب أن يفرض الحد الائتماني عند البيع الآجل');
expect(db.includes('put("debit", outstandingAmount)'), 'دفتر العميل يجب أن يقيد الرصيد المستحق لا كامل فاتورة مدفوعة جزئياً');

expect(bridge.includes('fun ensureOperationalWarehouse(): String'), 'جسر تجهيز المستودع التشغيلي غير موجود');
expect(db.includes('fun ensureOperationalWarehouse(stationScopeId: Int): Long'), 'تجهيز المستودع يجب أن يبقى في SQLite وسياق المحطة');
expect(movements.includes("case 'ensureOperationalWarehouse'"), 'شاشة الحركات يجب أن تستدعي جسر المستودع الفعلي عند الحاجة');
expect(movements.includes('activeMovementFilters'), 'فلاتر حركات المخزون يجب أن تمرر إلى طلب SQLite');
expect(movements.includes('new URLSearchParams(window.location.search)'), 'مباشرة إعادة التعبئة من التنبيه يجب أن تفتح حركة إدخال فعلية');

expect(stockLevels.includes('AndroidInterface.getStockAlertRecords'), 'مستويات المخزون يجب أن تقرأ سجل التنبيهات الحقيقي');
expect(!stockLevels.includes('getLowStockitems'), 'يجب إزالة اسم الجسر الخاطئ getLowStockitems');
expect(stockLevels.includes("window.location.href = 'stocktake.html'"), 'زر تقرير الجرد يجب أن يفتح تدفق الجرد الحقيقي');
expect(stocktake.includes("call('saveStocktakeDetailRecord'"), 'واجهة الجرد يجب أن تحفظ عدّ المنتج في SQLite');
expect(stocktake.includes("call('resolveStocktakeRecord'"), 'واجهة الجرد يجب أن تعتمد الفروقات من خلال الجسر الحقيقي');
expect(bridge.includes('fun getStocktakeDetails(stocktakeId: Long): String'), 'جسر تفاصيل الجرد المتخصصة غير موجود');
expect(db.includes('fun getStocktakeDetails(stocktakeId: Long, stationScopeId: Int): JSONArray'), 'استعلام تفاصيل الجرد يجب أن يفرض نطاق المحطة');

expect(db.includes('private fun synchronizeLiveStockAlerts'), 'تنبيهات المخزون يجب أن تتزامن من الرصيد الحقيقي');
expect(db.includes('getStockAlertRecordsContract'), 'عقد سجل تنبيهات المخزون غير موجود');
expect(alerts.includes("call('getStockAlertRecords'"), 'واجهة التنبيهات يجب أن تطلب السجل الحقيقي');
expect(alerts.includes("resolveStockAlertRecord"), 'واجهة التنبيهات يجب أن تسجل المعالجة عبر الجسر');
expect(alerts.includes("inventory-movements.html?product_id="), 'معالجة التنبيه يجب أن تقود إلى حركة مخزنية حقيقية');

for (const [name, html] of [['الجرد', stocktake], ['التنبيهات', alerts]]) {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1]);
  expect(scripts.length > 0, `تفتقد شاشة ${name} إلى منطق WebView`);
  scripts.forEach((script, index) => {
    try { new Function(script); } catch (error) { throw new Error(`خطأ JavaScript في شاشة ${name}، كتلة ${index + 1}: ${error.message}`); }
  });
}

console.log('Credit, inventory, stocktake, and alert regression contract PASS.');
