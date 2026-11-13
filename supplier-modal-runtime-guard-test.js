const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/suppliers.html'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('.modal-overlay.show { display: flex; opacity: 1; pointer-events: auto; }'), 'يجب تفعيل لمس الطبقة فقط عند إظهار المودال');
expect(source.includes('pointer-events: auto;\n            touch-action: pan-y;'), 'يجب أن يستقبل محتوى المودال اللمس والتمرير');
expect(source.includes('touch-action: manipulation;') && source.includes('-webkit-user-select: text;'), 'يجب أن تبقى حقول المورد قابلة للمس والكتابة');
expect(source.includes('firstInput.focus({ preventScroll: true });'), 'يجب أن يتم التركيز دون تمرير قسري');
expect(!source.includes('station_id: 1'), 'لا يجوز أن يختار مودال المورد محطة ثابتة');
const detailStart = source.indexOf('async function viewDetails(id)');
const detailEnd = source.indexOf('function closeDetails()', detailStart);
const detail = source.slice(detailStart, detailEnd);
expect(detail.includes("apiCall('getSupplierAging'"), 'تفاصيل المورد يجب أن تقرأ أعمار المستحقات من SQLite');
expect(detail.includes("apiCall('getSupplierInvoices'"), 'تفاصيل المورد يجب أن تعرض فواتير المورد');
expect(detail.includes("apiCall('getSupplierPayments'"), 'تفاصيل المورد يجب أن تعرض المدفوعات');
expect(!detail.includes('تفاصيل الفواتير والمدفوعات والملاحظات المنفصلة غير متاحة'), 'يجب إزالة رسالة عدم توفر كشف المورد');

const supplierFormStart = source.indexOf('<form id="supplierForm"');
const supplierFormEnd = source.indexOf('</form>', supplierFormStart);
const supplierForm = source.slice(supplierFormStart, supplierFormEnd);
expect(supplierForm.includes('id="supplierPaymentTerms"'), 'يجب إتاحة شروط السداد في نموذج المورد');
const saveStart = source.indexOf('async function saveSupplier()');
const saveEnd = source.indexOf('// 24. تعديل مورد', saveStart);
const saveSupplier = source.slice(saveStart, saveEnd);
expect(saveSupplier.includes("payment_terms: document.getElementById('supplierPaymentTerms').value.trim()"), 'يجب حفظ شروط السداد مع بيانات المورد');
const editStart = source.indexOf('async function editSupplier(id)');
const editEnd = source.indexOf('function closeModal()', editStart);
const editSupplier = source.slice(editStart, editEnd);
expect(editSupplier.includes("document.getElementById('supplierPaymentTerms').value = getField(supplier, 'payment_terms', 'paymentTerms') || ''"), 'يجب استعادة شروط السداد عند تعديل المورد');
expect(source.includes('لن يُفترض أجل تلقائي'), 'يجب توضيح عدم افتراض أجل عند غياب الشروط');

console.log('Supplier modal runtime interaction guard contract PASS.');
