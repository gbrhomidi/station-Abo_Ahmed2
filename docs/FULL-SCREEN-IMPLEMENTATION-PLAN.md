# FULL-SCREEN-IMPLEMENTATION-PLAN

بناءً على الجرد الشامل لـ 97 شاشة وتحليل الفجوات ومواصفات نظام التصميم، تم إعداد خطة التنفيذ التالية لتحويل المشروع بالكامل إلى مستوى Enterprise.

## 1. مرحلة الأصول المشتركة (Shared Assets)
- **الهدف:** تطبيق `GLOBAL-DESIGN-SYSTEM-SPECIFICATION` فعلياً.
- **المهام:**
  - مراجعة وتحديث `app/src/main/assets/assets-local/css/theme.css` ليشمل جميع مكونات النظام الموحد.
  - توحيد مكتبات JavaScript الأساسية (مثل `utils.js` أو دوال الجسر المشتركة).

## 2. مرحلة الشاشات ذات الأولوية القصوى (Priority P0 - Core Operations)
- **النطاقات:** Dashboard, SMS, AI, Sales (POS), Customers, Products.
- **الشاشات المستهدفة (أمثلة):** `main.html`, `messages.html`, `ai-assistant.html`, `pos.html`, `customers.html`.
- **المهام:**
  - إزالة CSS المدمج وربط `theme.css`.
  - تنظيف البيانات الوهمية (`Math.random()`, `setTimeout`).
  - التحقق من مسارات `Android Bridge` وتحديث `DatabaseHelper.kt` إذا لزم الأمر.
  - تطبيق أنماط UI/UX العالمية (Cards, Observability Grids, Touch-friendly).

## 3. مرحلة الشاشات ذات الأولوية العالية (Priority P1 - Inventory & Fuel)
- **النطاقات:** Inventory, Warehouses, Tanks, Pumps, Suppliers, Reports.
- **الشاشات المستهدفة (أمثلة):** `inventory.html`, `tanks.html`, `pumps.html`, `fuel-reports.html`.
- **المهام:**
  - توحيد الجداول (Data Tables) لتتوافق مع نظام التصميم.
  - ربط المؤشرات الحقيقية لسعة الخزانات والمخزون.
  - تطبيق فلاتر البحث المتقدمة.

## 4. مرحلة الشاشات الداعمة (Priority P2 - Accounting, HR, Fleet)
- **النطاقات:** Accounting, HR, Fleet.
- **الشاشات المستهدفة (أمثلة):** `ledger.html`, `payroll.html`, `trips.html`.
- **المهام:**
  - تطبيق قوالب النماذج (Form Views) والتبويبات (Tabs).
  - ضمان دقة العمليات الحسابية المرتبطة بـ SQLite.
  - توحيد نوافذ التأكيد (Modals).

## 5. مرحلة الشاشات الإدارية (Priority P3 - Admin & Settings)
- **النطاقات:** Administration, Logs, Settings.
- **الشاشات المستهدفة (أمثلة):** `settings.html`, `roles.html`, `audit-logs.html`.
- **المهام:**
  - توحيد قوائم الإعدادات (Settings Layouts).
  - التأكد من تطبيق صلاحيات الوصول (Permissions) عبر الجسر.
  - مراجعة توافق الـ Dark Mode.

## 6. مرحلة التحقق النهائي (Full Project Audit & Testing)
- **الهدف:** التأكد من تطبيق "Definition of Done".
- **المهام:**
  - تشغيل اختبارات UI لجميع الشاشات الرئيسية.
  - التحقق من عدم وجود `Math.random()` في كامل المشروع.
  - فحص الاستجابة (Responsiveness) ودعم RTL.
  - إعداد تقرير التدقيق النهائي `FINAL-GLOBAL-SCREEN-AUDIT`.

---
**حالة التنفيذ:**
- تم إنجاز `main.html`، `messages.html`، `ai-assistant.html` جزئياً كجزء من مهام سابقة، وسيتم إدراجها ضمن المراجعة الشاملة لضمان التوافق التام مع هذه الخطة.
- الخطة جاهزة للتنفيذ التسلسلي.
