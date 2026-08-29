const fs = require('fs');
const path = require('path');
const inventory = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/inventory-movements.html'), 'utf8');
const theme = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/assets-local/css/theme.css'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(/class="modal fade"[^>]*id="(?:createModal|viewModal)"/.test(inventory), 'يجب أن يستخدم المخزون عقد Bootstrap modal fade');
expect(theme.includes('.modal.fade.show,') && theme.includes('pointer-events: auto;'), 'يجب أن تعيد theme.css تفعيل اللمس لمودال Bootstrap الظاهر');
expect(theme.includes('.modal.fade.show :is(input, textarea, select, button)'), 'يجب أن تدعم عناصر نموذج المخزون اللمس والكتابة');
console.log('Inventory Bootstrap modal pointer-events regression PASS.');
