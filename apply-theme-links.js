const fs = require('fs');
const path = require('path');

const screensDir = path.join('app', 'src', 'main', 'assets', 'screens');
const themeLink = '    <link rel="stylesheet" href="file:///android_asset/assets-local/css/theme.css">';
let updated = 0;
let skipped = 0;
let failed = 0;

for (const fileName of fs.readdirSync(screensDir).filter(name => name.endsWith('.html')).sort()) {
  const filePath = path.join(screensDir, fileName);
  const original = fs.readFileSync(filePath, 'utf8');
  if (original.includes('assets-local/css/theme.css')) {
    skipped++;
    continue;
  }

  const headClose = original.search(/<\/head>/i);
  if (headClose < 0) {
    console.error(`SKIP no </head>: ${fileName}`);
    failed++;
    continue;
  }

  const updatedContent = original.slice(0, headClose) + themeLink + '\n' + original.slice(headClose);
  fs.writeFileSync(filePath, updatedContent);
  updated++;
}

console.log(JSON.stringify({ updated, skipped, failed }));
if (failed > 0) process.exit(1);
