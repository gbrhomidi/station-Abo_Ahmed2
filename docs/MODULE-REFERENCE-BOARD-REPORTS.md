# MODULE-REFERENCE-BOARD: Reports & Analytics

تم إنشاء هذه اللوحة المرجعية استناداً إلى مشاريع مفتوحة المصدر رائدة، لتوجيه إعادة هندسة وحدة التقارير (`reports`) في مشروع "محطة أبو أحمد".

## 1. Dashboard & KPI (`main.html`, `kpi.html`)
- **Global Reference:** `metabase/metabase` (Frontend Components)
- **Repository URL:** [https://github.com/metabase/metabase](https://github.com/metabase/metabase)
- **Observed Pattern:** Data-driven KPI cards with Trend Arrows (Up/Down) and Skeleton Loading states.
- **Why It Matters:** يمنع الشاشات البيضاء أثناء التحميل ويعطي سياقاً فورياً للأداء (إيجابي/سلبي).
- **How We Adapt It:** سنطبق Skeleton CSS class أثناء جلب `getDashboardStats()` من الجسر، ونعرض أسهماً خضراء/حمراء بناءً على المقارنة الزمنية إذا توفرت في `SQLite`.
- **Target Files:** `main.html`, `kpi.html`

## 2. Tabular Reports (`sales-reports.html`, `inventory-reports.html`, `accounting-reports.html`)
- **Global Reference:** `frappe/erpnext` (Report Builder & List Views)
- **Repository URL:** [https://github.com/frappe/erpnext](https://github.com/frappe/erpnext)
- **Observed Pattern:** 
  1. شريط فلاتر علوي موحد (Date Range, Group By, Status).
  2. جداول بيانات قابلة للفرز (Sortable Columns) مع إجماليات (Totals) في الصف الأخير.
  3. أزرار تصدير (Export/Print) واضحة.
- **Why It Matters:** يسهل على المحاسبين والمديرين قراءة البيانات الكثيفة وتصديرها للمراجعة.
- **How We Adapt It:** سنعيد بناء الجداول لتستخدم `theme.css` مع شريط `filter-bar`. سنربط الفلاتر بدوال الجسر مثل `getProfitReport()` و `getInventoryReport()`.
- **Target Files:** `sales-reports.html`, `inventory-reports.html`, `accounting-reports.html`, `customer-reports.html`, `fuel-reports.html`

## 3. End of Day (EOD) Report (`eod-report.html`)
- **Global Reference:** `odoo/odoo` (Point of Sale Session Closing)
- **Repository URL:** [https://github.com/odoo/odoo](https://github.com/odoo/odoo)
- **Observed Pattern:** ملخص شامل مقسم إلى أقسام (Cash, Card, Expenses, Expected vs Actual) مع زر إغلاق صريح.
- **Why It Matters:** تقرير نهاية اليوم هو الأهم تشغيلياً للمحطة، ويجب أن يكون منظماً وواضحاً للمطابقة.
- **How We Adapt It:** سننظم `eod-report.html` إلى بطاقات ملخص (Summary Cards) وجداول تفصيلية تقرأ مباشرة من `getEodReport()`.
- **Target Files:** `eod-report.html`

## 4. Forecasts & Analytics (`forecasts.html`)
- **Global Reference:** `RasaHQ/rasa` (Analytics Dashboard) / `apache/superset`
- **Repository URL:** [https://github.com/RasaHQ/rasa](https://github.com/RasaHQ/rasa)
- **Observed Pattern:** رسوم بيانية (Line/Bar Charts) مبنية على بيانات زمنية مع توضيح الثقة (Confidence Intervals).
- **Why It Matters:** التنبؤات تحتاج إلى تصور مرئي (Visual) لفهم الاتجاهات.
- **How We Adapt It:** سنستخدم مكتبة خفيفة (مثل Chart.js إذا كانت متوفرة أو HTML/CSS Bars) متصلة بـ `getPredictionRecords()` من `MainActivity.kt`.
- **Target Files:** `forecasts.html`

---
**القاعدة الإلزامية:**
أي عنصر واجهة (Chart, KPI, Table) لا يوجد له مسار بيانات حقيقي في `DatabaseHelper.kt` أو `MainActivity.kt` سيتم تعليمه كـ GAP أو إخفاؤه، ولن يتم اختراع `Math.random()` أو `Fake Data` لتعبئته.
