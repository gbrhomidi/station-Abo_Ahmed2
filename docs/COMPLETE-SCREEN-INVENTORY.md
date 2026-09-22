# COMPLETE-SCREEN-INVENTORY

> تم جرد جميع الشاشات الفعلية الموجودة في المشروع وعددهن **97** شاشة.

## 📊 إحصائيات الجرد
- **الشاشات حسب النطاق:**
  - General / Core: 30
  - Accounting: 11
  - Inventory: 10
  - Reports & Logs: 7
  - Sales: 7
  - Administration: 6
  - Fuel / Station: 6
  - AI: 5
  - HR: 4
  - CRM: 4
  - Fleet: 4
  - SMS / Messaging: 2
  - Dashboard: 1
- **الشاشات حسب الأولوية:** P0(1), P1(8), P2(18), P3(70)
- **فجوات عامة:** 95 شاشة تحتوي بيانات وهمية، 95 شاشة لا تستخدم نظام التصميم الموحد.

## 📑 تفاصيل الشاشات

### 001 - محطة أبو أحمد – لوحة التحكم
- **File:** `main.html`
- **Domain:** AI
- **Priority:** P0
- **Bridge Methods (4):** `getCurrentUser`(✅), `exitApplication`(✅), `screenExists`(✅), `getDashboardStats`(✅)
- **DB Tables:** sms_ai_runs
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)

---

