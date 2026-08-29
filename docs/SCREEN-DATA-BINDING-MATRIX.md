# SCREEN-DATA-BINDING-MATRIX

تحليل مسارات البيانات لكل شاشة لضمان عدم وجود بيانات وهمية.

| Screen File | UI Action/Data | Bridge Method | Kotlin Service | DB Table/Query | Real Data Path? |
|-------------|----------------|---------------|----------------|----------------|-----------------|
| `main.html` | Call `getCurrentUser` | `Android.getCurrentUser` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `main.html` | Call `exitApplication` | `Android.exitApplication` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `main.html` | Call `screenExists` | `Android.screenExists` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `main.html` | Call `getDashboardStats` | `Android.getDashboardStats` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/SmsCoreDiagnostics.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/accounting-reports.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `getActivityLogs` | `Android.getActivityLogs` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `getCurrentUser` | `Android.getCurrentUser` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `getSetting` | `Android.getSetting` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `deleteActivityLog` | `Android.deleteActivityLog` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/activity-log.html` | Call `cleanupActivityLogs` | `Android.cleanupActivityLogs` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `generate` | `Android.generate` | Missing | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getSmsAiConfig` | `Android.getSmsAiConfig` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `configureSmsAiProvider` | `Android.configureSmsAiProvider` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `deleteSmsAiProvider` | `Android.deleteSmsAiProvider` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `setSmsAiProviderEnabled` | `Android.setSmsAiProviderEnabled` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/ai-assistant.html` | Call `getAiHealthStatus` | `Android.getAiHealthStatus` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/assets-v12.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/attendance.html` | Call `getEmployees` | `Android.getEmployees` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/attendance.html` | Call `getShifts` | `Android.getShifts` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/attendance.html` | Call `getStations` | `Android.getStations` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/attendance.html` | Call `getUserActivityLog` | `Android.getUserActivityLog` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/attendance.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/attendance.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/audit-logs.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/backups.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/bad-debts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/bad-debts.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/bad-debts.html` | Call `generate` | `Android.generate` | Missing | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/bad-debts.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/bad-debts.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/balance-sheet.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/balance-sheet.html` | Call `saveBalanceSheet` | `Android.saveBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/balance-sheet.html` | Call `getCurrencies` | `Android.getCurrencies` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `generateBankReport` | `Android.generateBankReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `getBanks` | `Android.getBanks` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `getBankAccounts` | `Android.getBankAccounts` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `getCurrencies` | `Android.getCurrencies` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `saveBank` | `Android.saveBank` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `deleteBank` | `Android.deleteBank` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `saveBankAccount` | `Android.saveBankAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/banks-accounts.html` | Call `deleteBankAccount` | `Android.deleteBankAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/budgets.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/budgets.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/budgets.html` | Call `generate` | `Android.generate` | Missing | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/budgets.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/budgets.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-deposits.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-deposits.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-deposits.html` | Call `generate` | `Android.generate` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-deposits.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-deposits.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/cash-movements.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cash-movements.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cash-movements.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cash-movements.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cash-movements.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cashboxes.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cashboxes.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cashboxes.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cashboxes.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/cashboxes.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `getChartTrialBalance` | `Android.getChartTrialBalance` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `getChartAccounts` | `Android.getChartAccounts` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `getBankAccounts` | `Android.getBankAccounts` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `saveChartAccount` | `Android.saveChartAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `deleteChartAccount` | `Android.deleteChartAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `cloneChartAccount` | `Android.cloneChartAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `moveChartAccount` | `Android.moveChartAccount` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/chart-of-accounts.html` | Call `getChartAccountAudit` | `Android.getChartAccountAudit` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/company-settings.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/contracts.html` | Call `getContracts` | `Android.getContracts` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `getParties` | `Android.getParties` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `getCurrencies` | `Android.getCurrencies` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `getCurrentUser` | `Android.getCurrentUser` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `getContractBundle` | `Android.getContractBundle` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `deleteContract` | `Android.deleteContract` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `cloneContract` | `Android.cloneContract` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `changeContractStatus` | `Android.changeContractStatus` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `generateContractReport` | `Android.generateContractReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `backupDatabase` | `Android.backupDatabase` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/contracts.html` | Call `getContractAudit` | `Android.getContractAudit` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `generateCRMReport` | `Android.generateCRMReport` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `getParties` | `Android.getParties` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `getPartyTypes` | `Android.getPartyTypes` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `savePartyBundle` | `Android.savePartyBundle` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `getPartyCrmBundle` | `Android.getPartyCrmBundle` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/crm.html` | Call `deleteParty` | `Android.deleteParty` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getPayments` | `Android.getPayments` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getCustomerDebts` | `Android.getCustomerDebts` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `makePayment` | `Android.makePayment` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getCustomers` | `Android.getCustomers` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getPartyById` | `Android.getPartyById` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getCustomerSales` | `Android.getCustomerSales` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getCustomerLedger` | `Android.getCustomerLedger` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getDashboardStats` | `Android.getDashboardStats` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getLowStockItems` | `Android.getLowStockItems` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `getSetting` | `Android.getSetting` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-debts.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-reports.html` | Call `generateCRMReport` | `Android.generateCRMReport` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-reports.html` | Call `getCustomerDebts` | `Android.getCustomerDebts` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-reports.html` | Call `getPartyCrmBundle` | `Android.getPartyCrmBundle` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-reports.html` | Call `updatePartyCreditLimit` | `Android.updatePartyCreditLimit` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customer-reports.html` | Call `getPartyTypes` | `Android.getPartyTypes` | MainActivity | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_customers` | `Android.get_customers` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_party_types` | `Android.get_party_types` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_currencies` | `Android.get_currencies` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_users` | `Android.get_users` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_fuel_types` | `Android.get_fuel_types` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_customer_bundle` | `Android.get_customer_bundle` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_customer_ledger` | `Android.get_customer_ledger` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_customer_sales` | `Android.get_customer_sales` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `add_contact` | `Android.add_contact` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `delete_contact` | `Android.delete_contact` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `update_contact` | `Android.update_contact` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `add_address` | `Android.add_address` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `delete_address` | `Android.delete_address` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `update_address` | `Android.update_address` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `delete_customer` | `Android.delete_customer` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `make_payment` | `Android.make_payment` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_party_report` | `Android.get_party_report` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_customer_debts` | `Android.get_customer_debts` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_payments` | `Android.get_payments` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_tanks` | `Android.get_tanks` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/customers.html` | Call `get_products` | `Android.get_products` | Missing | `customers` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getDamagedProducts` | `Android.getDamagedProducts` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `addNotification` | `Android.addNotification` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `updateDamagedProductStatus` | `Android.updateDamagedProductStatus` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `archiveDamagedProduct` | `Android.archiveDamagedProduct` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getDashboardStats` | `Android.getDashboardStats` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getLowStockItems` | `Android.getLowStockItems` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getSetting` | `Android.getSetting` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getWarehouses` | `Android.getWarehouses` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getTanks` | `Android.getTanks` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/damaged-products.html` | Call `getUsers` | `Android.getUsers` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/debt-reminders.html` | Call `addNotification` | `Android.addNotification` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/debt-reminders.html` | Call `addSmsMessage` | `Android.addSmsMessage` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/debt-reminders.html` | Call `makePayment` | `Android.makePayment` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/debt-reminders.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/deliveries.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/deliveries.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/deliveries.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/deliveries.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/deliveries.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/depreciation.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/depreciation.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/depreciation.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/depreciation.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/depreciation.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/devices.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/devices.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/devices.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/devices.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/devices.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/documents.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/documents.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/documents.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/documents.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/documents.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/drivers.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/drivers.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/drivers.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/drivers.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/drivers.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employee-payments.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employee-payments.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employee-payments.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employee-payments.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employee-payments.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employees.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employees.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employees.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employees.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/employees.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/eod-report.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/eod-report.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/eod-report.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/eod-report.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/eod-report.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/equipment-calibration.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/equipment-calibration.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/equipment-calibration.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/equipment-calibration.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/equipment-calibration.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/exchange-rates.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/exchange-rates.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/exchange-rates.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/exchange-rates.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/exchange-rates.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expense-categories.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expense-categories.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expense-categories.html` | Call `generate` | `Android.generate` | Missing | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expense-categories.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expense-categories.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expenses.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expenses.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expenses.html` | Call `generate` | `Android.generate` | Missing | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expenses.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expenses.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getExpirySoonProducts` | `Android.getExpirySoonProducts` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `extendProductExpiry` | `Android.extendProductExpiry` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `markProductExpired` | `Android.markProductExpired` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getDashboardStats` | `Android.getDashboardStats` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getLowStockItems` | `Android.getLowStockItems` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getSetting` | `Android.getSetting` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/expiry-soon.html` | Call `getCategories` | `Android.getCategories` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/first-user-setup.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/fixed-assets.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/fixed-assets.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/fixed-assets.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/fixed-assets.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/fixed-assets.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/forecasts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/forecasts.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/forecasts.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/forecasts.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/forecasts.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-reports.html` | Call `getFuelInventoryReconciliation` | `Android.getFuelInventoryReconciliation` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-reports.html` | Call `getFuelReport` | `Android.getFuelReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-reports.html` | Call `getFuelTransactionDetails` | `Android.getFuelTransactionDetails` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-reports.html` | Call `getFuelTypes` | `Android.getFuelTypes` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-reports.html` | Call `getTanks` | `Android.getTanks` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-sales.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-sales.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-sales.html` | Call `generate` | `Android.generate` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-sales.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-sales.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-types.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-types.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-types.html` | Call `generate` | `Android.generate` | Missing | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-types.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/fuel-types.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-alerts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-alerts.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-alerts.html` | Call `generate` | `Android.generate` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-alerts.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-alerts.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-movements.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/inventory-movements.html` | Call `getWarehouses` | `Android.getWarehouses` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/inventory-movements.html` | Call `getSuppliers` | `Android.getSuppliers` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/inventory-movements.html` | Call `getCustomers` | `Android.getCustomers` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/inventory-movements.html` | Call `getMovements` | `Android.getMovements` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `getMovementStats` | `Android.getMovementStats` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `getInventoryDetails` | `Android.getInventoryDetails` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `saveMovement` | `Android.saveMovement` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `getMovement` | `Android.getMovement` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `deleteMovement` | `Android.deleteMovement` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `generateReport` | `Android.generateReport` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `getInventoryAnalytics` | `Android.getInventoryAnalytics` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-movements.html` | Call `getProductTrend` | `Android.getProductTrend` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/inventory-reports.html` | Call `generateInventoryReport` | `Android.generateInventoryReport` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-reports.html` | Call `getProductDetails` | `Android.getProductDetails` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-reports.html` | Call `getWarehouses` | `Android.getWarehouses` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/inventory-reports.html` | Call `getCategories` | `Android.getCategories` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/invoice-templates.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/invoice-templates.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/invoice-templates.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/invoice-templates.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/invoice-templates.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `generateJournalReport` | `Android.generateJournalReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `getJournalEntries` | `Android.getJournalEntries` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `getJournalItems` | `Android.getJournalItems` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `getChartAccounts` | `Android.getChartAccounts` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `getCurrencies` | `Android.getCurrencies` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `getNextEntryNumber` | `Android.getNextEntryNumber` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `saveJournalEntry` | `Android.saveJournalEntry` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `deleteJournalEntry` | `Android.deleteJournalEntry` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `postJournalEntry` | `Android.postJournalEntry` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/journal-entries.html` | Call `reverseJournalEntry` | `Android.reverseJournalEntry` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/kpi.html` | Call `getKPIDashboard` | `Android.getKPIDashboard` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/kpi.html` | Call `getKPIDetails` | `Android.getKPIDetails` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/ledger.html` | Call `generateLedgerReport` | `Android.generateLedgerReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/ledger.html` | Call `getChartAccounts` | `Android.getChartAccounts` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/ledger.html` | Call `getLedgerStats` | `Android.getLedgerStats` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/ledger.html` | Call `getLedgerEntries` | `Android.getLedgerEntries` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/ledger.html` | Call `getJournalEntryDetails` | `Android.getJournalEntryDetails` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `has_saved_credentials` | `Android.has_saved_credentials` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `save_credentials` | `Android.save_credentials` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `load_credentials` | `Android.load_credentials` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `clear_remembered_credentials` | `Android.clear_remembered_credentials` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `request_password_reset_sms` | `Android.request_password_reset_sms` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `verify_reset_code` | `Android.verify_reset_code` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `reset_password` | `Android.reset_password` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/login.html` | Call `login` | `Android.login` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-log.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-log.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-log.html` | Call `generate` | `Android.generate` | Missing | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-log.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-log.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-requests.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-requests.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-requests.html` | Call `generate` | `Android.generate` | Missing | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-requests.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-requests.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-schedule.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-schedule.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-schedule.html` | Call `generate` | `Android.generate` | Missing | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-schedule.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/maintenance-schedule.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sms_ai_runs` | ⚠️ CONTAINS MOCKS |
| `screens/message-log.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/messages.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/meter-readings.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/meter-readings.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/meter-readings.html` | Call `generate` | `Android.generate` | Missing | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/meter-readings.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/meter-readings.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/notification-inbox.html` | Call `getCurrentUser` | `Android.getCurrentUser` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/notification-inbox.html` | Call `getNotifications` | `Android.getNotifications` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/notification-inbox.html` | Call `markNotificationRead` | `Android.markNotificationRead` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/notification-templates.html` | Call `getNotificationTemplates` | `Android.getNotificationTemplates` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/notification-templates.html` | Call `updateNotificationTemplate` | `Android.updateNotificationTemplate` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/notification-templates.html` | Call `deleteNotificationTemplate` | `Android.deleteNotificationTemplate` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/orders.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/orders.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/orders.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/orders.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/orders.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/party-types.html` | Call `generatePartyTypeReport` | `Android.generatePartyTypeReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/party-types.html` | Call `getPartyTypes` | `Android.getPartyTypes` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/party-types.html` | Call `savePartyType` | `Android.savePartyType` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/party-types.html` | Call `deletePartyType` | `Android.deletePartyType` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payments.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payments.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payments.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payments.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payments.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payroll.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payroll.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payroll.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payroll.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/payroll.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getProductByBarcode` | `Android.getProductByBarcode` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getNextInvoiceNumber` | `Android.getNextInvoiceNumber` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `completeSale` | `Android.completeSale` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `printReceipt` | `Android.printReceipt` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getCustomers` | `Android.getCustomers` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getEntityTypes` | `Android.getEntityTypes` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getEntitiesByType` | `Android.getEntitiesByType` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `getEntityDetails` | `Android.getEntityDetails` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `searchCustomers` | `Android.searchCustomers` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `addCustomer` | `Android.addCustomer` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `searchSales` | `Android.searchSales` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `saveReturn` | `Android.saveReturn` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `processReturn` | `Android.processReturn` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `searchInvoices` | `Android.searchInvoices` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `retrieveInvoice` | `Android.retrieveInvoice` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/pos.html` | Call `salesReport` | `Android.salesReport` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/price-change-log.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-change-log.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-change-log.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-change-log.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-change-log.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-lists.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-lists.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-lists.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-lists.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/price-lists.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/printer-settings.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/printer-settings.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/printer-settings.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/printer-settings.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/printer-settings.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/product-categories.html` | Call `getCategories` | `Android.getCategories` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/product-categories.html` | Call `saveCategory` | `Android.saveCategory` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/product-categories.html` | Call `deleteCategory` | `Android.deleteCategory` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/product-categories.html` | Call `searchCategories` | `Android.searchCategories` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/product-categories.html` | Call `getCategory` | `Android.getCategory` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `getCategories` | `Android.getCategories` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `getUnits` | `Android.getUnits` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `saveProduct` | `Android.saveProduct` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `getProduct` | `Android.getProduct` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `deleteProduct` | `Android.deleteProduct` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `searchProducts` | `Android.searchProducts` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `filterProducts` | `Android.filterProducts` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `exportProducts` | `Android.exportProducts` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `importProducts` | `Android.importProducts` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/products.html` | Call `checkProductExists` | `Android.checkProductExists` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/pumps.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/pumps.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/pumps.html` | Call `generate` | `Android.generate` | Missing | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/pumps.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/pumps.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/quality-checks.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/quality-checks.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/quality-checks.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/quality-checks.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/quality-checks.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/receipt-templates.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipt-templates.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipt-templates.html` | Call `generate` | `Android.generate` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipt-templates.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipt-templates.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipts.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipts.html` | Call `generate` | `Android.generate` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipts.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/receipts.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getReturns` | `Android.getReturns` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `deleteReturn` | `Android.deleteReturn` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getDashboardStats` | `Android.getDashboardStats` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getLowStockItems` | `Android.getLowStockItems` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getSetting` | `Android.getSetting` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `setSetting` | `Android.setSetting` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/returned-products.html` | Call `getCustomers` | `Android.getCustomers` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getPermissions` | `Android.getPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getScreens` | `Android.getScreens` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getUsers` | `Android.getUsers` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getGroups` | `Android.getGroups` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getRoles` | `Android.getRoles` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `deletePermission` | `Android.deletePermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `grantUserPermission` | `Android.grantUserPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getGrantedPermissions` | `Android.getGrantedPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `revokeUserPermission` | `Android.revokeUserPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getGroupPermissions` | `Android.getGroupPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `grantGroupPermission` | `Android.grantGroupPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `revokeGroupPermission` | `Android.revokeGroupPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `grantDelegatedPermission` | `Android.grantDelegatedPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `getDelegatedPermissions` | `Android.getDelegatedPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/roles.html` | Call `revokeDelegatedPermission` | `Android.revokeDelegatedPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `getSalesTransactions` | `Android.getSalesTransactions` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `getParties` | `Android.getParties` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `getFuelTypes` | `Android.getFuelTypes` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `getPumps` | `Android.getPumps` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `saveSaleTransaction` | `Android.saveSaleTransaction` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-log.html` | Call `deleteSaleTransaction` | `Android.deleteSaleTransaction` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-reports.html` | Call `generateSalesReport` | `Android.generateSalesReport` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-reports.html` | Call `getInvoiceDetails` | `Android.getInvoiceDetails` | Missing | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-reports.html` | Call `getProducts` | `Android.getProducts` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-reports.html` | Call `getCustomers` | `Android.getCustomers` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/sales-reports.html` | Call `getShifts` | `Android.getShifts` | MainActivity | `sales_transactions` | ⚠️ CONTAINS MOCKS |
| `screens/screens.html` | Call `getScreens` | `Android.getScreens` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/screens.html` | Call `getModules` | `Android.getModules` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/screens.html` | Call `getPermissions` | `Android.getPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/screens.html` | Call `getScreenPermissions` | `Android.getScreenPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/screens.html` | Call `deleteScreen` | `Android.deleteScreen` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/settings.html` | Static UI | None | None | None | ❌ NO PATH |
| `screens/shifts.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/shifts.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/shifts.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/shifts.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/shifts.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stations.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stations.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stations.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stations.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stations.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/stock-levels.html` | Call `getWarehouses` | `Android.getWarehouses` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/stock-levels.html` | Call `getCategories` | `Android.getCategories` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/stock-levels.html` | Call `getInventorySummary` | `Android.getInventorySummary` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getLowStockProducts` | `Android.getLowStockProducts` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getInventoryAlerts` | `Android.getInventoryAlerts` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getInventoryCharts` | `Android.getInventoryCharts` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getMovements` | `Android.getMovements` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getStockValues` | `Android.getStockValues` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `getAlerts` | `Android.getAlerts` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `filterInventory` | `Android.filterInventory` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `advancedFilter` | `Android.advancedFilter` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `searchProducts` | `Android.searchProducts` | Missing | `products, inventory` | ❌ BROKEN PATH |
| `screens/stock-levels.html` | Call `addProduct` | `Android.addProduct` | MainActivity | `products, inventory` | ✅ VERIFIED |
| `screens/stocktake.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/stocktake.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/stocktake.html` | Call `generate` | `Android.generate` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/stocktake.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/stocktake.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/suppliers.html` | Call `getSuppliers` | `Android.getSuppliers` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/suppliers.html` | Call `deleteSupplier` | `Android.deleteSupplier` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/suppliers.html` | Call `getPartyById` | `Android.getPartyById` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sync-log.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sync-log.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sync-log.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sync-log.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/sync-log.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/system-logs.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/system-logs.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/system-logs.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/system-logs.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/system-logs.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tank-filling.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tank-filling.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tank-filling.html` | Call `generate` | `Android.generate` | Missing | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tank-filling.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tank-filling.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tanks.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tanks.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tanks.html` | Call `generate` | `Android.generate` | Missing | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tanks.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tanks.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `tanks, pumps, fuel_types` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `getPendingTasks` | `Android.getPendingTasks` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `archiveTask` | `Android.archiveTask` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `resolveTask` | `Android.resolveTask` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `generateTaskReport` | `Android.generateTaskReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `backupData` | `Android.backupData` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/tasks.html` | Call `getAuditLogs` | `Android.getAuditLogs` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/trips.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/trips.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/trips.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/trips.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/trips.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getUsers` | `Android.getUsers` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getRoles` | `Android.getRoles` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getStations` | `Android.getStations` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getGroups` | `Android.getGroups` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getPermissions` | `Android.getPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getUserPermissions` | `Android.getUserPermissions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `revokeUserPermission` | `Android.revokeUserPermission` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `deleteUser` | `Android.deleteUser` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getUserSessions` | `Android.getUserSessions` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `terminateSession` | `Android.terminateSession` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getUserActivityLog` | `Android.getUserActivityLog` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `getUserNotifications` | `Android.getUserNotifications` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `markNotificationRead` | `Android.markNotificationRead` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/users.html` | Call `deleteGroup` | `Android.deleteGroup` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-expenses.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-expenses.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-expenses.html` | Call `generate` | `Android.generate` | Missing | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-expenses.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-expenses.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `accounts, transactions` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-tracking.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-tracking.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-tracking.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-tracking.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicle-tracking.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicles.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicles.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicles.html` | Call `generate` | `Android.generate` | Missing | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicles.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/vehicles.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/warehouses.html` | Call `getBalanceSheet` | `Android.getBalanceSheet` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/warehouses.html` | Call `getEodReport` | `Android.getEodReport` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/warehouses.html` | Call `generate` | `Android.generate` | Missing | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/warehouses.html` | Call `getDatabaseInfo` | `Android.getDatabaseInfo` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/warehouses.html` | Call `getBackupHistoryRecords` | `Android.getBackupHistoryRecords` | MainActivity | `products, inventory` | ⚠️ CONTAINS MOCKS |
| `screens/whitelist.html` | Call `getWhitelist` | `Android.getWhitelist` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/whitelist.html` | Call `removeWhitelist` | `Android.removeWhitelist` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/whitelist.html` | Call `addSmsMessage` | `Android.addSmsMessage` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
| `screens/whitelist.html` | Call `updateWhitelist` | `Android.updateWhitelist` | MainActivity | `Unknown` | ⚠️ CONTAINS MOCKS |
