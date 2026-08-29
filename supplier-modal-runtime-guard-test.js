const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/suppliers.html'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('.modal-overlay.show { display: flex; opacity: 1; pointer-events: auto; }'), 'يجب تفعيل لمس الطبقة فقط عند إظهار المودال');
expect(source.includes('pointer-events: auto;\n            touch-action: pan-y;'), 'يجب أن يستقبل محتوى المودال اللمس والتمرير');
expect(source.includes('touch-action: manipulation;') && source.includes('-webkit-user-select: text;'), 'يجب أن تبقى حقول المورد قابلة للمس والكتابة');
expect(source.includes('firstInput.focus({ preventScroll: true });'), 'يجب أن يتم التركيز دون تمرير قسري');
expect(!source.includes('station_id: 1'), 'لا يجوز أن يختار مودال المورد محطة ثابتة');
console.log('Supplier modal runtime interaction guard contract PASS.');
