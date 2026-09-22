# Global Settings UI Adaptation Report (MODULE-002)

## 1. الهدف
معالجة شاشات الوحدة الثانية (الإدارة الأساسية: `company-settings`, `stations`, `settings`, `exchange-rates`) بناءً على أفضل الممارسات العالمية لواجهات الإعدادات (Settings UI)، مع ربطها بالوظائف الحقيقية لـ `SQLite` عبر `Bridge`.

## 2. المراجع العالمية المستخدمة
- **Apple/macOS Design Language (e.g. Gok24/settings_ui):**
  - **تخطيط البطاقات المقسمة (Sectioned Cards):** تقسيم الإعدادات إلى مجموعات منطقية داخل بطاقات ذات حواف دائرية.
  - **القائمة الجانبية (Sidebar Navigation):** استخدام قائمة جانبية للتنقل السريع بين أقسام الإعدادات (عام، النظام، المحطات، المزامنة).
- **ERPNext/Odoo:**
  - **حفظ الإعدادات المجمعة (Bulk Save):** زر حفظ رئيسي يجمع التغييرات من مختلف الأقسام ويرسلها دفعة واحدة عبر `Bridge`.
  - **الجداول القابلة للتحرير (Editable Grids):** لشاشات `stations` و `exchange-rates`، استخدام جداول سريعة التحرير بدلاً من النوافذ المنبثقة (Modals) المعقدة لكل عملية.

## 3. الفجوات الحالية في شاشات MODULE-002
1. **تكرار القوالب (Scaffold Boilerplate):** شاشات `company-settings`، `stations`، و`exchange-rates` تعتمد على قالب CRUD المولد تلقائياً (CFG) الذي يعرض البيانات في جداول تقليدية غير مناسبة للإعدادات.
2. **شاشة `settings.html` منفصلة:** تحتوي على تصميم مخصص ومعقد مع تبويبات (Tabs) متعددة، ولكنها غير متناسقة مع نظام التصميم الموحد `.report-screen` الذي بنيناه.
3. **تداخل CSS:** الاعتماد على ستايلات مدمجة (Inline Styles) بدلاً من `theme.css`.

## 4. خطة التطبيق (MODULE-002 - Core Management)
- **الخطوة 1:** إضافة أنماط `Settings UI` (مثل `.settings-layout`, `.settings-sidebar`, `.settings-card`) إلى `theme.css`.
- **الخطوة 2:** تحديث شاشة `settings.html` لتكون "مركز الإعدادات الشامل"، بحيث يمكن أن تضم إعدادات الشركة والنظام.
- **الخطوة 3:** تحديث شاشتي `stations.html` و `exchange-rates.html` لتتبنى تصميم `List/Grid` محسن يتناسب مع الإدارة السريعة، مع تطبيق هوية `report-screen` عليها لتوحيد الواجهات.
- **الخطوة 4:** التأكد من ربط جميع المدخلات بدوال `Bridge` الفعلية (مثل `saveApplicationSettings`, `saveStationRecord`).
- **الخطوة 5:** اختبار التوافق والأداء.
