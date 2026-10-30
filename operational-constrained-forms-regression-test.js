const fs = require('fs');

const load = (name) => fs.readFileSync(`app/src/main/assets/screens/${name}`, 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };
const payroll = load('payroll.html');
const requests = load('maintenance-requests.html');
const schedule = load('maintenance-schedule.html');
const databaseHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');
const mainActivity = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

// Payroll uses a dedicated form, not the generic CRUD configure*Form convention.
expect(
    payroll.includes('function openForm(row = null)') &&
    payroll.includes('function collectForm()') &&
    payroll.includes('async function saveForm()'),
    'مسار فتح وتجميع وحفظ نموذج الرواتب غير مكتمل'
);

// Compare the options actually rendered to the authoritative SQLite CHECK constraint.
const payrollStatusSelect = payroll.match(/<select\b[^>]*\bid="status"[^>]*>([\s\S]*?)<\/select>/i);
const uiPayrollStatuses = payrollStatusSelect
    ? [...payrollStatusSelect[1].matchAll(/<option\b[^>]*\bvalue="([^"]+)"/gi)].map(match => match[1])
    : [];
const payrollTableStart = databaseHelper.indexOf('CREATE TABLE IF NOT EXISTS payroll (');
const payrollTableSchema = payrollTableStart >= 0 ? databaseHelper.slice(payrollTableStart) : '';
const sqlPayrollStatus = payrollTableSchema.match(/status\s+VARCHAR\(20\)\s+DEFAULT\s+'draft'\s+CHECK\s*\(status\s+IN\s*\(([^)]+)\)\)/i);
const databasePayrollStatuses = sqlPayrollStatus
    ? [...sqlPayrollStatus[1].matchAll(/'([^']+)'/g)].map(match => match[1])
    : [];
expect(uiPayrollStatuses.length > 0, 'خيارات حالة الرواتب غير موجودة في النموذج');
expect(
    JSON.stringify(uiPayrollStatuses) === JSON.stringify(databasePayrollStatuses),
    'خيارات حالة الرواتب لا تطابق قيد CHECK في SQLite'
);

expect(
    /id="payroll_code"[^>]*readonly/i.test(payroll) &&
    /payload\.payroll_code\s*=\s*automaticPayrollCode\(\)/.test(payroll),
    'رمز مسير الرواتب لا يُولّد تلقائياً ولا يُحمى من إدخال المستخدم'
);
expect(
    /payroll_code\s*:\s*'رمز مسير الرواتب'/.test(payroll),
    'عنوان رمز مسير الرواتب العربي غير مطبق'
);
expect(
    mainActivity.includes('put("created_by", activity.currentUserId)'),
    'مسار الحفظ لا يربط منشئ سجل الرواتب بالمستخدم الحالي'
);
expect(
    databaseHelper.includes('listOf("calculated_by", "approved_by", "paid_by", "created_by", "updated_by", "deleted_by")'),
    'DatabaseHelper لا ينظف المراجع الاختيارية الفارغة قبل الحفظ'
);

expect(requests.includes('function configureMaintenanceRequestForm(row)'), 'تهيئة نموذج طلب الصيانة غير موجودة');
expect(requests.includes('delete payload.request_code'), 'رمز طلب الصيانة لا يزال يرسل من إدخال المستخدم عند الإنشاء');
expect(requests.includes("choose('priority',[['low','منخفضة'],['medium','متوسطة'],['high','عالية'],['critical','حرجة']],'medium')"), 'خيارات أولوية طلب الصيانة غير محددة بالعربية');

expect(schedule.includes('function configureMaintenanceScheduleForm(row)'), 'تهيئة نموذج جدولة الصيانة غير موجودة');
expect(schedule.includes('value="meter_based">حسب العداد'), 'تكرار الجدولة لا يطابق قيد SQLite');
expect(schedule.includes("['daily','weekly','monthly','yearly','meter_based'].includes(payload.frequency_type)?payload.frequency_type:'monthly'"), 'لا توجد حماية لحمولة تكرار الصيانة');
expect(schedule.includes('delete payload.schedule_code'), 'رمز الجدولة لا يزال يرسل من إدخال المستخدم عند الإنشاء');
expect(schedule.includes('"updateAction":null'), 'جدولة الصيانة ما زالت تستخدم مسار تحديث حالة غير متوافق مع عقد التعديل');
expect(schedule.includes("document.querySelector('#formModal.show .modal')"), 'إشعارات الحفظ لا تنتقل إلى مودال الجدولة النشط');
expect(databaseHelper.includes('SELECT id, ${spec.columns.joinToString(", ")} FROM ${spec.table}$whereSql'), 'القائمة العامة لا تعيد المفتاح الأساسي id إلى WebView');
expect(databaseHelper.includes('listOf("calculated_by", "approved_by", "paid_by", "created_by", "updated_by", "deleted_by")'), 'DatabaseHelper لا ينظف المفاتيح الأجنبية الاختيارية للرواتب');
const schemaVersion = Number((databaseHelper.match(/const val VERSION = (\d+)/) || [])[1]);
expect(schemaVersion >= 30, 'إصدار SQLite يجب أن يبقى V30 أو أحدث بعد ترحيل مراجع المنتجات');
expect(databaseHelper.includes('28 -> migrateV28ToV29(db)'), 'ترحيل V28 إلى V29 لجدولة الصيانة غير مسجل');
expect(databaseHelper.includes('ensureMaintenanceScheduleStationScopeSchema(db)'), 'عزل محطة جدولة الصيانة لا يُضمن عند فتح قاعدة البيانات');
expect(databaseHelper.includes('idx_maintenance_schedule_station_active'), 'فهرس عزل محطة جدولة الصيانة غير موجود');
expect(databaseHelper.includes('"maintenance_schedule" -> OperationalTableSpec(') && databaseHelper.includes('"is_active", "station_id", "created_by"'), 'مواصفة CRUD لجدولة الصيانة لا تتضمن station_id');

console.log('Operational constrained forms regression PASS.');
