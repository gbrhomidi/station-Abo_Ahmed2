const fs = require('fs');

const load = (name) => fs.readFileSync(`app/src/main/assets/screens/${name}`, 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };
const payroll = load('payroll.html');
const requests = load('maintenance-requests.html');
const schedule = load('maintenance-schedule.html');
const databaseHelper = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');

expect(payroll.includes('function configurePayrollForm(row)'), 'تهيئة نموذج الرواتب غير موجودة');
expect(payroll.includes("['draft','calculated','approved','paid','closed']"), 'حالات الرواتب لا تطابق قيد SQLite');
expect(payroll.includes("delete payload.payroll_code;delete payload.created_by;"), 'رمز الرواتب أو منشئ السجل ما زال مطلوباً من المستخدم عند الإنشاء');
expect(payroll.includes("payroll_code:'رمز مسير الرواتب'"), 'عناوين الرواتب العربية غير مطبقة');
expect(payroll.includes("['calculated_by','approved_by','paid_by','created_by','updated_by','deleted_by'].forEach"), 'حمولة الرواتب لا تنظف المفاتيح الأجنبية الاختيارية');

expect(requests.includes('function configureMaintenanceRequestForm(row)'), 'تهيئة نموذج طلب الصيانة غير موجودة');
expect(requests.includes("delete payload.request_code"), 'رمز طلب الصيانة لا يزال يرسل من إدخال المستخدم عند الإنشاء');
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
