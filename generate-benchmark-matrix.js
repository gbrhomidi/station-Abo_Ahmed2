const fs = require('fs');

const inventory = JSON.parse(fs.readFileSync('screen-inventory-deep.json', 'utf8'));

let md = `# GLOBAL-SCREEN-BENCHMARK-MATRIX\n\n`;
md += `هذه المصفوفة تطابق شاشات المشروع مع المراجع العالمية وتحدد الأنماط التي سيتم تكييفها.\n\n`;

md += `| Screen ID | Screen Name | Business Domain | Global Reference | Patterns to Adopt | Status |\n`;
md += `|-----------|-------------|-----------------|------------------|-------------------|--------|\n`;

inventory.forEach(s => {
    let ref = 'ERPNext';
    let patterns = 'Standard Grid, Forms, Audit';
    
    if (s.domain === 'SMS / Messaging') {
        ref = 'Jasmin SMS, RapidPro';
        patterns = 'Observability Grid, Status Badges';
    } else if (s.domain === 'AI') {
        ref = 'Rasa, Langfuse';
        patterns = 'Trace Details, Health Indicators';
    } else if (s.domain === 'Dashboard') {
        ref = 'Metabase, Superset';
        patterns = 'Data-Driven Cards, Skeleton Loading';
    } else if (s.domain === 'Sales') {
        ref = 'Odoo POS';
        patterns = 'Touch-friendly, Fast Entry';
    } else if (s.domain === 'Fuel / Station') {
        ref = 'Odoo Fleet';
        patterns = 'Capacity Visualization, Logs';
    }

    md += `| ${s.id} | \`${s.file}\` | ${s.domain} | ${ref} | ${patterns} | PENDING ADAPTATION |\n`;
});

fs.writeFileSync('docs/GLOBAL-SCREEN-BENCHMARK-MATRIX.md', md);
console.log('Markdown benchmark matrix generated at docs/GLOBAL-SCREEN-BENCHMARK-MATRIX.md');
