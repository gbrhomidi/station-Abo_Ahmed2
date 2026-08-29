const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/customer-reports.html'), 'utf8');
const expect = (condition, message) => {
  if (!condition) throw new Error(message);
};

expect(source.includes('bridge.getPartyCrmBundleAsync(requestId, Number(partyId))'), 'تفاصيل تقارير العملاء يجب أن تستخدم جسر SQLite غير المتزامن');
expect(!/async function viewCustomer\([\s\S]*?apiCall\('getPartyCrmBundle'/.test(source), 'لا يجوز أن يعيد مودال التقرير استدعاء getPartyCrmBundle المتزامن');
expect(source.includes('customerReportDetailsCallbacks = new Map()'), 'يلزم حفظ callbacks المؤقتة لإلغائها');
expect(source.includes('customerReportDetailsRequestVersion'), 'يلزم حارس نتيجة الطلب المتأخرة');
expect(source.includes('انتهت مهلة تحميل تفاصيل العميل'), 'يلزم حد زمني صريح لطلب تفاصيل التقرير');
expect(source.includes("window.addEventListener('pagehide', () => cancelPendingCustomerReportDetails"), 'يلزم تنظيف callbacks عند مغادرة الشاشة');
expect(source.includes('overlay.classList.add(\'show\');') && source.indexOf('overlay.classList.add(\'show\');') < source.indexOf('await invokeCustomerReportDetailsAsync(id)'), 'يلزم فتح المودال قبل قراءة SQLite');
expect(!/function viewCustomer\([\s\S]*?showLoading\(true\)[\s\S]*?invokeCustomerReportDetailsAsync/.test(source), 'لا يجوز لحمل التفاصيل أن يستخدم الغطاء العام الحاجب');

console.log('Customer reports modal async bridge regression contract PASS.');
