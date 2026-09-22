const fs = require('fs');
const path = require('path');

const screensDir = 'app/src/main/assets/screens';
const rootDir = 'app/src/main/assets';

const files = fs.readdirSync(screensDir)
    .filter(f => f.endsWith('.html'))
    .map(f => path.join(screensDir, f));

// Add main.html
if (fs.existsSync(path.join(rootDir, 'main.html'))) {
    files.unshift(path.join(rootDir, 'main.html'));
}

const inventory = [];

files.forEach((file, index) => {
    const content = fs.readFileSync(file, 'utf8');
    const fileName = path.basename(file);
    
    // Extract Screen Name (from <title> or header)
    let screenName = fileName;
    const titleMatch = content.match(/<title>(.*?)<\/title>/i);
    if (titleMatch && titleMatch[1]) {
        screenName = titleMatch[1].trim();
    } else {
        const h1Match = content.match(/<h[12][^>]*>(.*?)<\/h[12]>/i);
        if (h1Match && h1Match[1]) {
            screenName = h1Match[1].replace(/<[^>]+>/g, '').trim();
        }
    }
    
    // Extract JS Functions
    const jsFunctions = [...content.matchAll(/function\s+([a-zA-Z0-9_]+)\s*\(/g)].map(m => m[1]);
    const arrowFunctions = [...content.matchAll(/(?:const|let|var)\s+([a-zA-Z0-9_]+)\s*=\s*(?:\([^)]*\)|[a-zA-Z0-9_]+)\s*=>/g)].map(m => m[1]);
    const allFunctions = [...new Set([...jsFunctions, ...arrowFunctions])];
    
    // Extract Bridge Methods
    const bridgeMethods = [...content.matchAll(/Android\.([a-zA-Z0-9_]+)\s*\(/g)].map(m => m[1]);
    const apiCalls = [...content.matchAll(/apiCall\s*\(\s*['"]([a-zA-Z0-9_]+)['"]/g)].map(m => m[1]);
    const invokes = [...content.matchAll(/invoke\s*\(\s*['"]([a-zA-Z0-9_]+)['"]/g)].map(m => m[1]);
    const allBridgeMethods = [...new Set([...bridgeMethods, ...apiCalls, ...invokes])];
    
    // Check if it has Math.random or Mock data patterns
    const hasMock = content.includes('Math.random()') || content.includes('setTimeout') && content.includes('success');
    
    inventory.push({
        id: (index + 1).toString().padStart(3, '0'),
        file: file.replace('app/src/main/assets/', ''),
        name: screenName,
        domain: getDomain(fileName),
        jsFunctions: allFunctions.length,
        bridgeMethods: allBridgeMethods,
        hasMock: hasMock
    });
});

function getDomain(fileName) {
    if (fileName.includes('sms') || fileName.includes('message')) return 'SMS / Messaging';
    if (fileName.includes('ai') || fileName.includes('cognitive')) return 'AI';
    if (fileName.includes('customer') || fileName.includes('crm')) return 'CRM';
    if (fileName.includes('product') || fileName.includes('inventory') || fileName.includes('stock') || fileName.includes('warehouse')) return 'Inventory';
    if (fileName.includes('sale') || fileName.includes('pos') || fileName.includes('receipt')) return 'Sales';
    if (fileName.includes('expense') || fileName.includes('budget') || fileName.includes('account') || fileName.includes('ledger') || fileName.includes('debt') || fileName.includes('tax') || fileName.includes('balance')) return 'Accounting';
    if (fileName.includes('fuel') || fileName.includes('tank') || fileName.includes('pump') || fileName.includes('meter')) return 'Fuel / Station';
    if (fileName.includes('report') || fileName.includes('log') || fileName.includes('audit')) return 'Reports & Logs';
    if (fileName.includes('setting') || fileName.includes('user') || fileName.includes('role') || fileName.includes('company')) return 'Administration';
    if (fileName.includes('main') || fileName.includes('dashboard') || fileName.includes('kpi')) return 'Dashboard';
    if (fileName.includes('employee') || fileName.includes('payroll') || fileName.includes('attendance')) return 'HR';
    if (fileName.includes('vehicle') || fileName.includes('driver') || fileName.includes('trip')) return 'Fleet';
    return 'General / Core';
}

fs.writeFileSync('screen-inventory-raw.json', JSON.stringify(inventory, null, 2));
console.log(`Analyzed ${inventory.length} screens.`);
