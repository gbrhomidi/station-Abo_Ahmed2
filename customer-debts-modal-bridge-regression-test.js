const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/customer-debts.html'), 'utf8');
const scriptBlocks = [...source.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(match => match[1]);
const expect = (condition, message) => { if (!condition) throw new Error(message); };

scriptBlocks.forEach((script, index) => new Function(script));
expect(source.includes('bridge.getPartyCrmBundleAsync(requestId, Number(partyId))'), 'يلزم جسر تفاصيل العميل غير المتزامن');
expect(source.includes('customerDebtDetailsCallbacks = new Map()'), 'يلزم حفظ callback مؤقت وقابل للإلغاء');
expect(source.includes('انتهت مهلة تحميل تفاصيل العميل'), 'يلزم حد زمني صريح');
expect(source.includes("Array.isArray(c.invoices) ? c.invoices : []"), 'يجب استهلاك فواتير SQLite من bundle الحقيقي');
expect(source.includes('cancelPendingCustomerDebtDetails();'), 'يلزم تنظيف callbacks عند إغلاق المودال أو مغادرة الشاشة');
const detailsFunction = source.match(/async function viewCustomerDetails\([^\n]*\)\s*\{([\s\S]*?)\n\s*\}\s*async function viewHistory\(/);
expect(detailsFunction, 'تعذر تحديد نطاق دالة تفاصيل العميل في اختبار الجسر');
expect(!/Promise\.all\(/.test(detailsFunction[1]), 'لا يجوز تنفيذ قراءات جسر متزامنة داخل دالة تفاصيل العميل');
['reportAgingBucket', 'reportMinOverdueDays', 'reportPaymentMethod', 'reportSettlementType'].forEach(id => {
  expect(source.includes(`id="${id}"`), `يلزم وجود فلتر التقرير المتقدم: ${id}`);
});
expect(source.includes('reportAgingMatches(age,f.aging)'), 'يجب تطبيق فلتر الفئة العمرية على الديون');
expect(source.includes('paymentSettlement!==f.settlementType'), 'يجب تطبيق فلتر مصدر التحصيل على المدفوعات');


expect(source.includes('<h2>إدارة ديون العملاء</h2>'), 'يجب اعتماد عنوان إدارة ديون العملاء');
const debtHeader = source.match(/<header class="top-bar">([\\s\\S]*?)<\\/header>/);
expect(debtHeader, 'تعذر تحديد الشريط العلوي لشاشة ديون العملاء');
expect(!debtHeader[1].includes('onclick="openAIAdvisor()"'), 'يجب حذف أيقونة المستشار من الشريط العلوي');
expect(source.includes("document.getElementById('filterDateFrom').value = '';"), 'يجب فتح شاشة الديون دون قيد تاريخ افتراضي');
expect(source.includes('function getDebtFilterDate(row)'), 'يجب توحيد مرجع تاريخ فلترة الديون');
expect(source.includes('row.created_at || row.transaction_date || row.business_day'), 'يجب إتاحة الفواتير التاريخية غير المؤرخة بتاريخ استحقاق موثوق عبر تاريخ الفاتورة');
expect(source.includes('const nativeDateFilter = dueInvoicesDrilldownActive'), 'يجب إبقاء قيد الاستحقاق الأصلي لمسار تفصيل مؤشر لوحة المعلومات');
expect(source.includes('<option value="undated">غير محدد الاستحقاق</option>'), 'يجب إتاحة فلترة الديون التي لا تملك تاريخ استحقاق موثوقاً');
console.log(`Customer debts modal async bridge regression PASS (${scriptBlocks.length} script block(s)).`);