### 002 - تشخيصات جوهر الرسائل
- **File:** `screens/SmsCoreDiagnostics.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 003 - نظام إدارة محطة الوقود – التقارير المحاسبية
- **File:** `screens/accounting-reports.html`
- **Domain:** Accounting
- **Priority:** P1
- **Bridge Methods (1):** `getBalanceSheet`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 004 - سجل النشاطات – نظام محطات الوقود
- **File:** `screens/activity-log.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (6):** `getActivityLogs`(✅), `getCurrentUser`(✅), `getSetting`(✅), `setSetting`(✅), `deleteActivityLog`(✅), `cleanupActivityLogs`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 005 - نظام إدارة محطة الوقود – المساعد الذكي
- **File:** `screens/ai-assistant.html`
- **Domain:** AI
- **Priority:** P3
- **Bridge Methods (10):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getSmsAiConfig`(✅), `configureSmsAiProvider`(✅), `deleteSmsAiProvider`(✅), `setSmsAiProviderEnabled`(✅), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅), `getAiHealthStatus`(✅)
- **DB Tables:** sms_ai_runs
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 006 - نظام إدارة محطة الوقود – إدارة الأصول V12
- **File:** `screens/assets-v12.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 007 - نظام إدارة محطة الوقود – الحضور والانصراف
- **File:** `screens/attendance.html`
- **Domain:** HR
- **Priority:** P2
- **Bridge Methods (6):** `getEmployees`(✅), `getShifts`(✅), `getStations`(✅), `getUserActivityLog`(✅), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 008 - نظام إدارة محطة الوقود – سجلات التدقيق
- **File:** `screens/audit-logs.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 009 - نظام إدارة محطة الوقود – النسخ الاحتياطي
- **File:** `screens/backups.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 010 - نظام إدارة محطة الوقود – الديون المعدومة
- **File:** `screens/bad-debts.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 011 - نظام إدارة محطات الوقود – الميزانية العمومية
- **File:** `screens/balance-sheet.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (3):** `getBalanceSheet`(✅), `saveBalanceSheet`(✅), `getCurrencies`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 012 - نظام إدارة محطات الوقود – البنوك والحسابات
- **File:** `screens/banks-accounts.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (8):** `generateBankReport`(✅), `getBanks`(✅), `getBankAccounts`(✅), `getCurrencies`(✅), `saveBank`(✅), `deleteBank`(✅), `saveBankAccount`(✅), `deleteBankAccount`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 013 - نظام إدارة محطة الوقود – الميزانيات
- **File:** `screens/budgets.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 014 - نظام إدارة محطة الوقود – الإيداعات النقدية
- **File:** `screens/cash-deposits.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 015 - نظام إدارة محطة الوقود – حركات النقدية
- **File:** `screens/cash-movements.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 016 - نظام إدارة محطة الوقود – إدارة الصناديق
- **File:** `screens/cashboxes.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 017 - نظام إدارة محطات الوقود – شجرة الحسابات
- **File:** `screens/chart-of-accounts.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (9):** `getChartTrialBalance`(✅), `getBalanceSheet`(✅), `getChartAccounts`(✅), `getBankAccounts`(✅), `saveChartAccount`(✅), `deleteChartAccount`(✅), `cloneChartAccount`(✅), `moveChartAccount`(✅), `getChartAccountAudit`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 018 - نظام إدارة محطات الوقود – إعدادات النظام
- **File:** `screens/company-settings.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 019 - contracts.html
- **File:** `screens/contracts.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (11):** `getContracts`(✅), `getParties`(✅), `getCurrencies`(✅), `getCurrentUser`(✅), `getContractBundle`(✅), `deleteContract`(✅), `cloneContract`(✅), `changeContractStatus`(✅), `generateContractReport`(✅), `backupDatabase`(✅), `getContractAudit`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 020 - نظام إدارة محطات الوقود – إدارة العلاقات (CRM)
- **File:** `screens/crm.html`
- **Domain:** CRM
- **Priority:** P3
- **Bridge Methods (6):** `generateCRMReport`(✅), `getParties`(✅), `getPartyTypes`(✅), `savePartyBundle`(✅), `getPartyCrmBundle`(✅), `deleteParty`(✅)
- **DB Tables:** customers
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 021 - نظام إدارة محطات الوقود – ديون العملاء
- **File:** `screens/customer-debts.html`
- **Domain:** CRM
- **Priority:** P3
- **Bridge Methods (11):** `getPayments`(✅), `getCustomerDebts`(✅), `makePayment`(✅), `getCustomers`(✅), `getPartyById`(✅), `getCustomerSales`(✅), `getCustomerLedger`(✅), `getDashboardStats`(✅), `getLowStockItems`(✅), `getSetting`(✅), `setSetting`(✅)
- **DB Tables:** customers
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 022 - customer-reports.html
- **File:** `screens/customer-reports.html`
- **Domain:** CRM
- **Priority:** P1
- **Bridge Methods (5):** `generateCRMReport`(✅), `getCustomerDebts`(✅), `getPartyCrmBundle`(✅), `updatePartyCreditLimit`(✅), `getPartyTypes`(✅)
- **DB Tables:** customers
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 023 - أبو أحمد – النظام المتكامل
- **File:** `screens/customers.html`
- **Domain:** CRM
- **Priority:** P3
- **Bridge Methods (21):** `get_customers`(❌), `get_party_types`(❌), `get_currencies`(❌), `get_users`(❌), `get_fuel_types`(❌), `get_customer_bundle`(❌), `get_customer_ledger`(❌), `get_customer_sales`(❌), `add_contact`(❌), `delete_contact`(❌), `update_contact`(❌), `add_address`(❌), `delete_address`(❌), `update_address`(❌), `delete_customer`(❌), `make_payment`(❌), `get_party_report`(❌), `get_customer_debts`(❌), `get_payments`(❌), `get_tanks`(❌), `get_products`(❌)
- **DB Tables:** customers
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 024 - نظام إدارة محطات الوقود – المنتجات التالفة
- **File:** `screens/damaged-products.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (12):** `getDamagedProducts`(✅), `addNotification`(✅), `updateDamagedProductStatus`(✅), `archiveDamagedProduct`(✅), `getDashboardStats`(✅), `getLowStockItems`(✅), `getSetting`(✅), `setSetting`(✅), `getProducts`(✅), `getWarehouses`(✅), `getTanks`(✅), `getUsers`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 025 - تذكيرات الديون
- **File:** `screens/debt-reminders.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (4):** `addNotification`(✅), `addSmsMessage`(✅), `makePayment`(✅), `setSetting`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 026 - نظام إدارة محطة الوقود – إدارة التوصيلات
- **File:** `screens/deliveries.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 027 - نظام إدارة محطة الوقود – الإهلاك
- **File:** `screens/depreciation.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 028 - نظام إدارة محطة الوقود – إدارة الأجهزة
- **File:** `screens/devices.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 029 - نظام إدارة محطة الوقود – إدارة الوثائق
- **File:** `screens/documents.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 030 - نظام إدارة محطة الوقود – إدارة السائقين
- **File:** `screens/drivers.html`
- **Domain:** Fleet
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 031 - نظام إدارة محطة الوقود – دفعات الموظفين
- **File:** `screens/employee-payments.html`
- **Domain:** HR
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 032 - نظام إدارة محطة الوقود – إدارة الموظفين
- **File:** `screens/employees.html`
- **Domain:** HR
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 033 - نظام إدارة محطة الوقود – تقرير نهاية اليوم
- **File:** `screens/eod-report.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 034 - نظام إدارة محطة الوقود – معايرة المعدات
- **File:** `screens/equipment-calibration.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 035 - نظام إدارة محطة الوقود – أسعار الصرف
- **File:** `screens/exchange-rates.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 036 - نظام إدارة محطة الوقود – فئات المصروفات
- **File:** `screens/expense-categories.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 037 - نظام إدارة محطة الوقود – إدارة المصروفات
- **File:** `screens/expenses.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 038 - نظام إدارة محطات الوقود – المنتجات منتهية الصلاحية قريباً
- **File:** `screens/expiry-soon.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (9):** `getExpirySoonProducts`(✅), `extendProductExpiry`(✅), `getProducts`(✅), `markProductExpired`(✅), `getDashboardStats`(✅), `getLowStockItems`(✅), `getSetting`(✅), `setSetting`(✅), `getCategories`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 039 - إضافة المستخدم الأول - نظام إدارة محطات الوقود
- **File:** `screens/first-user-setup.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 040 - نظام إدارة محطة الوقود – الأصول الثابتة
- **File:** `screens/fixed-assets.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 041 - نظام إدارة محطة الوقود – التنبؤات والتحليلات
- **File:** `screens/forecasts.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 042 - fuel-reports.html
- **File:** `screens/fuel-reports.html`
- **Domain:** Fuel / Station
- **Priority:** P1
- **Bridge Methods (5):** `getFuelInventoryReconciliation`(✅), `getFuelReport`(✅), `getFuelTransactionDetails`(✅), `getFuelTypes`(✅), `getTanks`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 043 - نظام إدارة محطة الوقود – مبيعات الوقود
- **File:** `screens/fuel-sales.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 044 - نظام إدارة محطة الوقود – أنواع الوقود
- **File:** `screens/fuel-types.html`
- **Domain:** Fuel / Station
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 045 - نظام إدارة محطة الوقود – تنبيهات المخزون
- **File:** `screens/inventory-alerts.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 046 - تفاصيل الحركة
- **File:** `screens/inventory-movements.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (13):** `getProducts`(✅), `getWarehouses`(✅), `getSuppliers`(✅), `getCustomers`(✅), `getMovements`(❌), `getMovementStats`(❌), `getInventoryDetails`(❌), `saveMovement`(❌), `getMovement`(❌), `deleteMovement`(❌), `generateReport`(❌), `getInventoryAnalytics`(❌), `getProductTrend`(❌)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 047 - inventory-reports.html
- **File:** `screens/inventory-reports.html`
- **Domain:** Inventory
- **Priority:** P1
- **Bridge Methods (4):** `generateInventoryReport`(✅), `getProductDetails`(❌), `getWarehouses`(✅), `getCategories`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 048 - نظام إدارة محطة الوقود – قوالب الفواتير
- **File:** `screens/invoice-templates.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 049 - نظام إدارة محطات الوقود – القيود المحاسبية
- **File:** `screens/journal-entries.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (10):** `generateJournalReport`(✅), `getJournalEntries`(✅), `getJournalItems`(✅), `getChartAccounts`(✅), `getCurrencies`(✅), `getNextEntryNumber`(✅), `saveJournalEntry`(✅), `deleteJournalEntry`(✅), `postJournalEntry`(✅), `reverseJournalEntry`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 050 - kpi.html
- **File:** `screens/kpi.html`
- **Domain:** Dashboard
- **Priority:** P3
- **Bridge Methods (2):** `getKPIDashboard`(✅), `getKPIDetails`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 051 - نظام إدارة محطات الوقود – دفتر الأستاذ العام
- **File:** `screens/ledger.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `generateLedgerReport`(✅), `getChartAccounts`(✅), `getLedgerStats`(✅), `getLedgerEntries`(✅), `getJournalEntryDetails`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 052 - تسجيل الدخول - نظام إدارة محطات الوقود
- **File:** `screens/login.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (8):** `has_saved_credentials`(❌), `save_credentials`(❌), `load_credentials`(❌), `clear_remembered_credentials`(❌), `request_password_reset_sms`(❌), `verify_reset_code`(❌), `reset_password`(❌), `login`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 053 - نظام إدارة محطة الوقود – سجل الصيانة
- **File:** `screens/maintenance-log.html`
- **Domain:** AI
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sms_ai_runs
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 054 - نظام إدارة محطة الوقود – طلبات الصيانة
- **File:** `screens/maintenance-requests.html`
- **Domain:** AI
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sms_ai_runs
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 055 - نظام إدارة محطة الوقود – جدولة الصيانة
- **File:** `screens/maintenance-schedule.html`
- **Domain:** AI
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sms_ai_runs
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 056 - سجل الرسائل
- **File:** `screens/message-log.html`
- **Domain:** SMS / Messaging
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** sms_messages, sms_templates
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 057 - إدارة الرسائل
- **File:** `screens/messages.html`
- **Domain:** SMS / Messaging
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** sms_messages, sms_templates
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)

