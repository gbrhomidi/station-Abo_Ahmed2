const fs = require('fs');

const source = fs.readFileSync('app/src/main/assets/screens/employee-payments.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('function openForm(row = null)'), 'تهيئة نموذج دفعات الموظفين غير موجودة');
expect(source.includes('value="salary">راتب') && source.includes('value="advance">سلفة') && source.includes('value="bonus">مكافأة') && source.includes('value="deduction">خصم') && source.includes('value="allowance">بدل'), 'خيارات نوع دفعة الموظف لا تطابق قيد SQLite');
expect(source.includes("payment_type: $('payment_type').value"), 'لا يتم تضمين نوع الدفعة في الحمولة المرسلة');
expect(source.includes("if (f === 'payment_date') el.value = today();"), 'تاريخ دفعة الموظف لا يملأ افتراضياً عند الإنشاء');
expect(source.includes("if (data.period_from && data.period_to && data.period_from > data.period_to)"), 'لا توجد حماية من فترة خصم معكوسة');
expect(source.includes("type === 'deduction'"), 'لا توجد معالجة لتقارير الخصومات');

console.log('Employee payments constrained-form regression PASS.');
