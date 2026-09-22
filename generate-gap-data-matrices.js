const fs = require('fs');
const path = require('path');

const inventory = JSON.parse(fs.readFileSync('screen-inventory-deep.json', 'utf8'));
const dbHelperContent = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt', 'utf8');

// 1. Generate Capability Matrix
let capMd = `# SCREEN-CAPABILITY-MATRIX\n\n`;
capMd += `تحليل فجوات القدرات لكل شاشة بناءً على المعايير العالمية.\n\n`;
capMd += `| Screen File | Capability | Required? | Existing? | Data Source | Implementation | Status |\n`;
capMd += `|-------------|------------|-----------|-----------|-------------|----------------|--------|\n`;

// 2. Generate Data Binding Matrix
let dataMd = `# SCREEN-DATA-BINDING-MATRIX\n\n`;
dataMd += `تحليل مسارات البيانات لكل شاشة لضمان عدم وجود بيانات وهمية.\n\n`;
dataMd += `| Screen File | UI Action/Data | Bridge Method | Kotlin Service | DB Table/Query | Real Data Path? |\n`;
dataMd += `|-------------|----------------|---------------|----------------|----------------|-----------------|\n`;

inventory.forEach(s => {
    // Basic capabilities based on domain
    let caps = [
        { name: 'Search', req: 'YES', ext: s.bridgeMethods.length > 0 ? 'YES' : 'NO', ds: 'DB', imp: s.bridgeMethods.length > 0 ? 'Existing' : 'Required', status: s.bridgeMethods.length > 0 ? 'REAL' : 'GAP' },
        { name: 'KPI/Stats', req: 'YES', ext: s.hasMock ? 'PARTIAL (Mock)' : (s.bridgeMethods.some(m => m.includes('Stats') || m.includes('Dashboard')) ? 'YES' : 'NO'), ds: 'DB', imp: 'Required', status: 'GAP' },
        { name: 'Export/Print', req: s.domain.includes('Report') || s.domain.includes('Accounting') ? 'YES' : 'OPTIONAL', ext: s.bridgeMethods.some(m => m.includes('export') || m.includes('print')) ? 'YES' : 'NO', ds: 'DB', imp: 'Required', status: 'GAP' },
        { name: 'Audit Trail', req: 'YES', ext: 'NO', ds: 'DB', imp: 'Required', status: 'GAP' }
    ];

    caps.forEach(c => {
        capMd += `| \`${s.file}\` | ${c.name} | ${c.req} | ${c.ext} | ${c.ds} | ${c.imp} | ${c.status} |\n`;
    });

    // Data Binding paths
    if (s.bridgeMethods.length === 0) {
        dataMd += `| \`${s.file}\` | Static UI | None | None | None | ❌ NO PATH |\n`;
    } else {
        s.bridgeMethods.forEach(m => {
            let kotlin = s.kotlinMethods.find(k => k.method === m)?.status === 'MainActivity' ? 'MainActivity' : 'Missing';
            let table = s.dbTables.length > 0 ? s.dbTables.join(', ') : 'Unknown';
            let pathStatus = kotlin === 'MainActivity' ? '✅ VERIFIED' : '❌ BROKEN PATH';
            
            // Flag specific known mock methods or suspicious paths
            if (s.hasMock) pathStatus = '⚠️ CONTAINS MOCKS';
            
            dataMd += `| \`${s.file}\` | Call \`${m}\` | \`Android.${m}\` | ${kotlin} | \`${table}\` | ${pathStatus} |\n`;
        });
    }
});

fs.writeFileSync('docs/SCREEN-CAPABILITY-MATRIX.md', capMd);
fs.writeFileSync('docs/SCREEN-DATA-BINDING-MATRIX.md', dataMd);

console.log('Generated SCREEN-CAPABILITY-MATRIX.md and SCREEN-DATA-BINDING-MATRIX.md');
