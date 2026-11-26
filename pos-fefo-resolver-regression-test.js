const fs = require('fs');
const path = require('path');
const root = __dirname;
const pos = fs.readFileSync(path.join(root, 'app/src/main/assets/screens/pos.html'), 'utf8');
const db = fs.readFileSync(path.join(root, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt'), 'utf8');
const body = pos.match(/async function prepareDryStockProducts\(products\) \{([\s\S]*?)\n\}/)?.[1] || '';
function assert(condition, message) { if (!condition) throw new Error(message); }
assert(body.includes('delete item.batch_id'), 'يجب حذف أي اختيار دفعة قديم قبل إرسال عملية البيع');
assert(!body.includes("apiCall('getProductBatches'"), 'لا ينبغي للواجهة اختيار دفعة مستقلة عن محرك SQLite');
assert(db.includes('private fun resolveDryStockSelection'), 'يجب أن يحسم SQLite اختيار الدفعة');
assert(db.includes('date(expiry_date)>=date(?)'), 'يجب على محرك SQLite استبعاد الدفعات المنتهية');
assert(db.includes('date(expiry_date) ASC,date(received_at) ASC,batch_id ASC'), 'يجب فرض ترتيب FEFO ثم FIFO عند التعادل');
assert(db.includes('consumeDryStockSelection'), 'يجب أن يبقى خصم الدفعة ضمن مسار البيع الأصلي');
console.log('POS FEFO resolver contract PASS.');
