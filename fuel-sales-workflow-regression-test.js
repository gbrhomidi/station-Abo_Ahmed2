const fs = require('fs');
const source = fs.readFileSync('app/src/main/assets/screens/fuel-sales.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes("listMethod:'getFuelSalesPage'"), 'تبويبات مبيعات الوقود لا تستخدم استعلام الصفحة الفعلي');
expect(source.includes("params.payment_method = state.filter"), 'لا يتم إرسال فلتر طريقة الدفع إلى قاعدة البيانات');
expect(source.includes("state.filter=btn.dataset.filter; reloadData()"), 'تغيير التبويب لا يعيد الاستعلام من قاعدة البيانات');
expect(source.includes("{label:'العميل',key:'customer_id',type:'select',required:false}"), 'حقل العميل ليس قائمة اختيار');
expect(source.includes("<option value=\"0\">عميل عام</option>"), 'العميل العام غير متاح');
expect(source.includes("customerEl.value=field(row,['customer_id','customer_party_id'])||'0'"), 'لا تتم استعادة رقم العميل المختار');
expect(source.includes('payload.customer_id=Number(payload.customer_id||0)'), 'لا يتم حفظ رقم العميل فقط');
expect(!source.includes("{label:'لوحة المركبة',key:'vehicle_plate'"), 'حقل لوحة المركبة ما زال ظاهرًا في النموذج');
expect(source.includes('id="deliveryBtn" disabled'), 'زر توصيل الطلب لا يبدأ معطلاً');
expect(source.includes('showDeliveryAction(saleId)'), 'زر التوصيل لا يتفعل بعد الحفظ الفعلي');
expect(source.includes("window.location.href='deliveries.html?sale_id='"), 'زر توصيل الطلب لا يستدعي شاشة deliveries.html');

console.log('Fuel sales workflow regression test PASS');
