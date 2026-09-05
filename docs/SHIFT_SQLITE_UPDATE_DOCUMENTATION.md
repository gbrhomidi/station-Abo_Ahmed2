# وثائق تحديثات نظام الورديات وتكامل SQLite

**المشروع:** محطة أبو أحمد لمشتقات الديزل

**الفرع:** `feature/ai-health-sqlite`

**آخر commit موثق:** `129b2db`

**المؤلف:** Manus AI

## 1. الملخص التنفيذي

تم تعزيز مسار البيانات الذي يبدأ من إعداد المستخدم الأول وينتهي باستخدام الوردية في نقاط البيع ومبيعات الوقود. يعتمد المسار الآن على هوية المستخدم الحالي لتحديد المحطة والفرع، بدلاً من الاعتماد على قيم واجهة المستخدم باعتبارها مصدرًا موثوقًا. كما تم توحيد حفظ بيانات الوردية في SQLite، والتحقق من ربط المدير وأمين الصندوق بالموظفين والمستخدمين، وتطبيع قائمة العاملين إلى JSON صالح.

أضيفت كذلك حماية للواجهة من رسائل النجاح الكاذبة. لا تعرض شاشة الورديات رسالة نجاح بعد عمليات الإنشاء أو التعديل أو الحذف أو الإغلاق إلا عندما تعيد طبقة Android استجابة يكون فيها `success === true` صراحةً. وتم تعطيل وصول WebView غير الضروري إلى عناوين `content://` من خلال `allowContentAccess = false`.

> **حدود التحقق:** نجحت اختبارات العقود والتكامل والأداء المتاحة. لم يكتمل بناء APK في بيئة التنفيذ لأن Android SDK غير مثبت، ولذلك لم يُدَّعَ تنفيذ اختبار Runtime على جهاز أو محاكي.

## 2. نطاق التحديثات

| المجال | التحديث | المصدر الرئيسي |
|---|---|---|
| إعداد المستخدم الأول | قراءة `station_id` و`branch_id` من الإدخال والتحقق منهما في SQLite | `MainActivity.kt`, `first-user-setup.html` |
| هوية الوردية | اعتماد `users.station_id` و`users.branch_id`، مع fallback محدود إلى `stations.branch_id` | `MainActivity.kt`, `DatabaseHelper.kt` |
| سياق نموذج الوردية | إرجاع المستخدم الحالي والمحطة والفرع وقوائم الموظفين | `DatabaseHelper.getShiftFormContext` |
| إنشاء الوردية | حفظ الوردية typed مع تحقق المحطة والفرع والأدوار | `DatabaseHelper.saveShiftRecordTyped` |
| معرفات الموظفين | استقبال `employees.id` وتحويل المدير وأمين الصندوق إلى `users.id` | `DatabaseHelper.kt` |
| العاملون | دعم JSON وCSV القديم وتخزين قائمة فريدة بصيغة JSON | `DatabaseHelper.kt`, `shifts.html` |
| واجهة الورديات | منع نجاحات الواجهة غير المؤكدة | `shifts.html` |
| أمان WebView | منع وصول `content://` غير المطلوب | `MainActivity.kt` |

## 3. مسار البيانات المعتمد

المسار التشغيلي المعتمد هو:

```text
first-user-setup.html
        ↓
AndroidInterface.setupSystemUser()
        ↓
MainActivity.setupSystemUser()
        ↓
DatabaseHelper.addUser()
        ↓
SQLite users
        ↓
currentUserId → users.station_id / users.branch_id
        ↓
shifts.html
        ↓
AndroidInterface.getShiftFormContext()
        ↓
AndroidInterface.saveShiftRecordTyped()
        ↓
DatabaseHelper.saveShiftRecordTyped()
        ↓
SQLite shifts
        ↓
getOpenShift()
        ↓
POS وFuel Sales
```

