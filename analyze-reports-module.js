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

let analysis = [];

screens.forEach(screen => {
    const filePath = path.join('app/src/main/assets', screen);
    if (!fs.existsSync(filePath)) {
        console.error(`Missing file: ${filePath}`);
        return;
    }
    const content = fs.readFileSync(filePath, 'utf8');
    
    // Extract Bridge Methods
    const bridgeMethods = [...content.matchAll(/Android\.([a-zA-Z0-9_]+)\s*\(/g)].map(m => m[1]);
    const invokes = [...content.matchAll(/invoke\s*\(\s*['"]([a-zA-Z0-9_]+)['"]/g)].map(m => m[1]);
    const allBridgeMethods = [...new Set([...bridgeMethods, ...invokes])];
    
    // Check for Mocks
    const hasMock = content.includes('Math.random()') || content.includes('mock') || content.includes('dummy');
    
    // Check UI elements (Charts, Tables, KPIs)
    const hasCharts = content.includes('<canvas') || content.includes('Chart(');
    const hasTables = content.includes('<table');
    const hasFilters = content.includes('type="date"') || content.includes('filter');
    
    analysis.push({
        screen,
        bridgeMethods: allBridgeMethods,
        hasMock,
        ui: { hasCharts, hasTables, hasFilters }
    });
});

console.log(JSON.stringify(analysis, null, 2));
