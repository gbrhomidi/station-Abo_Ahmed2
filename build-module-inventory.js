const fs = require('fs');
const path = require('path');

// Fallback route map from main.html
const routeMap = {
    'إدارة المستخدمين': 'users.html',
    'الأدوار والصلاحيات': 'roles.html',
    'إدارة الشاشات': 'screens.html',
    'سجل النشاطات': 'activity-log.html',
    'إعدادات الشركة': 'company-settings.html',
    'إدارة المحطات': 'stations.html',
    'إعدادات النظام': 'settings.html',
    'أسعار الصرف': 'exchange-rates.html',
    'إدارة العملاء': 'customers.html',
    'إدارة الموردين': 'suppliers.html',
    'أنواع الأطراف': 'party-types.html',
    'إدارة العقود': 'contracts.html',
    'الديون المعدومة': 'bad-debts.html',
    'CRM - العلاقات': 'crm.html',
    'إدارة المركبات': 'vehicles.html',
    'إدارة السائقين': 'drivers.html',
    'تتبع المركبات': 'vehicle-tracking.html',
    'رحلات المركبات': 'trips.html',
    'مصروفات المركبات': 'vehicle-expenses.html',
    'أنواع الوقود': 'fuel-types.html',
    'إدارة المنتجات': 'products.html',
    'فئات المنتجات': 'product-categories.html',
    'قوائم الأسعار': 'price-lists.html',
    'سجل تغيير الأسعار': 'price-change-log.html',
    'إدارة الخزانات': 'tanks.html',
    'إدارة المضخات': 'pumps.html',
    'قراءات العدادات': 'meter-readings.html',
    'تعبئة الخزانات': 'tank-filling.html',
    'فحوصات الجودة': 'quality-checks.html',
    'معايرة المعدات': 'equipment-calibration.html',
    'إدارة المستودعات': 'warehouses.html',
    'مستويات المخزون': 'stock-levels.html',
    'حركات المخزون': 'inventory-movements.html',
    'الجرد والتسوية': 'stocktake.html',
    'المنتجات التالفة': 'damaged-products.html',
    'تنبيهات المخزون': 'inventory-alerts.html',
    'نقطة البيع (POS)': 'pos.html',
    'إدارة الورديات': 'shifts.html',
    'سجل المبيعات': 'sales-log.html',
    'إدارة الطلبات': 'orders.html',
    'إدارة التوصيلات': 'deliveries.html',
    'مبيعات الوقود': 'fuel-sales.html',
    'إدارة المدفوعات': 'payments.html',
    'إدارة الإيصالات': 'receipts.html',
    'إدارة الصناديق': 'cashboxes.html',
    'حركات النقدية': 'cash-movements.html',
    'البنوك والحسابات': 'banks-accounts.html',
    'شجرة الحسابات': 'chart-of-accounts.html',
    'القيود المحاسبية': 'journal-entries.html',
    'فئات المصروفات': 'expense-categories.html',
    'إدارة المصروفات': 'expenses.html',
    'الميزانيات': 'budgets.html',
    'الإيداعات النقدية': 'cash-deposits.html',
    'دفتر الأستاذ': 'ledger.html',
    'الميزانية العمومية': 'balance-sheet.html',
    'إدارة الموظفين': 'employees.html',
    'الحضور والانصراف': 'attendance.html',
    'إدارة الرواتب': 'payroll.html',
    'دفعات الموظفين': 'employee-payments.html',
    'الأصول الثابتة': 'fixed-assets.html',
    'إدارة الأصول V12': 'assets-v12.html',
    'طلبات الصيانة': 'maintenance-requests.html',
    'جدولة الصيانة': 'maintenance-schedule.html',
    'سجل الصيانة': 'maintenance-log.html',
    'الإهلاك': 'depreciation.html',
    'قوالب الإشعارات': 'notification-templates.html',
    'صندوق الإشعارات': 'notification-inbox.html',
    'إدارة الرسائل': 'messages.html',
    'سجل الرسائل': 'message-log.html',
    'تذكيرات الديون': 'debt-reminders.html',
    'القائمة البيضاء': 'whitelist.html',
    'فحص الرسائل': 'SmsCoreDiagnostics.html',
    'لوحة التحكم': 'main.html',
    'تقارير المبيعات': 'sales-reports.html',
    'تقرير نهاية اليوم': 'eod-report.html',
    'تقارير المخزون': 'inventory-reports.html',
    'تقارير العملاء': 'customer-reports.html',
    'تقارير الوقود': 'fuel-reports.html',
    'مؤشرات الأداء KPI': 'kpi.html',
    'التنبؤات والتحليلات': 'forecasts.html',
    'التقارير المحاسبية': 'accounting-reports.html',
    'سجلات النظام': 'system-logs.html',
    'سجلات التدقيق': 'audit-logs.html',
    'إدارة الوثائق': 'documents.html',
    'إدارة الأجهزة': 'devices.html',
    'سجل المزامنة': 'sync-log.html',
    'النسخ الاحتياطي': 'backups.html',
    'إعدادات الطابعات': 'printer-settings.html',
    'قوالب الإيصالات': 'receipt-templates.html',
    'قوالب الفواتير': 'invoice-templates.html',
    'المساعد الذكي': 'ai-assistant.html'
};

