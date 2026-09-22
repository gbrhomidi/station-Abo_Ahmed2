const fs = require('fs');
const path = require('path');
const product = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/products.html'), 'utf8');
const db = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(product.includes('html5-qrcode.min.js'), 'يجب تحميل مكتبة الماسح المحلية قبل تنفيذ صفحة المنتجات');
expect(product.includes('id="barcode"'), 'يجب وجود حقل باركود مستقل عن كود المنتج التلقائي');
expect(product.includes("document.getElementById('barcode').value = decodedText;"), 'يجب أن يملأ الماسح حقل الباركود');
expect(!product.includes("if (!productCode) { showToast('كود المنتج مطلوب'"), 'لا يجوز مطالبة المستخدم بكود منتج يدوي');
expect(db.includes('private fun nextAutomaticProductCode') && db.includes('nextAutomaticProductCode(db, stationScopeId)'), 'يجب أن يصدر SQLite كود المنتج التلقائي داخل المعاملة');
expect(db.includes('private fun ensureProductReferenceData'), 'يجب ضمان مراجع الفئة والوحدة قبل حفظ المنتج');
expect(db.includes("'UNIT-PIECE-REFERENCE'"), 'وحدة المنتج المرجعية غير موجودة');
expect(db.includes("'CAT-GENERAL-REFERENCE'"), 'فئة المنتج المرجعية غير موجودة');
expect(product.includes("case 'getProductPage': raw = AndroidInterface.getProductPage(JSON.stringify(p)); break;"), 'يجب أن تدعم الشاشة تحميل بيانات المنتجات وإحصاءاتها من SQLite');
console.log('Product save, automatic-code, and barcode regression PASS.');
