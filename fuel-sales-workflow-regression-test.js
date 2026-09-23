const fs = require('fs');
const source = fs.readFileSync('app/src/main/assets/screens/fuel-sales.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes("listMethod:'getFuelSalesPage'"), 'تبويبات مبيعات الوقود لا تستخدم استعلام الصفحة الفعلي');
expect(source.includes("params.payment_method = state.filter"), 'لا يتم إرسال فلتر طريقة الدفع إلى قاعدة البيانات');
expect(source.includes("state.filter=btn.dataset.filter; reloadData()"), 'تغيير التبويب لا يعيد الاستعلام من قاعدة البيانات');
expect(source.includes('id="fuelAdjustmentModal"'), 'مودال مرتجع/تالف الوقود غير موجود');
expect(source.includes('value="return">مرتجع'), 'خيار مرتجع الوقود غير موجود');
expect(source.includes('value="damage">تالف'), 'خيار تالف الوقود غير موجود');
expect(source.includes("processFuelSaleAdjustment"), 'مسار Android لمعالجة مرتجع/تالف الوقود غير مستخدم');
expect(source.includes("idempotency_key:newIdempotencyKey()"), 'عملية الوقود لا تستخدم idempotency');
expect(source.includes("original_quantity"), 'واجهة الوقود لا تعرض الكمية الأصلية قبل التصحيحات');
expect(source.includes("returned_quantity"), 'واجهة الوقود لا تعرض كمية المرتجع');
expect(source.includes("damaged_quantity"), 'واجهة الوقود لا تعرض كمية التالف');
expect(source.includes("showDeliveryAction(saleId)"), 'زر التوصيل لا يتفعل بعد الحفظ الفعلي');
expect(source.includes("window.location.href='deliveries.html?sale_id='"), 'زر توصيل الطلب لا يستدعي شاشة deliveries.html');

console.log('Fuel sales workflow regression test PASS');
