# GLOBAL DOMAIN BENCHMARKS

وفقاً لتوجيهات `GLOBAL-SMS-FULL-PROJECT-UI-ENGINEERING-010`، تم تحديد النطاقات (Domains) الرئيسية لمشروع "محطة أبو أحمد" وتخصيص المراجع العالمية (Global Benchmarks) المناسبة لكل نطاق ليتم دراستها. هذه المرحلة هي بحثية وتوثيقية بحتة.

## 1. SMS / Messaging (الرسائل النصية وعملياتها)
- **Global References:** Jasmin SMS Gateway, Kannel, RapidPro
- **Focus Areas:** Inbox management, Outbox/Queue tracking, DLR (Delivery Reports) status, Retry mechanisms, Message routing, Fallback configurations.
- **Applicable Screens:** `messages.html`, `message-log.html`, `SmsCoreDiagnostics.html`

## 2. AI / Conversational (الذكاء الاصطناعي والمحادثات)
- **Global References:** Rasa, Langfuse, LangChain (Tracing UI)
- **Focus Areas:** Provider management, AI Health (Latency, Success Rate, Quota), Circuit Breaker states, Confidence scores, Clarification UI.
- **Applicable Screens:** `ai-assistant.html`, `maintenance-log.html` (if AI-driven)

## 3. Dashboard / KPI (لوحات التحكم والمؤشرات)
- **Global References:** Metabase, Grafana, Apache Superset
- **Focus Areas:** Real-time data binding, KPI Cards, Trend indicators, Responsive grid layouts, Empty/Error states.
- **Applicable Screens:** `main.html`, `kpi.html`

## 4. Inventory / Products (المخزون والمنتجات)
- **Global References:** ERPNext, Odoo, Dolibarr
- **Focus Areas:** Stock levels, Low stock alerts, Movements tracking, Multi-warehouse management, Categorization, Bulk actions.
- **Applicable Screens:** `products.html`, `inventory.html`, `inventory-movements.html`, `warehouses.html`, `damaged-products.html`

## 5. Sales / POS (المبيعات ونقاط البيع)
- **Global References:** Odoo POS, ERPNext POS
- **Focus Areas:** Fast transaction entry, Receipt templates, Cash deposits, Daily summaries (EOD).
- **Applicable Screens:** `pos.html`, `sales-reports.html`, `fuel-sales.html`, `receipts.html`

## 6. Accounting / Finance (المحاسبة والمالية)
- **Global References:** ERPNext, Odoo Accounting, Firefly III
- **Focus Areas:** Chart of accounts, Ledger entries, Balance sheets, Budgets, Debt tracking, Bank accounts reconciliation.
- **Applicable Screens:** `accounting-reports.html`, `ledger.html`, `balance-sheet.html`, `bad-debts.html`, `expenses.html`

## 7. CRM / Customers (إدارة العملاء)
- **Global References:** SuiteCRM, ERPNext CRM
- **Focus Areas:** Customer profiles, Transaction history, Debt management, Communication logs.
- **Applicable Screens:** `customers.html`, `crm.html`, `customer-debts.html`

## 8. Fuel / Station Operations (عمليات المحطة والوقود)
- **Global References:** Odoo (Fleet/Asset modules), Custom ERP implementations for Gas Stations
- **Focus Areas:** Tank capacities (Current vs Max), Pump allocations, Meter readings, Fuel types, Filling logs.
- **Applicable Screens:** `tanks.html`, `pumps.html`, `tank-filling.html`, `meter-readings.html`

## 9. Administration & Settings (الإدارة والإعدادات)
- **Global References:** ERPNext Admin, Keycloak (for Auth/Roles)
- **Focus Areas:** Role-based access control (RBAC), Company profiles, Printer configurations, Audit logs.
- **Applicable Screens:** `settings.html`, `roles.html`, `users.html`, `company-settings.html`, `audit-logs.html`

## 10. HR & Fleet (الموارد البشرية والمركبات)
- **Global References:** Odoo HR, Odoo Fleet
- **Focus Areas:** Employee attendance, Payroll, Vehicle tracking, Driver assignments, Trip logs.
- **Applicable Screens:** `employees.html`, `payroll.html`, `vehicles.html`, `trips.html`

---
**Next Step:** سنقوم بالبحث في مستودعات GitHub المفتوحة (مثل `frappe/erpnext`, `odoo/odoo`, `jookies/jasmin`) لاستخراج هيكلية الشاشات، الملفات المصدرية، وأنماط الواجهات ليتم إدراجها في `GLOBAL-SCREEN-BENCHMARK-MATRIX`.