const fallbackSidebarData = [
    { id: 'security', label: 'الأمن والمصادقة', icon: 'fa-shield-halved', children: ['إدارة المستخدمين', 'الأدوار والصلاحيات', 'إدارة الشاشات', 'سجل النشاطات'] },
    { id: 'core', label: 'الإدارة الأساسية', icon: 'fa-building', children: ['إعدادات الشركة', 'إدارة المحطات', 'إعدادات النظام', 'أسعار الصرف'] },
    { id: 'parties', label: 'الأطراف والعملاء', icon: 'fa-users', children: ['إدارة العملاء', 'إدارة الموردين', 'أنواع الأطراف', 'إدارة العقود', 'الديون المعدومة', 'CRM - العلاقات'] },
    { id: 'vehicles', label: 'المركبات والسائقين', icon: 'fa-truck', children: ['إدارة المركبات', 'إدارة السائقين', 'تتبع المركبات', 'رحلات المركبات', 'مصروفات المركبات'] },
    { id: 'products', label: 'المنتجات والوقود', icon: 'fa-boxes', children: ['أنواع الوقود', 'إدارة المنتجات', 'فئات المنتجات', 'قوائم الأسعار', 'سجل تغيير الأسعار'] },
    { id: 'tanks', label: 'الخزانات والمضخات', icon: 'fa-oil-can', children: ['إدارة الخزانات', 'إدارة المضخات', 'قراءات العدادات', 'تعبئة الخزانات', 'فحوصات الجودة', 'معايرة المعدات'] },
    { id: 'inventory', label: 'المخزون والمستودعات', icon: 'fa-warehouse', children: ['إدارة المستودعات', 'مستويات المخزون', 'حركات المخزون', 'الجرد والتسوية', 'المنتجات التالفة', 'تنبيهات المخزون'] },
    { id: 'sales', label: 'المبيعات والورديات', icon: 'fa-shopping-cart', children: ['نقطة البيع (POS)', 'إدارة الورديات', 'سجل المبيعات', 'إدارة الطلبات', 'إدارة التوصيلات', 'مبيعات الوقود'] },
    { id: 'finance', label: 'المالية والحسابات', icon: 'fa-money-bill-wave', children: ['إدارة المدفوعات', 'إدارة الإيصالات', 'إدارة الصناديق', 'حركات النقدية', 'البنوك والحسابات', 'شجرة الحسابات', 'القيود المحاسبية', 'فئات المصروفات', 'إدارة المصروفات', 'الميزانيات', 'الإيداعات النقدية', 'دفتر الأستاذ', 'الميزانية العمومية'] },
    { id: 'hr', label: 'الموارد البشرية', icon: 'fa-user-tie', children: ['إدارة الموظفين', 'الحضور والانصراف', 'إدارة الرواتب', 'دفعات الموظفين'] },
    { id: 'assets', label: 'الأصول والصيانة', icon: 'fa-tools', children: ['الأصول الثابتة', 'إدارة الأصول V12', 'طلبات الصيانة', 'جدولة الصيانة', 'سجل الصيانة', 'الإهلاك'] },
    { id: 'notifications', label: 'الإشعارات والرسائل', icon: 'fa-bell', children: ['قوالب الإشعارات', 'صندوق الإشعارات', 'إدارة الرسائل', 'سجل الرسائل', 'تذكيرات الديون', 'القائمة البيضاء', 'فحص الرسائل'] },
    { id: 'reports', label: 'التقارير والتحليلات', icon: 'fa-chart-bar', children: ['لوحة التحكم', 'تقارير المبيعات', 'تقرير نهاية اليوم', 'تقارير المخزون', 'تقارير العملاء', 'تقارير الوقود', 'مؤشرات الأداء KPI', 'التنبؤات والتحليلات', 'التقارير المحاسبية'] },
    { id: 'system', label: 'النظام والسجلات', icon: 'fa-server', children: ['سجلات النظام', 'سجلات التدقيق', 'إدارة الوثائق'] },
    { id: 'sync', label: 'المزامنة والنسخ الاحتياطي', icon: 'fa-sync', children: ['إدارة الأجهزة', 'سجل المزامنة', 'النسخ الاحتياطي'] },
    { id: 'printing', label: 'الطباعة والقوالب', icon: 'fa-print', children: ['إعدادات الطابعات', 'قوالب الإيصالات', 'قوالب الفواتير'] },
    { id: 'ai', label: 'المساعد الذكي', icon: 'fa-robot', children: ['المساعد الذكي'] }
];

