const fs = require('fs');
const path = require('path');

const inventoryFile = 'screen-inventory-raw.json';
const dbHelperFile = 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt';
const mainActivityFile = 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt';

const inventory = JSON.parse(fs.readFileSync(inventoryFile, 'utf8'));
const dbHelperContent = fs.readFileSync(dbHelperFile, 'utf8');
const mainActivityContent = fs.readFileSync(mainActivityFile, 'utf8');

// Helper to check if a bridge method exists in MainActivity
function findKotlinMethod(methodName) {
    if (mainActivityContent.includes(`fun ${methodName}(`)) {
        return 'MainActivity';
    }
    return 'Missing';
}

// Helper to find DB tables accessed in DatabaseHelper
function findDbTables(methodName) {
    // This is a simplified heuristic
    // Real mapping would require AST parsing of Kotlin
    // We'll just look for common tables related to the domain
    return [];
}

inventory.forEach(screen => {
    // Determine priority
    if (['main.html', 'customers.html', 'products.html', 'sales.html', 'pos.html', 'messages.html'].includes(screen.file) || screen.file.includes('sms')) {
        screen.priority = 'P0';
    } else if (['inventory.html', 'tanks.html', 'pumps.html', 'suppliers.html', 'reports.html'].some(k => screen.file.includes(k))) {
        screen.priority = 'P1';
    } else if (screen.domain === 'Accounting' || screen.domain === 'HR' || screen.domain === 'Fleet') {
        screen.priority = 'P2';
    } else {
        screen.priority = 'P3';
    }

    // Check Bridge mapping
    screen.kotlinMethods = screen.bridgeMethods.map(m => {
        return { method: m, status: findKotlinMethod(m) };
    });

    // Heuristic for DB tables based on domain
    let tables = [];
    const domain = screen.domain.toLowerCase();
    if (domain.includes('customer') || domain.includes('crm')) tables.push('customers');
    if (domain.includes('product') || domain.includes('inventory')) tables.push('products', 'inventory');
    if (domain.includes('sale')) tables.push('sales_transactions');
    if (domain.includes('sms')) tables.push('sms_messages', 'sms_templates');
    if (domain.includes('ai')) tables.push('sms_ai_runs');
    if (domain.includes('tank') || domain.includes('fuel')) tables.push('tanks', 'pumps', 'fuel_types');
    if (domain.includes('accounting')) tables.push('accounts', 'transactions');
    
    screen.dbTables = tables;

    // Detect Gaps
    screen.gaps = [];
    if (screen.hasMock) screen.gaps.push('Contains Fake/Mock Data (Math.random or setTimeout)');
    if (screen.bridgeMethods.length === 0 && !['assets-v12.html', 'screens.html'].includes(screen.file)) {
        screen.gaps.push('No Bridge Methods found (Static Screen)');
    }
    if (screen.kotlinMethods.some(k => k.status === 'Missing')) {
        screen.gaps.push('Missing Kotlin Bridge Implementation');
    }
    
    // Check if UI uses old inline styles or new theme
    const htmlContent = fs.readFileSync(path.join('app/src/main/assets', screen.file === 'main.html' ? '' : 'screens', screen.file.replace('screens/', '')), 'utf8');
    if (!htmlContent.includes('theme.css') && !['main.html', 'messages.html', 'ai-assistant.html', 'customers.html'].includes(screen.file)) {
        screen.gaps.push('Not using Global Design System (theme.css)');
    }
});

fs.writeFileSync('screen-inventory-deep.json', JSON.stringify(inventory, null, 2));
console.log(`Deep analysis completed for ${inventory.length} screens.`);
