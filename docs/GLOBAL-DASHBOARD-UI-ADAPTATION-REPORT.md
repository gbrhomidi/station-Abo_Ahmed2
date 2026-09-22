# Global Dashboard UI Adaptation Report

## 1. الهدف
تطبيق أنماط عالمية مستخرجة من مشاريع لوحات التحكم (Dashboards) مثل `DashUI` و `TailAdmin` على الشاشة الرئيسية `main.html` وشاشة المؤشرات `kpi.html` في مشروع `station-Abo_Ahmed2`، مع ربط هذه الواجهات بالوظائف الحقيقية لـ `SQLite` عبر `Bridge` وإزالة أي بيانات وهمية.

## 2. المراجع العالمية المستخدمة
- **DashUI (Tailwind Dashboard):**
  - **نمط بطاقات المؤشرات (KPI Cards):** استخدام تصميم يحتوي على (عنوان، أيقونة في دائرة ملونة شفافة، رقم كبير، ومؤشر نسبة التغير).
  - **الجداول التفاعلية (Active Projects Table):** تصميم جدول مسطح مع ترويسة رمادية فاتحة وخلفية بيضاء نظيفة.
- **TailAdmin:**
  - **تخطيط الشبكة (Grid Layout):** ترتيب البطاقات في شبكة متجاوبة (Responsive Grid) تناسب الشاشات المختلفة.

## 3. الفجوات الحالية في `main.html`
1. **بيانات وهمية (Mock Data):** استخدام `Math.random()` و `setTimeout` لتوليد بيانات مؤشرات الأداء والرسوم البيانية.
2. **تداخل CSS:** وجود ستايلات مدمجة (Inline Styles) ضخمة لا تعتمد على `theme.css`.
3. **عدم ربط الوظائف (Unconnected Logic):** المخططات البيانية (Charts) لا تعكس بيانات `SQLite` الفعلية.

## 4. خطة التطبيق (MODULE-001 - Dashboards)
- **الخطوة 1:** دمج أنماط `KPI Cards` و `Dashboard Grid` المستوحاة من `DashUI` في `theme.css`.
- **الخطوة 2:** تنظيف `main.html` من `Math.random()` و `Chart.js` الوهمي.
- **الخطوة 3:** تحديث دوال `JavaScript` في `main.html` لجلب البيانات الحقيقية من `Bridge.getDashboardStats()` (أو ما يعادلها في `DatabaseHelper`).
- **الخطوة 4:** تطبيق نفس الأنماط على شاشات التقارير الأخرى ضمن الوحدة (`kpi.html`, `sales-reports.html`...).
- **الخطوة 5:** اختبار التوافق والأداء.


## 5. مصادر البحث الفعلية
تمت مراجعة المصادر الأصلية التالية قبل التطبيق، وليس الاعتماد على صور أو README فقط:

| المصدر | النمط الذي تم استخراجه | الرابط |
|---|---|---|
| Metabase | لوحات تفاعلية بفلاتر وتحديث دوري وصلاحيات ومؤشرات قابلة للتتبع | [metabase/metabase](https://github.com/metabase/metabase) |
| Frappe Insights | Query Builder، دمج الفلاتر مع الرسوم، ولوحات البيانات المبنية على نتائج الاستعلام | [frappe/insights](https://github.com/frappe/insights) |
| ERPNext | تنظيم التقارير التشغيلية والمحاسبية وربطها بسياق الأعمال | [frappe/erpnext](https://github.com/frappe/erpnext) |
| Odoo Dashboard Tutorial | Layout موحد، control panel، بطاقات KPI، تحميل كسول للرسوم، وخدمة لإدارة البيانات المحدثة | [Odoo Dashboard Tutorial](https://www.odoo.com/documentation/19.0/developer/tutorials/discover_js_framework/02_build_a_dashboard.html) |
| DashUI | بطاقات KPI ذات عنوان وأيقونة ورقم رئيسي وتخطيط شبكة responsive | [codescandy/dashui-tailwindcss](https://github.com/codescandy/dashui-tailwindcss) |
| TailAdmin | شبكة Dashboard، حالات الجداول، البطاقات، والتجاوب عبر أحجام الشاشات | [TailAdmin dashboard template](https://github.com/TailAdmin/tailadmin-free-tailwind-dashboard-template) |

تمت مواءمة هذه الأنماط مع قيود مشروع محطة أبو أحمد: الواجهات تبقى WebView HTML، مصدر البيانات يبقى `DatabaseHelper`/SQLite، و`Android Bridge` هو المسار الوحيد للوصول إلى البيانات. لم يتم إدخال API وهمية أو بيانات تجريبية إلى المسار التشغيلي.
