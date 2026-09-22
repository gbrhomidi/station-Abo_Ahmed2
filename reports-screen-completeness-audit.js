const fs = require('fs');
const path = require('path');
const root = path.join('app', 'src', 'main', 'assets', 'screens');
const files = ['../main.html','sales-reports.html','eod-report.html','inventory-reports.html','customer-reports.html','fuel-reports.html','kpi.html','forecasts.html','accounting-reports.html'].filter(f => fs.existsSync(path.join(root, f)));
const kotlin = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt','utf8');
const aliases = { generateSalesReport: 'generateSalesTransactionReport', getInvoiceDetails: 'retrieveInvoice', getProductDetails: 'getInventoryProductDetails' };
function methods(text) {
  const out = new Set();
  const add = name => { if (name && name !== 'generate') out.add(aliases[name] || name); };
  for (const m of text.matchAll(/(?:AndroidInterface|bridge)\.([A-Za-z_$][\w$]*)\s*\(/g)) add(m[1]);
  for (const m of text.matchAll(/(?:invokeTypedBridge|invoke)\s*\(\s*['"]([^'"]+)['"]/g)) add(m[1]);
  for (const m of text.matchAll(/(?:method|listMethod|saveMethod|deleteMethod|resolveMethod)\s*:\s*['"]([^'"]+)['"]/g)) add(m[1]);
  return [...out];
}
const rows = [];
for (const file of files) {
  const text = fs.readFileSync(path.join(root,file),'utf8');
  const inlineScripts = [...text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).join('\n');
  const bridge = methods(inlineScripts);
  const native = bridge.filter(m => new RegExp(`@JavascriptInterface\\s+fun\\s+${m}\\s*\\(`).test(kotlin));
  const missing = bridge.filter(m => !native.includes(m));
  const checks = {
    rtl: /<html[^>]*\bdir=["']rtl["']/i.test(text) || /direction\s*:\s*rtl/i.test(text),
    theme: /assets-local\/css\/theme\.css|theme\.css/i.test(text),
    runtime: /assets-local\/js\/reports-runtime\.js|reports-runtime\.js/i.test(text),
    sourceBanner: /report-source-banner|reportsDataSource/i.test(text),
    loading: /loading|skeleton|aria-busy/i.test(text),
    empty: /empty|لا توجد|لا يوجد|no data/i.test(text),
    error: /error|خطأ|فشل|catch/i.test(text),
    success: /success|نجاح|تم بنجاح|render/i.test(text),
    search: /search|بحث|oninput/i.test(text),
    filter: /filter|فلتر|select|date|تاريخ/i.test(text),
    tableOrChart: /<table|canvas|chart|Chart\s*\(/i.test(text),
    export: /export|تصدير|print|طباعة|pdf|csv/i.test(text),
    responsive: /@media|responsive|viewport/i.test(text),
    dark: /data-theme=["']dark["']|\[data-theme=["']dark["']\]/i.test(text),
    bridgeCount: bridge.length > 0,
    bridgeNative: missing.length === 0
  };
  rows.push({ file, methods: bridge, missing, checks });
}
console.log(JSON.stringify({ screens: rows, screenCount: rows.length }, null, 2));