let md = `# GLOBAL-MODULE-INVENTORY\n\n`;
md += `تم استخراج الوحدات (Modules) والشاشات التابعة لها من بنية التنقل الفعلية للمشروع في \`main.html\`.\n\n`;

const executionQueue = [];

fallbackSidebarData.forEach((module, idx) => {
    md += `## ${String(idx + 1).padStart(2, '0')}. ${module.label} (${module.id})\n`;
    md += `| Screen Name | File Path | Status |\n`;
    md += `|-------------|-----------|--------|\n`;
    
    let screens = [];
    module.children.forEach(child => {
        const file = routeMap[child];
        if (file) {
            screens.push(file);
            md += `| ${child} | \`${file}\` | Pending |\n`;
        } else {
            md += `| ${child} | *Unknown* | Error |\n`;
        }
    });
    md += `\n`;
    
    // Add to execution queue
    executionQueue.push({
        id: module.id,
        label: module.label,
        screens: screens
    });
});

fs.writeFileSync('docs/GLOBAL-MODULE-INVENTORY.md', md);

// Generate Execution Queue based on Business Criticality
const priorities = {
    'reports': 'P0', // Dashboard is here
    'sales': 'P0',
    'inventory': 'P0',
    'parties': 'P0',
    'tanks': 'P1',
    'products': 'P1',
    'finance': 'P1',
    'notifications': 'P1', // SMS is here
    'ai': 'P1',
    'vehicles': 'P2',
    'hr': 'P2',
    'assets': 'P2',
    'core': 'P3',
    'system': 'P3',
    'sync': 'P3',
    'printing': 'P3'
};

let queueMd = `# MODULE-EXECUTION-QUEUE\n\n`;
queueMd += `ترتيب التنفيذ الهندسي بناءً على الأهمية التجارية والاعتماديات (Business Criticality & Dependencies).\n\n`;

['P0', 'P1', 'P2', 'P3'].forEach(p => {
    queueMd += `## Priority ${p}\n`;
    executionQueue.filter(m => priorities[m.id] === p).forEach(m => {
        queueMd += `- **[${m.id}] ${m.label}** (${m.screens.length} screens)\n`;
        m.screens.forEach(s => queueMd += `  - \`${s}\`\n`);
    });
    queueMd += `\n`;
});

fs.writeFileSync('docs/MODULE-EXECUTION-QUEUE.md', queueMd);

console.log('Generated GLOBAL-MODULE-INVENTORY.md and MODULE-EXECUTION-QUEUE.md');
