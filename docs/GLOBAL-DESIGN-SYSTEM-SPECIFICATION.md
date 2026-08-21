# GLOBAL DESIGN SYSTEM SPECIFICATION

## 1. Core Principles
- **Arabic-First & RTL:** All UI elements must naturally flow right-to-left. `dir="rtl"` on `<html>`.
- **Mobile-First:** Fluid grids and flexible components that scale down to mobile without horizontal scrolling.
- **WebView Optimized:** Touch-friendly targets (min 44x44px), fast CSS transitions, no heavy JS frameworks (Vanilla JS preferred).
- **Data-Driven:** No UI element should exist without a real data path. Empty states must be informative.

## 2. Typography & Colors
- **Font Family:** `Tajawal`, `Cairo`, or system default sans-serif.
- **Primary Color:** `#0066cc` (Trust, Corporate).
- **Secondary/Accent:** `#f39c12` (Alerts, Warnings).
- **Status Colors:** 
  - Success: `#2ecc71`
  - Danger: `#e74c3c`
  - Info: `#3498db`
- **Background (Light):** `#f8f9fa`
- **Background (Dark):** `#121212` (Triggered via `[data-theme="dark"]`).

## 3. UI Components
### 3.1. Cards & Containers
- **Border Radius:** `8px` (Modern, soft edges).
- **Shadows:** Subtle box-shadow for depth (`0 2px 4px rgba(0,0,0,0.05)`).
- **Padding:** Standardized `16px` or `24px` for internal content.

### 3.2. Data Tables & Grids
- **Header:** Sticky header with distinct background.
- **Rows:** Zebra striping or hover effects for readability.
- **Pagination:** Clean, numbered pagination at the bottom.
- **Empty State:** Icon + "لا توجد بيانات" + زر إضافة (إذا كان مسموحاً).

### 3.3. Forms & Inputs
- **Input Fields:** `100%` width on mobile, grid layout on desktop. Clear labels above inputs.
- **Validation:** Red borders for errors, helper text below the input.
- **Buttons:** Primary (Solid), Secondary (Outline), Danger (Red Solid). Min height `44px`.

### 3.4. Modals & Dialogs
- **Overlay:** Dark semi-transparent background (`rgba(0,0,0,0.5)`).
- **Position:** Centered on desktop, bottom-sheet style on mobile (optional).
- **Actions:** Right-aligned (in RTL) action buttons (Cancel / Confirm).

## 4. Implementation Strategy (CSS)
سيتم تجميع هذه المواصفات في ملف `app/src/main/assets/assets-local/css/theme.css` ليكون المصدر الوحيد (Single Source of Truth) لتصميم جميع الـ 97 شاشة.

- يجب إزالة الستايلات المدمجة (Inline Styles) من جميع الشاشات.
- يجب ربط جميع الشاشات بملف `theme.css`.
- يجب تطبيق الكلاسات القياسية (مثل `.btn-primary`, `.card`, `.table-responsive`) بدلاً من كتابة CSS مخصص لكل شاشة.

---
**Next Step:** سيتم إعداد خطة التنفيذ الشاملة (FULL-SCREEN-IMPLEMENTATION-PLAN) للبدء بتطبيق هذا النظام على جميع الشاشات تدريجياً.


## 5. حالة التطبيق الفعلي
تم تطبيق رابط `theme.css` على **97/97** ملف HTML في أصول المشروع، بما في ذلك `main.html` و96 شاشة داخل `screens/`. وتمت إضافة اختبارات تغطية آلية تتحقق من وجود رابط واحد فقط للنظام، ووجود RTL وviewport وcharset، وتوفر حالات التحميل والفراغ والخطأ والترقيم والاستجابة والطباعة. نتيجة التحقق المحلي: `Design-system coverage PASS`.

يبقى التحقق البصري الكامل على أجهزة Android وWebView الفعلية ضمن نطاق **RUNTIME VERIFIED** المطلوب لاحقًا؛ أما تغطية الروابط وسلامة البنية الأساسية فهي **STATIC VERIFIED** و**LOCAL TEST VERIFIED**.
