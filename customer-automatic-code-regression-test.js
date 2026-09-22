const fs = require('fs');
const path = require('path');
const customer = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/customers.html'), 'utf8');
const db = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(customer.includes('placeholder="يُرقم تلقائياً عند الحفظ" readonly'), 'لا يجوز طلب إدخال رمز العميل يدوياً');
expect(customer.includes("party_type: 'customer'"), 'يلزم تحديد نوع العميل التشغيلي في payload');
expect(!customer.includes('party_code: document.getElementById(\'partyCode\').value.trim(),'), 'لا يجوز إرسال رمز فارغ عند إنشاء عميل جديد');
expect(db.includes('private fun nextAutomaticPartyCode') && db.includes('nextAutomaticPartyCode(db, authorizedStationId)'), 'يلزم إصدار رمز فريد تلقائياً من SQLite داخل المعاملة');
console.log('Customer automatic-code and save-contract regression PASS.');
