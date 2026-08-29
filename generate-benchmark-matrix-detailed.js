const fs = require('fs');

const inventory = JSON.parse(fs.readFileSync('screen-inventory-deep.json', 'utf8'));

let md = `# GLOBAL-SCREEN-BENCHMARK-MATRIX (DETAILED)\n\n`;
md += `هذه المصفوفة التفصيلية تطابق كل شاشة في المشروع مع المرجع العالمي المناسب والأنماط المستخرجة للتكييف، استناداً إلى بحث مستودعات GitHub.\n\n`;

md += `| Screen ID | Screen Name | Business Domain | Global Reference (Repo) | Patterns to Adopt | Data Binding Strategy | Status |\n`;
md += `|-----------|-------------|-----------------|-------------------------|-------------------|-----------------------|--------|\n`;

inventory.forEach(s => {
    let ref = '`frappe/erpnext`';
    let patterns = 'Standard Grid, Forms, Collapsible Sections, Audit';
    let dataStrategy = 'Direct map to SQLite via Bridge';
    
    if (s.domain === 'SMS / Messaging') {
        ref = '`jookies/jasmin`, `101t/jasmin-web-panel`';
        patterns = 'Observability Grid, Status Badges, Conversation Threads';
        dataStrategy = 'Bind to `sms_messages`, `sms_templates`';
    } else if (s.domain === 'AI') {
        ref = '`langfuse/langfuse`, `RasaHQ/rasa`';
        patterns = 'Trace Details, Health Indicators, Circuit Breaker UI';
        dataStrategy = 'Bind to `sms_ai_runs`';
    } else if (s.domain === 'Dashboard') {
        ref = '`metabase/metabase`, `apache/superset`';
        patterns = 'Data-Driven Cards, Skeleton Loading, Trend Arrows';
        dataStrategy = 'Bind to `getDashboardStats` (No Math.random)';
    } else if (s.domain === 'Sales') {
        ref = '`odoo/odoo` (POS Module)';
        patterns = 'Touch-friendly targets, Sticky Actions, Fast Entry';
        dataStrategy = 'Bind to `sales_transactions`';
    } else if (s.domain === 'Fuel / Station') {
        ref = '`odoo/odoo` (Fleet/Asset)';
        patterns = 'Capacity Visualization, Meter Reading Logs';
        dataStrategy = 'Bind to `tanks`, `pumps`';
    } else if (s.domain === 'Reports & Logs') {
        ref = '`metabase/metabase`';
        patterns = 'Global Date Filters, Export Buttons, Sortable Tables';
        dataStrategy = 'Bind to specific report queries';
    }

    md += `| ${s.id} | \`${s.file}\` | ${s.domain} | ${ref} | ${patterns} | ${dataStrategy} | PENDING ADAPTATION |\n`;
});

fs.writeFileSync('docs/GLOBAL-SCREEN-BENCHMARK-MATRIX.md', md);
console.log('Detailed markdown benchmark matrix updated at docs/GLOBAL-SCREEN-BENCHMARK-MATRIX.md');
