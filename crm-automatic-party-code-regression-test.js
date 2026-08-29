const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/crm.html'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('placeholder="يُرقم تلقائياً عند الحفظ" readonly'), 'لا يجوز أن يطلب CRM كود طرف يدوياً عند الإضافة');
expect(!source.includes("if (!partyCode) { showToast('❌ الرجاء إدخال كود الطرف'"), 'يجب ألا يفشل الحفظ لغياب كود يصدره SQLite');
expect(source.includes('if (currentEditId) data.party_code = partyCode;'), 'يجب الحفاظ على الرمز الموجود عند التعديل فقط');
console.log('CRM automatic party-code regression PASS.');
