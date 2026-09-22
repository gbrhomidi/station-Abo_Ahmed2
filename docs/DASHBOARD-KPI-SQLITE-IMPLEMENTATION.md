# تحديث لوحة التحكم الرئيسية وربط مؤشرات KPI ببيانات SQLite

## نطاق التنفيذ
تم تحديث `app/src/main/assets/main.html` مع الحفاظ على عقد الجسر القائم `getDashboardStats`، بحيث تستمر الشاشة في استقبال الاستجابة من `MainActivity.kt` تحت البنية `{ success: true, data: ... }`. مصدر المؤشرات هو `DatabaseHelper.getDashboardStats(stationId)`، ولا توجد قاعدة بيانات أو API جديدة.

## المؤشرات المعروضة

| المؤشر | مفتاح SQLite الممرر إلى الواجهة | مصدر الحساب |
|---|---|---|
| إجمالي المنتجات | `total_products` | المنتجات النشطة في المحطة |
| المبيعات اليومية | `daily_sales` | صافي مبيعات اليوم من `sales_transactions` |
| العملاء النشطون | `active_customers` | العملاء ذوو المعاملات الفعلية |
| انتهاء قريب وانخفاض مخزون | `expiry_soon`, `low_stock` | جداول المنتجات والمخزون |
| الفواتير والديون | `due_invoices`, `customer_debts`, `supplier_debts` | معاملات البيع والأطراف والتعبئة |
| قيمة المخزون | `inventory_value` | الكمية الحالية مضروبة في سعر الشراء |
| المهام المعلقة | `pending_tasks` | جدول `tasks` الفعلي |
| اتجاهات المبيعات والمنتجات | `sales_trend`, `products_trend` | مقارنة فترات SQLite الحالية والسابقة |
| امتلاء الخزانات | `occupancy_rate`, `tank_count` | `SUM(tanks.current_quantity) / SUM(tanks.capacity_liters)` |

## تصحيح جوهري
كان `occupancy_rate` يُقدَّم سابقاً كقيمة تقريبية مبنية على عدد المنتجات وقاسم ثابت. تم استبدال ذلك باستعلام فعلي على جدول `tanks`، مع إبقاء المفتاح القديم للتوافق مع الشاشات الموجودة وإضافة `total_tank_quantity` و`total_tank_capacity` و`tank_count` و`occupancy_rate_source`. وعند غياب السعة أو كونها صفراً، تُرجع القاعدة نسبة صفرية بدلاً من القسمة غير الآمنة.

## سلوك التحديث
لم تعد الشاشة تعرض الذاكرة المؤقتة على أنها snapshot حديثة قبل محاولة القراءة من SQLite. كل تحميل عادي يستدعي الجسر ويقرأ أحدث بيانات متاحة، بينما يُستخدم cache فقط كخطة استرداد عند تعذر الجسر، مع إشعار واضح للمستخدم.

## أدلة التحقق

| طبقة التحقق | النتيجة |
|---|---:|
| فحص وجود `Math.random()` | PASS — غير موجود |
| فحص التقدم الثابت `progress: 70` | PASS — أُزيل |
| فحص استدعاء `getDashboardStats` | PASS |
| فحص التفاف الاستجابة `dataResponse(stats)` | PASS |
| فحص تفويض `MainActivity` إلى `DatabaseHelper` | PASS |
| فحص استعلام الخزانات الحقيقي | PASS |
| اختبارات `main-ui-test.js` | 14/14 PASS |
| فحص JavaScript Syntax | PASS |
| `git diff --check` | PASS |

## حدود التحقق
تم التحقق محلياً عبر تحليل HTML/JavaScript وعقود المصدر. لم يُنفذ Android Runtime أو Gradle/Robolectric في البيئة الحالية لعدم توفر Android SDK، لذلك يبقى التحقق النهائي من التشغيل على جهاز Android أو عبر CI بوصفه **BUILD/RUNTIME VERIFIED** وليس ضمن التحقق المحلي الحالي.

## تصنيف MEGP-SMS

| البند | التصنيف |
|---|---|
| ربط Dashboard بالجسر وقاعدة SQLite | STATIC VERIFIED |
| إزالة Mock KPI والقيمة الثابتة | STATIC VERIFIED + UI TEST VERIFIED |
| حساب امتلاء الخزانات من أعمدة فعلية | STATIC VERIFIED |
| تشغيل Android/SQLite الفعلي على جهاز | يحتاج CI أو جهاز Android |

**المسارات الرئيسية:** `main.html`، `MainActivity.kt`، `DatabaseHelper.kt`، `main-ui-test.js`، `main-script-syntax-test.js`.
