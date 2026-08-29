const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/assets-local/js/modal-interaction.js'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes("'Barcode': 'الباركود'"), 'يجب أن يحتوي القاموس على ترجمة الحقول الشائعة');
expect(source.includes("'employee code': 'رمز الموظف'") && source.includes("'asset code': 'كود الأصل'"), 'يجب أن يترجم القاموس الحقول الديناميكية للموظفين والأصول');
expect(source.includes("document.querySelectorAll('label, .form-label, .field-label, [placeholder]')"), 'يجب أن يعالج التوطين التسميات والـplaceholders فقط');
expect(source.includes('if (node.children && node.children.length > 0) return;'), 'لا يجوز مسح الأيقونات أو المكونات المركبة عند الترجمة');
expect(source.includes('localizeFieldLabels();'), 'يجب تشغيل التوطين مع تهيئة المودالات');
console.log('Arabic field localization regression PASS.');
