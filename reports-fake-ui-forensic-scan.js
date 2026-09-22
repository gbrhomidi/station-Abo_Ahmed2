const fs = require('fs');
const path = require('path');

const files = [
  'app/src/main/assets/main.html',
  ...['sales-reports','eod-report','inventory-reports','customer-reports','fuel-reports','kpi','forecasts','accounting-reports']
    .map(name => `app/src/main/assets/screens/${name}.html`)
];
const forbidden = [
  { label: 'Math.random', re: /Math\.random\s*\(/gi, classification: 'MOCK' },
  { label: 'mock/dummy/fake/sample/hardcoded', re: /\b(?:mock|dummy|fake|sample|hardcoded)\b/gi, classification: 'UNKNOWN' },
  { label: 'TODO/FIXME', re: /\b(?:TODO|FIXME)\b/gi, classification: 'TEMPORARY' },
  { label: 'simulation marker', re: /محاكاة|بيانات وهمية|بيانات اصطناعية/gi, classification: 'UNKNOWN' },
  { label: 'static percentage fallback', re: /["'](?:\+?0(?:\.0+)?%|-0%)["']/g, classification: 'STATIC' },
  { label: 'fake empty success', re: /Promise\.resolve\s*\(\s*\{[^}]*success\s*:\s*false[^}]*data\s*:\s*\[\]/gis, classification: 'MOCK' }
];
let total = 0;
for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map(match => match[1]).join('\n');
  const findings = [];
  for (const pattern of forbidden) {
    pattern.re.lastIndex = 0;
    for (const match of scripts.matchAll(pattern.re)) {
      const line = scripts.slice(0, match.index).split('\n').length;
      findings.push({ label: pattern.label, classification: pattern.classification, line, context: scripts.split('\n')[line - 1].trim() });
    }
  }
  total += findings.length;
  console.log(`\n[${file}]`);
  if (!findings.length) console.log('CLEAN');
  else findings.forEach(item => console.log(`${item.classification}\t${item.label}\tL${item.line}\t${item.context}`));
}
console.log(`\nTOTAL_SCRIPT_FINDINGS=${total}`);
if (total > 0) process.exitCode = 1;