---

### 058 - نظام إدارة محطة الوقود – قراءات العدادات
- **File:** `screens/meter-readings.html`
- **Domain:** Fuel / Station
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 059 - صندوق الإشعارات
- **File:** `screens/notification-inbox.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (3):** `getCurrentUser`(✅), `getNotifications`(✅), `markNotificationRead`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 060 - إدارة قوالب الإشعارات
- **File:** `screens/notification-templates.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (3):** `getNotificationTemplates`(✅), `updateNotificationTemplate`(✅), `deleteNotificationTemplate`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 061 - نظام إدارة محطة الوقود – إدارة الطلبات
- **File:** `screens/orders.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 062 - نظام إدارة محطات الوقود – أنواع الأطراف
- **File:** `screens/party-types.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (4):** `generatePartyTypeReport`(✅), `getPartyTypes`(✅), `savePartyType`(❌), `deletePartyType`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 063 - نظام إدارة محطة الوقود – إدارة المدفوعات
- **File:** `screens/payments.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 064 - نظام إدارة محطة الوقود – إدارة الرواتب
- **File:** `screens/payroll.html`
- **Domain:** HR
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 065 - نظام إدارة محطات الوقود – نقاط البيع
- **File:** `screens/pos.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (16):** `getProductByBarcode`(✅), `getNextInvoiceNumber`(✅), `completeSale`(✅), `printReceipt`(❌), `getCustomers`(✅), `getEntityTypes`(✅), `getEntitiesByType`(✅), `getEntityDetails`(✅), `searchCustomers`(❌), `addCustomer`(❌), `searchSales`(✅), `saveReturn`(❌), `processReturn`(❌), `searchInvoices`(✅), `retrieveInvoice`(✅), `salesReport`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 066 - نظام إدارة محطة الوقود – سجل تغيير الأسعار
- **File:** `screens/price-change-log.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 067 - نظام إدارة محطة الوقود – قوائم الأسعار
- **File:** `screens/price-lists.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 068 - نظام إدارة محطة الوقود – إعدادات الطابعات
- **File:** `screens/printer-settings.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 069 - نظام إدارة محطات الوقود – إدارة الفئات
- **File:** `screens/product-categories.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (5):** `getCategories`(✅), `saveCategory`(❌), `deleteCategory`(❌), `searchCategories`(❌), `getCategory`(❌)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 070 - نظام إدارة محطات الوقود – إدارة المنتجات
- **File:** `screens/products.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (11):** `getCategories`(✅), `getUnits`(✅), `getProducts`(✅), `saveProduct`(❌), `getProduct`(❌), `deleteProduct`(✅), `searchProducts`(❌), `filterProducts`(❌), `exportProducts`(❌), `importProducts`(❌), `checkProductExists`(❌)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 071 - نظام إدارة محطة الوقود – إدارة المضخات
- **File:** `screens/pumps.html`
- **Domain:** Fuel / Station
- **Priority:** P1
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 072 - نظام إدارة محطة الوقود – فحوصات الجودة
- **File:** `screens/quality-checks.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 073 - نظام إدارة محطة الوقود – قوالب الإيصالات
- **File:** `screens/receipt-templates.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 074 - نظام إدارة محطة الوقود – إدارة الإيصالات
- **File:** `screens/receipts.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 075 - نظام إدارة محطات الوقود – المنتجات المرتجعة
- **File:** `screens/returned-products.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (8):** `getReturns`(✅), `deleteReturn`(✅), `getDashboardStats`(✅), `getLowStockItems`(✅), `getSetting`(✅), `setSetting`(✅), `getProducts`(✅), `getCustomers`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 076 - إدارة الصلاحيات – نظام محطات الوقود
- **File:** `screens/roles.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (15):** `getPermissions`(✅), `getScreens`(✅), `getUsers`(✅), `getGroups`(✅), `getRoles`(✅), `deletePermission`(✅), `grantUserPermission`(✅), `getGrantedPermissions`(✅), `revokeUserPermission`(✅), `getGroupPermissions`(✅), `grantGroupPermission`(✅), `revokeGroupPermission`(✅), `grantDelegatedPermission`(✅), `getDelegatedPermissions`(✅), `revokeDelegatedPermission`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 077 - نظام إدارة محطات الوقود – سجل المبيعات
- **File:** `screens/sales-log.html`
- **Domain:** Sales
- **Priority:** P3
- **Bridge Methods (6):** `getSalesTransactions`(❌), `getParties`(✅), `getFuelTypes`(✅), `getPumps`(✅), `saveSaleTransaction`(❌), `deleteSaleTransaction`(❌)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 078 - نظام إدارة محطات الوقود – تقارير المبيعات
- **File:** `screens/sales-reports.html`
- **Domain:** Sales
- **Priority:** P1
- **Bridge Methods (5):** `generateSalesReport`(❌), `getInvoiceDetails`(❌), `getProducts`(✅), `getCustomers`(✅), `getShifts`(✅)
- **DB Tables:** sales_transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 079 - إدارة شاشات التطبيق – نظام محطات الوقود
- **File:** `screens/screens.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getScreens`(✅), `getModules`(✅), `getPermissions`(✅), `getScreenPermissions`(✅), `deleteScreen`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 080 - نظام إدارة محطات الوقود – إعدادات النظام
- **File:** `screens/settings.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (0):** *None*
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 No Bridge Methods found (Static Screen)
  - 🔴 Not using Global Design System (theme.css)

---

### 081 - نظام إدارة محطة الوقود – إدارة الورديات
- **File:** `screens/shifts.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 082 - نظام إدارة محطة الوقود – إدارة المحطات
- **File:** `screens/stations.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 083 - stock-levels.html
- **File:** `screens/stock-levels.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (13):** `getWarehouses`(✅), `getCategories`(✅), `getInventorySummary`(❌), `getLowStockProducts`(❌), `getInventoryAlerts`(❌), `getInventoryCharts`(❌), `getMovements`(❌), `getStockValues`(❌), `getAlerts`(❌), `filterInventory`(❌), `advancedFilter`(❌), `searchProducts`(❌), `addProduct`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 084 - نظام إدارة محطة الوقود – الجرد والتسوية
- **File:** `screens/stocktake.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 085 - نظام إدارة محطات الوقود – الموردين (AI Edition)
- **File:** `screens/suppliers.html`
- **Domain:** General / Core
- **Priority:** P1
- **Bridge Methods (3):** `getSuppliers`(✅), `deleteSupplier`(❌), `getPartyById`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 086 - نظام إدارة محطة الوقود – سجل المزامنة
- **File:** `screens/sync-log.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 087 - نظام إدارة محطة الوقود – سجلات النظام
- **File:** `screens/system-logs.html`
- **Domain:** Reports & Logs
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 088 - نظام إدارة محطة الوقود – تعبئة الخزانات
- **File:** `screens/tank-filling.html`
- **Domain:** Fuel / Station
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 089 - نظام إدارة محطة الوقود – إدارة الخزانات
- **File:** `screens/tanks.html`
- **Domain:** Fuel / Station
- **Priority:** P1
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** tanks, pumps, fuel_types
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 090 - tasks.html
- **File:** `screens/tasks.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (6):** `getPendingTasks`(✅), `archiveTask`(✅), `resolveTask`(✅), `generateTaskReport`(✅), `backupData`(❌), `getAuditLogs`(❌)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 091 - نظام إدارة محطة الوقود – رحلات المركبات
- **File:** `screens/trips.html`
- **Domain:** Fleet
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 092 - إدارة المستخدمين – نظام محطات الوقود
- **File:** `screens/users.html`
- **Domain:** Administration
- **Priority:** P3
- **Bridge Methods (14):** `getUsers`(✅), `getRoles`(✅), `getStations`(✅), `getGroups`(✅), `getPermissions`(✅), `getUserPermissions`(✅), `revokeUserPermission`(✅), `deleteUser`(✅), `getUserSessions`(✅), `terminateSession`(✅), `getUserActivityLog`(✅), `getUserNotifications`(✅), `markNotificationRead`(✅), `deleteGroup`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

### 093 - نظام إدارة محطة الوقود – مصروفات المركبات
- **File:** `screens/vehicle-expenses.html`
- **Domain:** Accounting
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** accounts, transactions
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 094 - نظام إدارة محطة الوقود – تتبع المركبات
- **File:** `screens/vehicle-tracking.html`
- **Domain:** Fleet
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 095 - نظام إدارة محطة الوقود – إدارة المركبات
- **File:** `screens/vehicles.html`
- **Domain:** Fleet
- **Priority:** P2
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 096 - نظام إدارة محطة الوقود – إدارة المستودعات
- **File:** `screens/warehouses.html`
- **Domain:** Inventory
- **Priority:** P3
- **Bridge Methods (5):** `getBalanceSheet`(✅), `getEodReport`(✅), `generate`(❌), `getDatabaseInfo`(✅), `getBackupHistoryRecords`(✅)
- **DB Tables:** products, inventory
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Missing Kotlin Bridge Implementation
  - 🔴 Not using Global Design System (theme.css)

---

### 097 - القائمة البيضاء - إدارة الأرقام
- **File:** `screens/whitelist.html`
- **Domain:** General / Core
- **Priority:** P3
- **Bridge Methods (4):** `getWhitelist`(✅), `removeWhitelist`(✅), `addSmsMessage`(✅), `updateWhitelist`(✅)
- **DB Tables:** *Unknown/Static*
- **Identified Gaps:**
  - 🔴 Contains Fake/Mock Data (Math.random or setTimeout)
  - 🔴 Not using Global Design System (theme.css)

---

