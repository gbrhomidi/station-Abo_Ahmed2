const fs = require('fs');
const path = require('path');
const employee = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/employees.html'), 'utf8');
const asset = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/fixed-assets.html'), 'utf8');
const db = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(db.includes('"employees" -> defaultString("employee_code", "EMP")'), 'يجب أن يصدر كود الموظف تلقائياً');
expect(db.includes('"fixed_assets" -> defaultString("asset_code", "AST")'), 'يجب أن يصدر كود الأصل تلقائياً');
expect(employee.includes("['full_time','دوام كامل']") && employee.includes("['male','ذكر']"), 'يجب أن يستخدم مودال الموظف قيم CHECK الصحيحة مع عناوين عربية');
expect(asset.includes("['other','أصل آخر']") && asset.includes("['maintenance','تحت الصيانة']"), 'يجب أن يستخدم مودال الأصل قيم CHECK الصحيحة مع عناوين عربية');
expect(employee.includes('grid-template-columns:repeat(2,minmax(0,1fr))') && asset.includes('grid-template-columns:repeat(2,minmax(0,1fr))'), 'يجب الحفاظ على حقلين في الصف نفسه على الشاشات الصغيرة');
console.log('Employee and fixed-assets modal regression PASS.');
