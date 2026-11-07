# سجل تنفيذ المرحلة الرابعة

**الفرع:** `feature/ai-health-sqlite`

**نطاق هذا التغيير:** توحيد أهلية تاريخ الاستحقاق ومصدر الرصيد وتصنيف واجهات الديون والتقارير، دون ترحيل أو تعديل بيانات المستخدم.

## الإصلاحات المنفذة

1. أزيل `legacy_rebuilt_snapshot` من مجموعة مصادر `due_date` الموثوقة؛ السجلات المعاد بناؤها تاريخياً لا تدخل المسارات التشغيلية أو التذكيرات أو المتأخرات.
2. أعيد استخدام predicate المصدر الموحد في مسار المهام المالية، مع إبقاء `station_id` و`asOfDate` معاملات SQL مربوطة.
3. وُحّدت مسارات لوحة التحكم وواجهات DatabaseHelper مع قائمة المصادر الموثوقة نفسها.
4. أزيلت من شاشة تذكيرات الديون معادلة `total - paid` المحلية؛ `remaining_amount` المحفوظ هو المصدر المحاسبي، والقيمة المفقودة لا تُخمن.
5. أزيلت من التذكيرات وتقارير العملاء إعادة تصنيف الاستحقاق اعتماداً على ساعة الجهاز؛ الواجهة تستهلك `maturity_status` و`days_remaining` الصادرين من Kotlin/BusinessDay.
6. أضيف اختبار انحدار ثابت يرفض إعادة إدخال المصدر التاريخي المحظور أو معادلة الرصيد أو حساب العمر في JavaScript.

## التحقق المنفذ

نجحت الاختبارات التالية فعلياً:

- `stage4-debt-unification-regression-test.js`
- `customer-debts-modal-bridge-regression-test.js`
- `dashboard-reminder-throttle-test.js`
- `iso-civil-date-regression-test.js`
- `sqlite-query-plan-regression-test.py`
- `business_day_boundary_integration_test.py`
- `security_scope_integration_test.py`
- `reports-evidence-reconciliation-integration-test.py`
- `reports-module-contract-test.js`
- `reports-unbounded-query-test.js`
- `reports-backend-capability-test.js`
- فحص `node --check` للسكربتات المضمنة في `debt-reminders.html` و`customer-reports.html`.

## القيود غير المحسومة

- لم يمكن تشغيل `./gradlew testDebugUnitTest assembleDebug --no-daemon` في بيئة التنفيذ؛ توقف قبل compilation بسبب غياب Android SDK (`SDK location not found`). لذلك لا يوجد ادعاء بنجاح اختبار Robolectric أو بناء Debug.
- لم يُنفذ بناء Release؛ سياسة المستودع تقصر البناء الموقع على GitHub Actions/الأسرار، ولم تُمس إعدادات التوقيع.
- لا تزال قرارات المرحلة الأولى ذات الحالة `PENDING_OWNER_APPROVAL`، لذلك لم يُنفذ Backfill أو إعادة كتابة due_date أو الأرصدة أو سياسة العملات.
- يلزم تشغيل اختبارات Kotlin/SQLite الكاملة وGitHub Actions على Runner يحتوي Android SDK قبل إعلان `PASS` النهائي.

**الحالة:** `PARTIAL` إلى حين نجاح Gradle/CI وإثبات اختبارات APK؛ لا توجد تغييرات بيانات أو Migration في هذا التغيير.
