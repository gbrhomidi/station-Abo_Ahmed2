const fs = require('fs');
const path = require('path');

const screens = [
  'main.html',
  'screens/sales-reports.html',
  'screens/eod-report.html',
  'screens/inventory-reports.html',
  'screens/customer-reports.html',
  'screens/fuel-reports.html',
  'screens/kpi.html',
  'screens/forecasts.html',
  'screens/accounting-reports.html'
];

let inventory = `# MODULE-001-INVENTORY: Reports & Analytics

**Module Purpose:** توفير لوحات تحكم وتقارير تحليلية ومالية وتشغيلية متكاملة لمديري ومحاسبي المحطة، مبنية على بيانات SQLite الفعلية.
**Business Domain:** Analytics, Finance, Operations.
**Dependencies:** Sales, Inventory, Fuel, Parties, Accounting.

## 1. Screen Inventory

| Screen | File Path | Business Purpose |
|---|---|---|
| Dashboard | \`main.html\` | ملخص أداء المحطة (KPIs, Charts) للمدير. |
| Sales Reports | \`screens/sales-reports.html\` | تحليل المبيعات والورديات والفواتير. |
| EOD Report | \`screens/eod-report.html\` | تقرير الإغلاق اليومي والمطابقة المالية. |
| Inventory Reports | \`screens/inventory-reports.html\` | جرد وحركة وقيمة المخزون. |
| Customer Reports | \`screens/customer-reports.html\` | تحليل الأطراف والمديونيات. |
| Fuel Reports | \`screens/fuel-reports.html\` | حركة المحروقات والخزانات والمضخات. |
| KPI Dashboard | \`screens/kpi.html\` | مؤشرات الأداء المتقدمة والتنبيهات. |
| Forecasts | \`screens/forecasts.html\` | تنبؤات الذكاء الاصطناعي بناءً على البيانات التاريخية. |
| Accounting Reports | \`screens/accounting-reports.html\` | الميزانية العمومية وحساب الأرباح والخسائر والأستاذ العام. |

## 2. Kotlin & Bridge Dependencies

| Screen | Required Bridge Methods |
|---|---|
| Dashboard | \`getDashboardStats\` |
| Sales Reports | \`generateSalesTransactionReport\`, \`retrieveInvoice\`, \`getProducts\`, \`getCustomers\`, \`getShifts\` |
| EOD Report | \`getEodReport\`, \`getBalanceSheet\`, \`getDatabaseInfo\`, \`getBackupHistoryRecords\` |
| Inventory Reports | \`generateInventoryReport\`, \`getWarehouses\`, \`getCategories\`, \`getInventoryProductDetails\` |
| Customer Reports | \`generateCRMReport\`, \`getCustomers\` |
| Fuel Reports | \`getFuelReport\`, \`getTanks\`, \`getPumps\` |
| KPI Dashboard | \`getDashboardStats\` |
| Forecasts | \`getPredictionRecords\` |
| Accounting Reports | \`getProfitReport\`, \`getBalanceSheet\`, \`getLedgerStats\` |

## 3. Database Tables Involved
\`sales_transactions\`, \`sales_items\`, \`inventory_items\`, \`inventory_movements\`, \`tanks\`, \`pumps\`, \`fuel_readings\`, \`parties\`, \`accounts\`, \`ledger_entries\`, \`shifts\`.

## 4. Permissions Required
\`reports:read\`, \`sales:read\`, \`inventory:read\`, \`accounting:read\`.
`;

fs.writeFileSync(path.join('docs', 'MODULE-001-INVENTORY.md'), inventory);
console.log('Created MODULE-001-INVENTORY.md');
