# Inventory Movements — Benchmark & Implementation Record

## Scope

This benchmark was used to extract functional patterns for the existing offline Android/WebView inventory screen. It does not copy code or protected UI.

## Sources reviewed

- ERPNext documentation: Stock Transactions and Stock Ledger.
- Odoo documentation: Moves History, Moves Analysis, and stock valuation.
- GitHub: open-source inventory/ERP projects using movement ledgers and stock-balance projections.
- GitLab: inventory platforms exposing stock-movement operations.
- Product Hunt: inventory/fulfillment products with warehouse-level visibility and operational reporting.
- Indie Hackers: inventory products and implementation discussions emphasizing real-time stock, suppliers, reorder points, and movement visibility.

## Repeated patterns relevant to this project

1. A movement history should expose date, reference, item, quantity, source/destination and location.
2. Transfers should be represented as two-sided stock effects and committed atomically.
3. Adjustments should record a reason and a signed quantity change.
4. Reports need date and location filters and should operate on the transaction source, not UI counters.
5. Stock valuation is tied to movement quantity and unit/total value.
6. Multi-location visibility is useful, but the project should retain its simpler warehouse/tank model.
7. Low-stock and movement-frequency indicators are useful only where the local database can calculate them offline.

## Applied to this project

### Applied directly

- SQLite-backed movement statistics.
- Product ID and fuel ID based filtering.
- Warehouse/tank filters.
- Unified movement presentation while preserving separate product/fuel storage models.
- Real report generation from SQLite.
- Native Android CSV export through FileProvider.
- Product/fuel selection modals populated from SQLite.
- Atomic manual fuel movements through `tanks` + `tank_ledger`.
- Daily movement analysis for products and fuel.

### Deliberately not added

- Lot/serial tracking: not required by the current inventory schema.
- Demand forecasting/anomaly detection: current data does not justify reliable forecasts.
- Approval workflows for every movement: existing permission/workflow model was not sufficient to add safely in this task.
- External/cloud synchronization: conflicts with the screen's offline-first requirement.
- PDF/XLSX libraries: CSV is the smallest reliable offline export format for the current Android stack.

## Product vs Fuel model

Products use:

`products` → `inventory_levels` → `inventory_movements`

Fuel uses:

`fuel_types` → `tanks` → `fuel_sales` / `tank_refills` / `tank_ledger`

The UI can display both through a unified read contract, but writes remain type-specific.
