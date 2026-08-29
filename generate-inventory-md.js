const fs = require('fs');

const inventory = JSON.parse(fs.readFileSync('screen-inventory-deep.json', 'utf8'));

let md = `# COMPLETE-SCREEN-INVENTORY\n\n`;
md += `> تم جرد جميع الشاشات الفعلية الموجودة في المشروع وعددهن **${inventory.length}** شاشة.\n\n`;

md += `## 📊 إحصائيات الجرد\n`;
const domains = {};
const priorities = { P0: 0, P1: 0, P2: 0, P3: 0 };
let totalMocks = 0;
let totalMissingTheme = 0;

inventory.forEach(s => {
    domains[s.domain] = (domains[s.domain] || 0) + 1;
    if (priorities[s.priority] !== undefined) priorities[s.priority]++;
    if (s.gaps.some(g => g.includes('Mock'))) totalMocks++;
    if (s.gaps.some(g => g.includes('theme.css'))) totalMissingTheme++;
});

md += `- **الشاشات حسب النطاق:**\n`;
Object.entries(domains).sort((a,b) => b[1]-a[1]).forEach(([domain, count]) => {
    md += `  - ${domain}: ${count}\n`;
});
md += `- **الشاشات حسب الأولوية:** P0(${priorities.P0}), P1(${priorities.P1}), P2(${priorities.P2}), P3(${priorities.P3})\n`;
md += `- **فجوات عامة:** ${totalMocks} شاشة تحتوي بيانات وهمية، ${totalMissingTheme} شاشة لا تستخدم نظام التصميم الموحد.\n\n`;

md += `## 📑 تفاصيل الشاشات\n\n`;

inventory.forEach(s => {
    md += `### ${s.id} - ${s.name}\n`;
    md += `- **File:** \`${s.file}\`\n`;
    md += `- **Domain:** ${s.domain}\n`;
    md += `- **Priority:** ${s.priority}\n`;
    
    md += `- **Bridge Methods (${s.bridgeMethods.length}):** `;
    if (s.bridgeMethods.length > 0) {
        md += s.kotlinMethods.map(k => `\`${k.method}\`(${k.status === 'Missing' ? '❌' : '✅'})`).join(', ');
    } else {
        md += `*None*`;
    }
    md += `\n`;
    
    md += `- **DB Tables:** ${s.dbTables.length > 0 ? s.dbTables.join(', ') : '*Unknown/Static*'}\n`;
    
    md += `- **Identified Gaps:**\n`;
    if (s.gaps.length > 0) {
        s.gaps.forEach(g => md += `  - 🔴 ${g}\n`);
    } else {
        md += `  - 🟢 No critical static gaps detected\n`;
    }
    md += `\n---\n\n`;
});

fs.writeFileSync('docs/COMPLETE-SCREEN-INVENTORY.md', md);
console.log('Markdown inventory generated at docs/COMPLETE-SCREEN-INVENTORY.md');