تُعد قيمة `users.station_id` مصدر الحقيقة للمحطة. وتُعد قيمة `users.branch_id` مصدر الحقيقة للفرع عندما تكون موجبة. إذا لم تتوفر قيمة الفرع لدى المستخدم، يُسمح بالرجوع إلى `stations.branch_id` للمحطة نفسها فقط. لا يستخدم هذا المسار جدولًا باسم `branches`.

## 4. عقد إعداد المستخدم الأول

تستقبل `setupSystemUser` كائن JSON من الشاشة. يجب أن يحتوي الكائن على `station_id` و`branch_id` موجبين، بالإضافة إلى بيانات المستخدم المطلوبة.

تمر العملية بالمراحل التالية:

1. تحليل الإدخال والتحقق من اسم المستخدم والاسم وكلمة المرور.
2. التحقق من أن `station_id > 0` و`branch_id > 0`.
3. تنفيذ استعلام مباشر على `stations` للتحقق من وجود المحطة وعدم حذفها.
4. قراءة `stations.branch_id` عند توفره.
5. رفض العملية إذا كان فرع المحطة الموجب مختلفًا عن الفرع المرسل.
6. بناء `userData` مع حفظ القيم المدخلة في `station_id` و`branch_id`.
7. تمرير الكائن إلى `addUser`، مع ترك إنشاء المعرف لقاعدة SQLite.

لا تُنشئ العملية موظفًا تلقائيًا للمستخدم الأول. إنشاء سجل موظف مستقل عن هذا المسار ويحتاج إلى قرار منتج منفصل.

## 5. عقد هوية الوردية

### 5.1 المحطة والفرع

تحتفظ `saveShiftRecordTyped` بتوقيع AndroidInterface الحالي، ولذلك يستمر استقبال `stationIdFromUi` و`branchId` من الواجهة. لكن قيمة المحطة القادمة من الواجهة لا تُستخدم كمصدر حقيقة.

تُحل الهوية بالترتيب التالي:

| القيمة | المصدر المعتمد |
|---|---|
| `resolvedStationId` | `users.station_id` للمستخدم الحالي |
| `resolvedBranchId` | `users.branch_id` إذا كان موجبًا |
| fallback للفرع | `stations.branch_id` للمحطة المحلولة فقط |
| تحقق UI | `branchId` القادم من الواجهة للمقارنة فقط |

ترفض العملية إذا لم تكن المحطة أو الفرع موجبين، أو إذا لم توجد المحطة، أو إذا كان فرع المحطة لا يطابق الفرع المحلول، أو إذا اختلف الفرع المرسل من الواجهة عن الفرع المحلول.

### 5.2 المدير وأمين الصندوق

ترسل شاشة HTML معرف الموظف `employees.id`. لا يُحفظ هذا المعرف مباشرة في `shifts.manager_id` أو `shifts.cashier_id`.

تتحقق قاعدة البيانات من الموظف ونطاق محطته وحالته، ثم تحل العلاقة التالية:

```text
employees.id
      ↓
employees.user_id
      ↓
users.id
      ↓
shifts.manager_id أو shifts.cashier_id
```

يجب أن يكون الموظف فعالًا وغير محذوف، وأن يمتلك حساب مستخدم مرتبطًا. كما يتحقق مسار المدير من المسميات الوظيفية المعتمدة، ويتحقق مسار أمين الصندوق من المسمى الوظيفي `CASHIER` أو المقابل العربي المعتمد.

### 5.3 العاملون

تستقبل قاعدة البيانات `attendant_ids` بصيغة JSON array، مثل:

```json
[12, 15, 19]
```

وللتوافق الخلفي، تقبل أيضًا صيغة CSV القديمة:

```text
12,15,19
```

يتم تحويل الصيغتين إلى قائمة أرقام موجبة، وإزالة التكرار، ثم تخزين النتيجة في `shifts.attendant_ids` بصيغة JSON. يتحقق الحفظ من أن كل موظف ينتمي إلى المحطة الحالية وأنه فعال وغير محذوف.

