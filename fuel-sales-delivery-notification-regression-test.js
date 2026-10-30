const fs = require('fs');

const fuelSales = fs.readFileSync('app/src/main/assets/screens/fuel-sales.html', 'utf8');
const deliveries = fs.readFileSync('app/src/main/assets/screens/deliveries.html', 'utf8');
const scheduler = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/DeliverySmsScheduler.kt', 'utf8');
const deliveryAccounting = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DeliveryFeeAccounting.kt', 'utf8');
const pumpSmsRepository = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/sms/PumpMeterSmsRepository.kt', 'utf8');
const mainActivity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

const check = (condition, message) => {
    if (!condition) throw new Error(message);
};

check(fuelSales.includes('<div class="toast form-toast" id="formToast" role="status"'),
    'رسالة النجاح يجب أن تكون داخل جسم مودال البيع');
check(fuelSales.includes("if (!isFormFeedback) node._timer = setTimeout"),
    'رسالة الحالة داخل المودال يجب أن تبقى متاحة للمراجعة');
check(fuelSales.includes("sms.queued") && fuelSales.includes("sms.customer_name || 'المحدد'") &&
      fuelSales.includes("sms.reason || sms.message || 'سبب غير معروف'"),
    'يجب عرض نتيجة جدولة SMS للعميل أو سبب تعذرها');
check(fuelSales.includes("'credit', 'آجل', 'credit_sale', 'credit_account'"),
    'يجب التعرّف على قيم البيع الآجل المدعومة عند عرض حالة SMS');

check(/id="f_delivery_service_fee"[^>]*readonly/.test(deliveries),
    'رسوم خدمة التوصيل يجب أن تكون للقراءة فقط');
check(/id="f_delivery_fee_mode"[^>]*disabled/.test(deliveries),
    'طريقة احتساب رسوم التوصيل يجب أن تكون غير قابلة للتعديل');
check(/id="f_location"[\s\S]{0,450}?readonly/.test(deliveries),
    'موقع التوصيل يجب أن يكون للقراءة فقط');
check(/id="f_cash_customer_name"[^>]*readonly/.test(deliveries),
    'اسم العميل النقدي يجب أن يكون للقراءة فقط');
check(/invoke\(\s*'getDeliverySaleContext',\s*\[querySaleId\]\s*\)/.test(deliveries) &&
      deliveries.includes("setIfValue('f_delivery_service_fee', sale.delivery_service_fee ?? sale.service_fee ?? 0)") &&
      deliveries.includes("setIfValue('f_delivery_fee_mode', sale.delivery_fee_mode || 'exempt')") &&
      deliveries.includes("setIfValue('f_location', sale.location ?? sale.delivery_location)") &&
      deliveries.includes("setIfValue('f_cash_customer_name', sale.cash_customer_name || '')"),
    'حقول التوصيل المطلوبة يجب أن تُجلب من سياق البيع الأصلي');

check(/if \(fee > 0\.0 && feeMode == "driver"\)/.test(scheduler),
    'رسوم السائق لا تظهر في الرسالة الفعلية إلا عند اختيار على حساب السائق');
check(scheduler.includes('قم باستلام رسوم خدمة التوصيل') &&
      scheduler.includes('من العميل عند التوصيل، وسيتم احتسابها عهدة عليك حتى تقوم بتوريدها'),
    'رسالة السائق الفعلية لا تطابق الصياغة المطلوبة');
check(!scheduler.includes('feeMode == "add_to_sales"'),
    'لا يجوز عرض تعليمات التحصيل عند احتساب الرسوم على المبيعات');
check((deliveries.match(/feeMode === 'driver'/g) || []).length >= 2 &&
      deliveries.includes('قم باستلام رسوم خدمة التوصيل'),
    'معاينة SMS داخل الواجهة يجب أن تطابق شرط الرسوم والصياغة الفعلية');

check(/schemaKey = "\$\{db\.path\}:\$\{db\.version\}"/.test(deliveryAccounting) &&
      deliveryAccounting.includes('if (schemaKey in ensuredSchemaVersions) return'),
    'تهيئة مخطط رسوم التوصيل يجب ألا تتكرر لكل عملية بيع ضمن قاعدة البيانات نفسها');
check(/schemaKey = "\$\{database\.path\}:\$\{database\.version\}"/.test(pumpSmsRepository) &&
      pumpSmsRepository.includes('ensureSchemaTables(database)'),
    'تهيئة مخطط قراءة المضخات يجب ألا تكرر ALTER/UPDATE لكل عملية بيع');
check(mainActivity.includes('queueCreditSaleSms(db, saleId, stationScopeId, currentUserId)'),
    'حفظ البيع الآجل يجب أن يستمر في جدولة رسالة العميل');

console.log('Fuel-sales and delivery-notification regression test PASS');
