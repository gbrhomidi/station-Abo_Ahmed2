const fs = require('fs');
const path = require('path');
const screen = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/inventory-movements.html'), 'utf8');
const db = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const bridge = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(screen.includes('id="filter-stock-type"') && screen.includes('id="filter-product-id"') && screen.includes('id="filter-fuel-id"'), 'يجب أن تحفظ فلاتر المنتجات والوقود المعرف الحقيقي');
expect(screen.includes('openProductPicker') && screen.includes('openFuelPicker'), 'يجب أن تستخدم الشاشة منتقيات حقيقية من SQLite');
expect(screen.includes("case 'getFuelTypes': return invokeTypedBridge('getFuelTypesForInventoryMovements')"), 'يجب أن يملك JavaScript عقد جلب الوقود');
expect(screen.includes("case 'saveMovement': return p.stock_type === 'fuel' ? invokeTypedBridge('addFuelInventoryMovement'"), 'يجب فصل حفظ حركة الوقود عن حركة المنتجات');
expect(screen.includes("case 'exportInventoryReport': return invokeTypedBridge('exportInventoryMovementReport'"), 'يجب أن يمر التصدير عبر Android Native');
expect(screen.includes('report-stock-type') && screen.includes('value="movement"'), 'يجب دعم تقرير حركات موحد مع نطاق المنتجات/الوقود/الكل');
expect(screen.includes('analytics-stock-type') && screen.includes('getFuelAnalysis'), 'يجب دعم تحليل الوقود الحقيقي');
expect(db.includes('fun getUnifiedInventoryMovements') && db.includes("'fuel' AS stock_type"), 'يجب أن يملك SQLite استعلاماً موحداً مع إبقاء نوع الوقود منفصلاً');
expect(db.includes('fun addFuelInventoryMovement') && db.includes('UPDATE tanks SET current_quantity'), 'يجب تحديث رصيد الخزان ضمن معاملة حركة الوقود');
expect(db.includes('db.beginTransaction()') && db.includes('db.setTransactionSuccessful()'), 'يجب أن تكون حركة الوقود ذرية');
expect(bridge.includes('fun exportInventoryMovementReport') && bridge.includes('FileProvider'), 'يجب إنشاء الملف وفتحه عبر Android');
console.log('Inventory unified products/fuel regression contract PASS.');
