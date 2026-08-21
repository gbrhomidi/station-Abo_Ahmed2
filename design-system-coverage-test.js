const fs = require('fs');
const path = require('path');

const assetsDir = 'app/src/main/assets';
const files = [
  path.join(assetsDir, 'main.html'),
  ...fs.readdirSync(path.join(assetsDir, 'screens'))
    .filter(name => name.endsWith('.html'))
    .map(name => path.join(assetsDir, 'screens', name))
];

const failures = [];
for (const file of files) {
  const html = fs.readFileSync(file, 'utf8');
  const themeLinks = (html.match(/assets-local\/css\/theme\.css/g) || []).length;
  if (themeLinks !== 1) failures.push(`${file}: expected exactly one theme.css link, found ${themeLinks}`);
  if (!/<html\b[^>]*\bdir=["']rtl["']/i.test(html)) failures.push(`${file}: missing html dir=rtl`);
  if (!/<meta\b[^>]*charset=/i.test(html)) failures.push(`${file}: missing charset declaration`);
  if (!/<meta\b[^>]*name=["']viewport["']/i.test(html)) failures.push(`${file}: missing viewport declaration`);
}

const css = fs.readFileSync(path.join(assetsDir, 'assets-local', 'css', 'theme.css'), 'utf8');
for (const token of ['--touch-target: 44px', '.empty-state', '.error-state', '.skeleton', '.pagination', '@media (max-width: 640px)', '@media print']) {
  if (!css.includes(token)) failures.push(`theme.css: missing required design-system primitive ${token}`);
}

console.log(`Coverage checked: ${files.length} HTML screens.`);
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Design-system coverage PASS.');