## 6. سياق نموذج الوردية

تعيد `getShiftFormContext(currentUserId)` كائنًا يحتوي على المفاتيح التالية:

| المفتاح | المحتوى |
|---|---|
| `current_user` | بيانات المستخدم الحالي، ومنها `station_id` و`branch_id` |
| `station` | المحطة المرتبطة بالمستخدم الحالي من جدول `stations` |
| `branch` | معرف الفرع المحلول من المستخدم أو المحطة |
| `managers` | موظفو الإدارة المؤهلون في المحطة الحالية |
| `cashiers` | موظفو الصندوق الفعالون في المحطة الحالية |
| `attendants` | الموظفون الفعالون في المحطة الحالية |

لا يعيد الاستعلام محطات عشوائية ولا يستخدم أول محطة في قاعدة البيانات. كما لا ينفذ أي استعلام على جدول `branches` غير الموجود في المخطط الحالي.

## 7. حفظ الوردية في SQLite

تتحقق `saveShiftRecordTyped` من نوع الوردية، وبيانات البداية، وصحة معرفات الأدوار، ثم تنفذ الحفظ داخل معاملة SQLite. تمنع العملية وجود ورديتين مفتوحتين للمحطة نفسها.

يُحفظ السجل في جدول `shifts` مع القيم التالية:

| الحقل | قاعدة الحفظ |
|---|---|
| `station_id` | محطة المستخدم الحالي |
| `branch_id` | فرع المستخدم أو فرع المحطة عند fallback المسموح |
| `manager_id` | `users.id` الناتج عن تحويل موظف المدير |
| `cashier_id` | `users.id` الناتج عن تحويل موظف أمين الصندوق |
| `attendant_ids` | JSON array فريد من `employees.id` |
| `status` | `open` |
| `is_deleted` | القيمة الافتراضية غير المحذوفة في المخطط |
| الإجماليات والإغلاقات | أصفار عند إنشاء وردية جديدة |

بعد الإدراج، يتحقق المسار مباشرة من وجود السجل بالمعرف الناتج، ومن ارتباطه بالمحطة، ومن عدم حذفه. إذا فشل أي تحقق، تُلغى المعاملة ولا تُعرض نتيجة نجاح.

## 8. واجهة المستخدم ومنع النجاح الكاذب

تستخدم شاشة `shifts.html` دالة `requireSuccess`. لا تسمح هذه الدالة بالانتقال إلى حالة النجاح إلا إذا تحقق الشرط التالي:

```javascript
parsed && parsed.success === true
```

تُطبق القاعدة على عمليات الإنشاء والتحديث والحذف وإنهاء الوردية. لا تُغلق النافذة ولا يُعاد تحميل القائمة ولا تُعرض رسالة نجاح عند استجابة فارغة أو عند `success: false` أو `success: null` أو `success: "false"`.

## 9. تكامل POS وFuel Sales

يعتمد POS وFuel Sales على الوردية المفتوحة المرتبطة بالمحطة الحالية. اختبارات التكامل الحالية تتحقق من أن مسار POS ينقص المخزون ويرحل الفاتورة، وأن مسار Fuel Sales يقرأ بياناته من SQLite ويطبق عزل المحطة.

تُعد مطابقة `shift_id` بين الوردية المنشأة و`getOpenShift()` وPOS وFuel Sales اختبار Runtime مطلوبًا على جهاز أو محاكي Android. لم يُنفذ هذا الجزء فعليًا في بيئة التطوير الحالية بسبب غياب Android SDK والجهاز أو المحاكي.

## 10. تحديث أمان WebView

تم ضبط إعداد WebView التالي:

```kotlin
allowContentAccess = false
```

