# GLOBAL SCREEN RESEARCH: Reports Module

هذه الوثيقة تثبت المراجع العالمية الفعلية التي تم استخراج أنماط شاشات وحدة `reports` منها، مع تحديد الملف الدقيق وطريقة التكييف، استجابة لشرط الأدلة الفعلية (Evidence وليس ادعاءات).

## المراجع العالمية والتكييف الهندسي

يعرض الجدول التالي المراجع العالمية المعتمدة لكل مجموعة من الشاشات، مع تحديد الملفات الدقيقة في المستودعات مفتوحة المصدر، والأنماط المستخرجة، وقرارات التكييف التي تم تطبيقها على مشروع المحطة لضمان التوافق مع بنية البيانات (SQLite).

| Screens | Global Reference | Exact File/Directory | Observed Pattern | Adaptation Decision |
|---|---|---|---|---|
| **Dashboard & KPIs**<br>`main.html`, `kpi.html` | Metabase (`metabase/metabase`) | `frontend/src/metabase/dashboard/containers/DashboardApp/DashboardApp.tsx` | استخدام بطاقات KPI قابلة للنقر مع مؤشر اتجاه (Trend) يعتمد على بيانات مقارنة حقيقية، واستخدام Skeleton Loading بدل الشاشات البيضاء. | تم تكييف النمط عبر إضافة Skeleton Loading. أزيلت مؤشرات الاتجاه الثابتة (`0%`) لأن قاعدة البيانات لا تدعم مقارنة فترات سابقة تلقائياً في `getDashboardStats`. تم ربط كل KPI بمسار `SQLite` مباشر. |
| **Tabular Reports**<br>`sales-reports.html`, `inventory-reports.html`, `customer-reports.html` | ERPNext (`frappe/frappe`) | `frappe/public/js/frappe/views/reports/report_view.js` | شريط فلاتر موحد في الأعلى، جدول بيانات ديناميكي، وملخص (Totals) أسفل الجدول، مع أزرار تصدير موحدة. | تم إنشاء `filter-bar` موحد. يتم إرسال الفلاتر كـ JSON إلى Bridge. إذا لم تتوفر دالة في `MainActivity.kt`، تظهر رسالة `GAP` بدل المحاكاة. أضيفت ميزة البحث المحلي للنتائج المحملة. |
| **End of Day Report**<br>`eod-report.html` | Odoo (`odoo/odoo`) | `addons/point_of_sale/static/src/app/components/popups/closing_popup/closing_popup.js` | تقسيم تقرير الإغلاق إلى أقسام واضحة (Cash, Bank, Expected, Actual) مع زر إغلاق صريح وحالات تأكيد. | تم تكييف النمط ليعتمد كلياً على `getEodReport()` و `getBalanceSheet()`. أي قيم لا يعيدها الجسر تترك فارغة ولا يتم محاكاتها. |
| **Analytics & Forecasts**<br>`forecasts.html`, `accounting-reports.html` | Apache Superset (`apache/superset`) | `superset-frontend/src/dashboard/components/DashboardBuilder/DashboardBuilder.tsx` | رسوم بيانية (Charts) تعتمد حصرياً على البيانات العائدة من الـ API، مع إظهار `Empty State` واضح عند غياب البيانات. | تم تطبيق النمط؛ الرسوم البيانية في `forecasts.html` لن تُعرض ما لم تعد دالة `getPredictionRecords()` بيانات فعلية. مُنع استخدام بيانات `Hardcoded` للرسوم. |
