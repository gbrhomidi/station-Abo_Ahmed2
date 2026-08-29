const fs = require('fs');
const path = require('path');
const screen = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/inventory-movements.html'), 'utf8');
const bridge = fs.readFileSync(path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(bridge.includes('fun getStockMovementDetailsAsync(requestId: String, movementId: Long)'), 'يلزم جسر تفاصيل حركة مخزون غير متزامن');
expect(bridge.includes('db.getStockMovementsPage(') && bridge.includes('requireCurrentStationId(db, userId)'), 'يجب أن يشتق الجسر محطة الجلسة قبل الاستعلام');
expect(screen.includes('bridge.getStockMovementDetailsAsync(requestId, Number(movementId))'), 'يجب أن يستخدم المودال الجسر غير المتزامن');
expect(!/async function viewMovement\([\s\S]*?apiCall\('getMovement'/.test(screen), 'لا يجوز أن يستدعي المودال قائمة الحركات المتزامنة');
expect(screen.includes('inventoryMovementDetailsCallbacks = new Map()'), 'يلزم حفظ callbacks المؤقتة');
expect(screen.includes('انتهت مهلة تحميل تفاصيل الحركة'), 'يلزم حد زمني صريح للطلب');
expect(screen.includes("hidden.bs.modal") && screen.includes('cancelPendingInventoryMovementDetails()'), 'يلزم تنظيف callbacks عند إغلاق المودال');
expect(screen.includes("const page = result.data || result;") && screen.includes("page.rows || []"), 'يجب دعم عقد صفحة SQLite ذي rows بدلاً من افتراض مصفوفة');
expect(!/function viewMovement\([\s\S]*?showLoading\(true\)[\s\S]*?invokeInventoryMovementDetailsAsync/.test(screen), 'لا يجوز استخدام الغطاء العام الحاجب لتفاصيل المودال');

console.log('Inventory movement modal async bridge regression contract PASS.');
