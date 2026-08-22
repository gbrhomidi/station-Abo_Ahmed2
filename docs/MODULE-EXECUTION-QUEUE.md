# MODULE-EXECUTION-QUEUE

ترتيب التنفيذ الهندسي بناءً على الأهمية التجارية والاعتماديات (Business Criticality & Dependencies).

**ملاحظة هامة:** الوحدات من MODULE-001 إلى MODULE-006 تم إنجازها وظيفياً (Backend/Bridge/SQLite) في مراحل سابقة. المرحلة الحالية ستركز على **المعالجة المرئية وتطبيق أنماط UI العالمية** على شاشات هذه الوحدات بالترتيب، ثم الانتقال للوحدات المتبقية.

## Priority P0
- **[reports] التقارير والتحليلات (MODULE-001)** (9 screens) - *تمت معالجة Backend*
  - `main.html`
  - `sales-reports.html`
  - `eod-report.html`
  - `inventory-reports.html`
  - `customer-reports.html`
  - `fuel-reports.html`
  - `kpi.html`
  - `forecasts.html`
  - `accounting-reports.html`
- **[sales] المبيعات والورديات (MODULE-002)** (6 screens) - *تمت معالجة Backend*
  - `pos.html`
  - `shifts.html`
  - `sales-log.html`
  - `orders.html`
  - `deliveries.html`
  - `fuel-sales.html`
- **[parties] الأطراف والعملاء (MODULE-003)** (6 screens) - *تمت معالجة Backend*
  - `customers.html`
  - `suppliers.html`
  - `party-types.html`
  - `contracts.html`
  - `bad-debts.html`
  - `crm.html`
- **[inventory] المخزون والمستودعات (MODULE-004)** (6 screens) - *تمت معالجة Backend*
  - `warehouses.html`
  - `stock-levels.html`
  - `inventory-movements.html`
  - `stocktake.html`
  - `damaged-products.html`
  - `inventory-alerts.html`

## Priority P1
- **[products] المنتجات والوقود (MODULE-005)** (5 screens) - *تمت معالجة Backend*
  - `fuel-types.html`
  - `products.html`
  - `product-categories.html`
  - `price-lists.html`
  - `price-change-log.html`
- **[tanks] الخزانات والمضخات (MODULE-006)** (6 screens) - *تمت معالجة Backend*
  - `tanks.html`
  - `pumps.html`
  - `meter-readings.html`
  - `tank-filling.html`
  - `quality-checks.html`
  - `equipment-calibration.html`
- **[finance] المالية والحسابات** (13 screens)
  - `payments.html`
  - `receipts.html`
  - `cashboxes.html`
  - `cash-movements.html`
  - `banks-accounts.html`
  - `chart-of-accounts.html`
  - `journal-entries.html`
  - `expense-categories.html`
  - `expenses.html`
  - `budgets.html`
  - `cash-deposits.html`
  - `ledger.html`
  - `balance-sheet.html`
- **[notifications] الإشعارات والرسائل** (7 screens)
  - `notification-templates.html`
  - `notification-inbox.html`
  - `messages.html`
  - `message-log.html`
  - `debt-reminders.html`
  - `whitelist.html`
  - `SmsCoreDiagnostics.html`
- **[ai] المساعد الذكي** (1 screens)
  - `ai-assistant.html`

## Priority P2
- **[vehicles] المركبات والسائقين** (5 screens)
  - `vehicles.html`
  - `drivers.html`
  - `vehicle-tracking.html`
  - `trips.html`
  - `vehicle-expenses.html`
- **[hr] الموارد البشرية** (4 screens)
  - `employees.html`
  - `attendance.html`
  - `payroll.html`
  - `employee-payments.html`
- **[assets] الأصول والصيانة** (6 screens)
  - `fixed-assets.html`
  - `assets-v12.html`
  - `maintenance-requests.html`
  - `maintenance-schedule.html`
  - `maintenance-log.html`
  - `depreciation.html`

## Priority P3
- **[core] البيانات الأساسية** (4 screens)
  - `company-settings.html`
  - `stations.html`
  - `settings.html`
  - `exchange-rates.html`
- **[system] النظام والسجلات** (3 screens)
  - `system-logs.html`
  - `audit-logs.html`
  - `documents.html`
- **[sync] المزامنة والنسخ الاحتياطي** (3 screens)
  - `devices.html`
  - `sync-log.html`
  - `backups.html`
- **[printing] الطباعة والقوالب** (3 screens)
  - `printer-settings.html`
  - `receipt-templates.html`
  - `invoice-templates.html`