يمنع هذا الإعداد تحميل محتوى من مزودي النظام عبر عناوين `content://`، بينما تظل أصول التطبيق المحلية متاحة وفق سياسة التطبيق. وتبقى إعدادات منع المحتوى المختلط، والوصول العام من ملفات HTML، والتصفح غير الآمن، والتنقل غير الموثوق جزءًا من السياسة الحالية.

يوثق مرجع Android الرسمي أن `allowContentAccess` يتحكم في قدرة WebView على الوصول إلى عناوين المحتوى المقدمة من Content Provider النظامي [2].

## 11. الاختبارات المنفذة

نجحت الاختبارات المركزة التالية بعد التحديثات:

| الاختبار | النتيجة |
|---|---|
| `verify_users_js.py` | PASS |
| `shifts-bridge-contract-test.js` | PASS |
| `shifts-full-day-ui-test.js` | PASS |
| `fuel-sales-workflow-regression-test.js` | PASS |
| `pos-security-performance-regression-test.js` | PASS |
| `pos-scanner-lifecycle-runtime-test.js` | PASS |
| `product-stock-pos-regression-test.js` | PASS |
| `module007_inventory_sqlite_test.py` | PASS |
| `module008_sales_sqlite_test.py` | PASS |
| `security_scope_integration_test.py` | PASS |
| `git diff --check` | PASS |

كما نجح اختبار أداء Fuel Sales على قاعدة اختبارية كبيرة بالقياسات التالية:

| الاستعلام | Median | P95 |
|---|---:|---:|
| جميع عمليات Fuel Sales | 3.17 ms | 3.47 ms |
| العمليات النقدية | 2.69 ms | 2.85 ms |
| العمليات الآجلة | 2.07 ms | 2.33 ms |
| اختيار العملاء | 0.48 ms | 0.56 ms |

## 12. حالة البناء والتشغيل

تمت محاولة تنفيذ الأمر التالي فعليًا:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug --no-daemon
```

فشل البناء بسبب عدم وجود Android SDK في بيئة التنفيذ:

```text
SDK location not found
```

سبق معالجة مشكلة JRE بتثبيت JDK 17 كامل يتضمن `javac`. أما اختبار Runtime الكامل، فيحتاج Android SDK صالحًا وجهازًا أو محاكيًا لتشغيل السيناريو:

```text
first-user-setup → users → shifts → create shift → POS → Fuel Sales
```

## 13. ملفات التحديث

| الملف | نوع التغيير |
|---|---|
| `app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt` | هوية المستخدم، تحقق المحطة والفرع، وإعداد WebView |
| `app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt` | سياق الوردية، الحفظ الذري، تحويل معرفات الموظفين، وتطبيع العاملين |
| `app/src/main/assets/screens/first-user-setup.html` | إزالة القيم الوهمية وإرسال القيم المدخلة |
| `app/src/main/assets/screens/shifts.html` | تحقق صريح من نجاح عمليات الوردية |
| `verify_users_js.py` | فحص تراجعي لعقود شاشة المستخدمين |

## 14. ملاحظات الترحيل والتشغيل

لا تتطلب هذه التحديثات ترحيلًا جماعيًا للبيانات القديمة. لا ينفذ النظام `UPDATE` شاملًا لتخمين الفروع أو المحطات. السجلات القديمة التي تفتقد علاقة موثوقة بين المستخدم والمحطة والفرع تحتاج إلى مصدر إداري أو بيانات موثوقة قبل تصحيحها.

عند نشر النسخة، ينبغي تنفيذ اختبار Runtime على جهاز أو محاكي يتضمن إنشاء مستخدم أول، ثم إنشاء وردية، ثم قراءة الوردية نفسها من القائمة و`getOpenShift()` وPOS وFuel Sales. يجب تسجيل المعرفات الناتجة ومقارنتها قبل اعتماد الإصدار.

## References

[1]: https://www.sqlite.org/lang_transaction.html "SQLite Transaction Documentation"

[2]: https://developer.android.com/reference/android/webkit/WebSettings "Android WebSettings API Reference"
