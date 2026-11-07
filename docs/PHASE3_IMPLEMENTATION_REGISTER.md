# سجل تنفيذ المرحلة الثالثة

**الفرع:** `feature/ai-health-sqlite`  
**النطاق:** توحيد سياسة الاستحقاق، حماية مصادر التاريخ، وفصل تهيئة المخطط عن القراءة.  
**حالة البيانات:** لم يُنفذ Backfill أو تعديل تاريخي على قاعدة مستخدم.

## ما تم تنفيذه

### 1. Parser نوعي مع Adapter توافق

أضيفت `DebtDueDateRepository.parseTermsResult()` بنتيجتين:

- `Known(days, basis, source)` للشروط المفهومة، بما فيها الصفر الصريح.
- `Unknown(reason, normalized)` للنص الفارغ أو غير المدعوم أو المتعارض أو الخارج عن النطاق.

تبقى `parseTermsDays()` كـ Adapter يعيد `Int?` للمستهلكين الحاليين، ولا يحول Unknown إلى `0` أو `30`. مصدر نتيجة النص الصريح هو `explicit_text`، ولا يعني وجود `NET_30` في الكتالوج أنه طُبق تلقائياً.

### 2. فصل حالة الاستحقاق عن العقد القديم

أضيفت `maturityStatusFor()` بالقيم `PAID`, `UNDATED`, `OVERDUE`, `DUE_TODAY`, `NOT_DUE`. يظل الحقل JSON القديم `status` كما هو لتوافق الشاشات، وتصدر مخرجات الديون الجديدة `maturity_status` كي لا تخفي `partial` حقيقة أن الفاتورة مستحقة أو متأخرة.

### 3. تهيئة المخطط في دورة حياة SQLite

أصبحت `DebtDueDateRepository.ensureSchema(SQLiteDatabase)` هي نقطة التهيئة الأساسية، وتُستدعى داخل المعاملة المركزية في `DatabaseHelper.onCreate()` و`onUpgrade()`. أزيلت استدعاءات التهيئة من مسارات البيع، اللوحة، المهام، التقارير، والتذكيرات. واجهة `ensureSchema(DatabaseHelper)` بقيت فقط للتوافق مع الاختبارات أو عملية تهيئة صريحة.

بالتالي لا تنفذ دوال القراءة DDL أو إنشاء Triggers أو فهارس عند فتح الشاشة. لم يضف هذا التغيير ترحيلاً تاريخياً، ولم ينشئ `debt_due_date_migrations`.

## خريطة البيانات الحالية

| الحقل | المعنى المعتمد | قاعدة الثقة |
|---|---|---|
| `parties.payment_terms` | نص العرض/الإعداد الحالي للطرف | ليس دليلاً تاريخياً وحده |
| `parties.payment_terms_days` | قيمة منظمة للطرف | تستخدم للفواتير الجديدة بعد التحقق من المصدر |
| `parties.payment_terms_source` | مصدر إعداد الطرف | `explicit_text` لا يثبت فاتورة قديمة بمفرده |
| `sales_transactions.payment_terms_at_sale` | Snapshot نص الشرط | ثابت بعد البيع ما لم توجد عملية تصحيح مخولة |
| `sales_transactions.payment_terms_days_at_sale` | Snapshot رقمي | يلزم مصدر Snapshot موثوق |
| `sales_transactions.payment_terms_source_at_sale` | مصدر Snapshot | لا يُملأ بأثر رجعي لمجرد وجود رقم |
| `sales_transactions.due_date_basis` | تاريخ الأساس التقويمي المستخدم | ليس نوع/اسم مصدر الأساس |
| `sales_transactions.due_date` | تاريخ الاستحقاق التشغيلي | لا يستخدم إذا كان المصدر غير موثوق |
| `sales_transactions.due_date_source` | provenance لتاريخ الاستحقاق | شرط للأهلية في aging والتذكير |

## مسح القراءات التشغيلية

- `DebtDueDateRepository.readDebts()` يقصر التاريخ الفعال على المصدر الموثوق ويعزل التاريخ غير الموثوق إلى `UNDATED`.
- `getCustomerDebts()` و`getOverdueReminderCandidates()` و`getDueSoonDebtReminders()` تعتمد على القراءة الموثوقة فقط.
- `DatabaseHelper.getOverduePayments()` و`getDebtRemindersPage()` تستخدم predicate المصدر الموثوق.
- اللوحة والمهام تستدعي استعلاماتها دون تهيئة مخطط جانبية؛ تظل قواعد KPI الحالية بحاجة إلى اختبار Robolectric كامل.
- Triggers لا تعيد تفسير النص الحر؛ تعتمد على `payment_terms_days` وSnapshot الرقمي.

## ما لم يُنفذ عمداً

- لا يوجد Backfill للبيانات القديمة.
- لا يوجد اعتماد لمصدر `legacy_rebuilt_snapshot` لكل السجلات؛ يلزم إثبات provenance على مستوى السجل.
- لا يوجد تغيير لمهلة الصفر المالي `0.000001`.
- لا يوجد تغيير لرقم مخطط SQLite `VERSION = 53`؛ التغيير الحالي additive داخل نقاط lifecycle القائمة ولا يضيف أعمدة جديدة.
- لا يوجد اعتماد لقرارات المرحلة الأولى غير الموقعة، ولا إعادة كتابة للأرصدة أو تواريخ المستخدمين.

## الاختبارات

أضيفت حالات لقبول Parser النوعي وحالة `DUE_TODAY`، مع الحفاظ على اختبارات الاستحقاق السابقة. محاولة تشغيل `./gradlew testDebugUnitTest --tests '*DebtDueDatePolicyTest'` في بيئة التنفيذ توقفت قبل Gradle compilation لأن Android SDK غير معرف (`SDK location not found`). لذلك لم يُعلن نجاح بناء محلي أو GitHub Actions لهذا Commit.

## مخاطر وبنود متابعة

1. يجب تشغيل Robolectric وDebug build على بيئة تحتوي Android SDK.
2. يجب تنفيذ جرد كامل لمسارات `addFuelSale()` و`completeSale()` مع fixtures تقارن Kotlin وTrigger.
3. يجب اعتماد قرارات POL-01 إلى POL-17 قبل أي Backfill إنتاجي.
4. يجب تصميم dry-run ومصالحة counts/sums و`integrity_check` و`foreign_key_check` قبل إضافة Migrator تاريخي.
