'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.join(__dirname, 'app/src/main/assets/screens');
const activityPath = path.join(__dirname, 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const failures = [];
const inventory = [];
let inlineScriptCount = 0;
let exportScreenCount = 0;
let exportButtonCount = 0;

function walkHtml(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walkHtml(file);
    else if (entry.isFile() && entry.name.toLowerCase().endsWith('.html')) inventory.push(file);
  }
}

walkHtml(root);

const inlineScriptPattern = /<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi;
const exportActionPattern = /(?:export|download|print|تصدير|طباعة|تنزيل|xlsx|csv|pdf)/i;
for (const file of inventory) {
  const html = fs.readFileSync(file, 'utf8');
  const relative = path.relative(__dirname, file).split(path.sep).join('/');
  let match;
  let scriptIndex = 0;
  while ((match = inlineScriptPattern.exec(html)) !== null) {
    const source = match[1].trim();
    if (!source) continue;
    inlineScriptCount += 1;
    scriptIndex += 1;
    try {
      new vm.Script(source, { filename: `${relative}#inline-${scriptIndex}` });
    } catch (error) {
      failures.push(`صياغة JavaScript غير صالحة: ${relative}#inline-${scriptIndex}: ${error.message}`);
    }
  }
  inlineScriptPattern.lastIndex = 0;

  const exportButtons = [...html.matchAll(/<button\b[^>]*>[\s\S]*?<\/button>/gi)]
    .filter(([button]) => exportActionPattern.test(button));
  exportButtonCount += exportButtons.length;
  if (exportButtons.length) exportScreenCount += 1;
}

const kotlin = fs.readFileSync(activityPath, 'utf8');
const androidContracts = {
  webViewFileChooser: /override\s+fun\s+onShowFileChooser\s*\(/.test(kotlin),
  scopedCsvExport: /@JavascriptInterface[\s\S]{0,240}?fun\s+exportOperationalCsv\s*\(/.test(kotlin),
  databaseCsvExport: /@JavascriptInterface[\s\S]{0,240}?fun\s+exportToCSV\s*\(/.test(kotlin),
  nativePrintAdapter: /createPrintDocumentAdapter\s*\(/.test(kotlin),
  genericWebViewDownloadListener: /setDownloadListener\s*\(/.test(kotlin)
};

for (const contract of ['webViewFileChooser', 'scopedCsvExport', 'databaseCsvExport', 'nativePrintAdapter']) {
  if (!androidContracts[contract]) failures.push(`عقد Android مفقود: ${contract}`);
}

console.log(`شاشات HTML المفحوصة: ${inventory.length}`);
console.log(`السكريبتات المضمنة المفحوصة: ${inlineScriptCount}`);
console.log(`شاشات تحوي أزرار/عبارات تصدير أو طباعة: ${exportScreenCount}`);
console.log(`عناصر أزرار مطابقة لعبارات التصدير/الطباعة: ${exportButtonCount}`);
console.log('عقود Android:', JSON.stringify(androidContracts, null, 2));
if (failures.length) {
  console.error('\nنتائج فاشلة:');
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('\nفحص الصياغة والعقود الأساسية: PASS.');
console.log('تنبيه: هذا فحص ساكن/عقود؛ لا يثبت تنزيل ملف أو فتح معاينة طباعة على جهاز Android فعلي.');
