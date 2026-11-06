# SQLite Query-Plan Validation

تمت إضافة فهارس مركبة في `DatabaseHelper` ضمن ترقية قاعدة البيانات V53. الفهارس تبدأ دائماً بـ `station_id` لأن كل قراءات لوحة التحكم والديون مقيدة بالمحطة.

## الفهارس الأساسية

- `idx_sales_business_day` (موجود مسبقاً): المبيعات اليومية.
- `idx_sales_debt_station_deleted_due`: الفواتير المفتوحة المرتبة والمفلترة حسب تاريخ الاستحقاق.
- `idx_sales_debt_station_customer_deleted`: قراءات ذمم العميل ضمن المحطة.
- `idx_sales_debt_station_method_due`: قراءات الدين التي تبدأ بنطاق المحطة وطريقة الدفع وتاريخ الاستحقاق.
- `idx_sales_dashboard_customer`: العملاء النشطون.
- `idx_dashboard_payments_sale_day`: التحصيلات اليومية المرتبطة بالمبيعات.
- `idx_dashboard_inventory_warehouse_product` و`idx_dashboard_warehouses_station`: قيمة المخزون والمخزون المنخفض.
- `idx_dashboard_products_scope_expiry`: انتهاء المنتجات قريباً.
- `idx_dashboard_tasks_scope_state`: المهام المعلقة.
- `idx_dashboard_tanks_scope`: إجمالي الخزانات.

## تغيير الاستعلامات

استُبدل نمط `date(due_date)` بنطاق مباشر على قيمة ISO المخزنة في `due_date`:

```sql
 due_date >= substr(?, 1, 10)
 AND due_date < date(substr(?, 1, 10), '+8 days')
```

وبذلك لا تُغلّف قيمة العمود بدالة تمنع استخدام الجزء الخاص بالتاريخ من الفهرس. الحساب على قيمة معامل التاريخ آمن، بينما يبقى العمود قابلاً للفهرسة.

## التحقق

يشغل CI الملف `sqlite-query-plan-regression-test.py`. الاختبار ينفذ `EXPLAIN QUERY PLAN` على استعلامات ممثلة ويتحقق من ظهور أسماء الفهارس المتوقعة. كما يمكن فحص قاعدة جهاز اختبار Android عبر:

```sql
EXPLAIN QUERY PLAN
SELECT id FROM sales_transactions
WHERE station_id = ? AND is_deleted = 0
  AND due_date >= ? AND due_date < ?
ORDER BY due_date, id;
```

لا ينفذ التطبيق `EXPLAIN QUERY PLAN` على بيانات المستخدم في التشغيل العادي؛ فهو أداة تحقق/اختبار فقط، حتى لا تُضاف كلفة تشخيصية إلى مسار لوحة التحكم.
