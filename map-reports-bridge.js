const fs = require('fs');

const mainActivityContent = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

const expectedBridgeCalls = {
    'sales-reports.html': ['generateSalesReport', 'getInvoiceDetails', 'getProducts', 'getCustomers', 'getShifts'],
    'eod-report.html': ['getBalanceSheet', 'getEodReport', 'generate', 'getDatabaseInfo', 'getBackupHistoryRecords'],
    'inventory-reports.html': ['generateInventoryReport', 'getProducts', 'getWarehouses'], // Hypothetical
    'customer-reports.html': ['generateCRMReport', 'getCustomers'], // Hypothetical
    'fuel-reports.html': ['getFuelReport', 'getTanks', 'getPumps'], // Hypothetical
    'kpi.html': ['getDashboardStats'], // Hypothetical
    'forecasts.html': ['getPredictionRecords'], // Hypothetical
    'accounting-reports.html': ['getProfitReport', 'getBalanceSheet', 'getLedgerStats'] // Hypothetical
};

console.log("=== Bridge Mapping for Reports Module ===");

Object.entries(expectedBridgeCalls).forEach(([screen, calls]) => {
    console.log(`\nScreen: ${screen}`);
    calls.forEach(call => {
        const regex = new RegExp(`fun ${call}\\s*\\(`, 'i');
        const exists = regex.test(mainActivityContent);
        console.log(`  - ${call}: ${exists ? '✅ Found' : '❌ MISSING in Kotlin'}`);
    });
});
