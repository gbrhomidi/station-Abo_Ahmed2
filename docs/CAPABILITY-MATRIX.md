# MODULE-001 CAPABILITY MATRIX & DATABASE ALIGNMENT

يتم تقييم كل قدرة وظيفية (Capability) مطلوبة عالمياً في شاشات التقارير مقابل تنفيذها الفعلي في واجهة المستخدم، وتوفرها في الجسر البرمجي (Bridge)، ودعم قاعدة البيانات (SQLite) لها.

## 1. Tabular Reports (Sales, Inventory, Fuel, Customer)

| Capability | UI Requirement | Bridge Method | DatabaseHelper | SQLite Support | Status |
|---|---|---|---|---|---|
| **Local Search** | Yes | N/A (Client-side) | N/A | N/A | IMPLEMENTED |
| **Backend Search** | No | `getOperationalReport(query)` | `getOperationalReport` | Yes (LIKE clauses) | SUPPORTED BUT NOT EXPOSED |
| **Date Filters** | Yes | Various (e.g., `generateSalesTransactionReport`) | Yes | Yes (BETWEEN dates) | IMPLEMENTED |
| **Entity Filters** | Yes | Various (Shift, Invoice, Category) | Yes | Yes (WHERE clauses) | IMPLEMENTED |
| **Sorting** | Yes | N/A (Client-side / Implicit) | `ORDER BY` | Yes | IMPLEMENTED |
| **Pagination** | No | N/A | `LIMIT/OFFSET` | Yes | BACKEND GAP |
| **Export (CSV/PDF)** | Yes | N/A (Client-side `jspdf`/`csv`) | N/A | N/A | IMPLEMENTED |
| **Print** | Yes | N/A (Browser Print) | N/A | N/A | IMPLEMENTED |
| **Empty State** | Yes | Handled in JS | Handled in Kotlin | N/A | IMPLEMENTED |
| **Loading State** | Yes | Skeleton/Spinner in JS | N/A | N/A | IMPLEMENTED |
| **Error State** | Yes | Toast/Alert in JS | `errorResponse` | N/A | IMPLEMENTED |
| **Drill-down** | Yes | Modal triggers | `getInvoiceDetails` | Yes | IMPLEMENTED |

## 2. Analytical & Dashboard Screens (Main, KPI, Forecasts, Accounting)

| Capability | UI Requirement | Bridge Method | DatabaseHelper | SQLite Support | Status |
|---|---|---|---|---|---|
| **KPI Calculation** | Yes | `getDashboardStats` | `getDashboardStats` | Yes (SUM/COUNT) | IMPLEMENTED |
| **Historical Trends** | Yes | `getDashboardStats` | `getDashboardStats` | No (Lacks previous period calculation) | DATABASE GAP |
| **Charts/Graphs** | Yes | Chart.js / ApexCharts | Returns JSON Arrays | Yes | IMPLEMENTED |
| **Forecast Generation**| No (Read-only)| `getPredictionRecords` | `getOperationalRows` | Yes (Reads `predictions` table) | IMPLEMENTED |
| **Real-time Refresh** | Yes | Manual Reload / Offline events | N/A | N/A | IMPLEMENTED |
| **Audit Trail** | Yes | N/A (Read-only reports) | N/A | N/A | NOT APPLICABLE |
| **Permissions** | Yes | Handled before UI load | `checkPermission` | Yes | IMPLEMENTED |

## 3. General UX Capabilities (All Screens)

| Capability | UI Requirement | Status | Justification |
|---|---|---|---|
| **Responsive UX** | Yes | IMPLEMENTED | CSS Grid/Flexbox used with `@media` queries. |
| **RTL Support** | Yes | IMPLEMENTED | `dir="rtl"` and `direction: rtl` enforced globally. |
| **Dark/Light Mode** | Yes | IMPLEMENTED | `[data-theme="dark"]` toggles applied via `theme.css`. |
| **Touch UX** | Yes | IMPLEMENTED | Large touch targets (min 44px) and bottom navigation bars used. |
| **Validation** | Yes | IMPLEMENTED | Input validation applied on date ranges and search fields. |

## 4. Capability Gap Analysis & Justification

- **DATABASE GAP: Historical Trends:**
  - **Issue:** The UI initially displayed `+0%` or `0.0%` for KPIs, simulating a trend.
  - **Root Cause:** The `DatabaseHelper.getDashboardStats()` method aggregates current totals but does not calculate the delta against a previous period (e.g., last month).
  - **Resolution:** Instead of faking the data or removing the KPI entirely, the UI was updated to explicitly show "No comparison data" (`—`). This preserves the UI structure while maintaining data integrity.

- **BACKEND GAP: Pagination:**
  - **Issue:** Tabular reports load all records matching the date filter at once.
  - **Root Cause:** While SQLite supports `LIMIT` and `OFFSET`, the Bridge methods (e.g., `generateSalesTransactionReport`) do not currently expose pagination parameters to the UI.
  - **Resolution:** Client-side rendering handles the current volume. Pagination remains a non-blocking backend gap for future optimization if data volume grows significantly.

- **UI REMOVAL: "Readings" Tab in Fuel Reports:**
  - **Issue:** The Fuel Reports screen included a tab for "Readings" (القراءات).
  - **Root Cause:** The `getFuelReport` Bridge method does not return fuel reading data, and there is no `getReadings` API implemented in Kotlin.
  - **Resolution:** The tab was removed. This is an explicitly documented gap, preventing users from interacting with a dead workflow. It cannot be marked as "PASS" because the capability is missing, but it is a NON-BLOCKING GAP for the current module scope.
