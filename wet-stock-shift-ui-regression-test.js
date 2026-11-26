const fs = require('fs');
const path = require('path');
const root = __dirname;
const screen = fs.readFileSync(path.join(root, 'app/src/main/assets/screens/stock-levels.html'), 'utf8');
const db = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
function assert(condition, message) { if (!condition) throw new Error(message); }
assert(db.includes('kpis.put("current_shift_id", shiftId)'), 'يجب أن يعيد SQLite معرف الوردية المفتوحة');
assert(db.includes('AS has_reading'), 'يجب تمييز رصيد الدفتر عن قراءة الخزان الفعلية');
assert(screen.includes('window.currentWetShiftId = Number(kpis.current_shift_id || 0)'), 'يجب أن تحفظ الواجهة معرف الوردية من SQLite');
assert(screen.includes("shift_id:Number(window.currentWetShiftId || 0)"), 'يجب ربط قراءة الغمس بالوردية المفتوحة عند وجودها');
assert(db.includes('require(shiftId > 0L) { "يجب ربط قراءة الغمس بورديّة مفتوحة" }'), 'يجب أن يفرض SQLite وجود معرف وردية صريح قبل حفظ قراءة الغمس');
const reconcile = screen.match(/async function reconcileWetTank\(tankId\) \{([\s\S]*?)\n\}/)?.[1] || '';
assert(reconcile.includes('لا توجد وردية مفتوحة'), 'يجب منع التسوية عند عدم وجود وردية مفتوحة');
assert(reconcile.includes('shift_id:activeShiftId'), 'يجب استخدام معرف الوردية المحملة من المصدر الأصلي');
assert(!reconcile.includes('reconShift') && !reconcile.includes('reconActual'), 'لا تطلب رقم الوردية أو كمية فعلية يدوية قد تتعارض مع قراءة SQLite');
assert(screen.includes('water === null ? \'غير مقاس\''), 'يجب عدم عرض القراءة المفقودة كمستوى ماء صفر');
assert(screen.includes('غير محسوب (درجة الحرارة غير متاحة)'), 'يجب عدم افتراض درجة 15 عند غياب القياس الحراري');
console.log('Wet stock shift and missing-measurement UI contract PASS.');
