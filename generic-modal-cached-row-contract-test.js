const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const screensDir = 'app/src/main/assets/screens';
const screens = fs.readdirSync(screensDir)
  .filter((file) => file.endsWith('.html'))
  .filter((file) => file !== 'vehicle-expenses.html')
  .filter((file) => fs.readFileSync(path.join(screensDir, file), 'utf8').includes('function openForm(row=null)'))
  .map((file) => path.basename(file, '.html'))
  .sort();

assert.ok(screens.length >= 15, 'Expected the generic cached-row modal screen family');

for (const name of screens) {
  const html = fs.readFileSync(`app/src/main/assets/screens/${name}.html`, 'utf8');
  const start = html.indexOf('function openForm(row=null)');
  const end = html.indexOf('function collectForm()', start);
  assert.notEqual(start, -1, `${name}: missing generic openForm`);
  assert.notEqual(end, -1, `${name}: missing generic openForm end marker`);
  const openForm = html.slice(start, end);
  assert.match(openForm, /state\.editing=row/, `${name}: modal must use the already loaded row`);
  assert.match(openForm, /formModal.*classList\.add\('show'\)/, `${name}: form modal must open directly`);
  assert.doesNotMatch(openForm, /invoke\(|bridge\[|AndroidInterface|setLoading\(true\)/, `${name}: opening a form must not block on a bridge call`);
  assert.match(html, /modal-interaction\.js/, `${name}: shared modal guard must be loaded`);
}

console.log(`Generic cached-row modal contract PASS (${screens.length} screens).`);
