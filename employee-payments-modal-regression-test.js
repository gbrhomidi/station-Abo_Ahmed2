const fs = require('fs');

const source = fs.readFileSync('app/src/main/assets/screens/employee-payments.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('function configureEmployeePaymentForm(row)'), 'تهيئة نموذج دفعات الموظفين غير موجودة');
expect(source.includes('value="salary">راتب') && source.includes('value="advance">سلفة') && source.includes('value="penalty">خصم') && source.includes('value="bonus">مكافأة') && source.includes('value="other">أخرى'), 'خيارات نوع دفعة الموظف لا تطابق قيد SQLite');
expect(source.includes("['salary','advance','penalty','bonus','other'].includes(payload.type)?payload.type:'salary'"), 'لا توجد حماية للحمولة ضد نوع دفعة غير صالح');
expect(source.includes("date.value=new Date().toISOString().slice(0,10)"), 'تاريخ دفعة الموظف لا يملأ افتراضياً عند الإنشاء');

console.log('Employee payments constrained-form regression PASS.');
