const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

const html = fs.readFileSync('app/src/main/assets/screens/employees.html', 'utf8');
const mapStart = html.indexOf('const departments = {');
const mapEnd = html.indexOf('\n};', mapStart) + 3;
assert.ok(mapStart >= 0 && mapEnd > mapStart, 'department map must exist');
const mapSource = html.slice(mapStart + 'const departments = '.length, mapEnd - 1);
const departments = vm.runInNewContext(`(${mapSource})`);

const updateStart = html.indexOf('function updateJobs(');
const updateEnd = html.indexOf('\n}\n\nif(dep)', updateStart) + 2;
assert.ok(updateStart >= 0 && updateEnd > updateStart, 'updateJobs must exist');
const updateSource = html.slice(updateStart, updateEnd);

const context = {
    departments,
    esc: value => String(value ?? '—').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
    dep: { value: '' },
    job: { value: 'غير صالح', disabled: false, innerHTML: '' },
    jobArabic: { value: 'مسمى قديم' }
};
vm.runInNewContext(`${updateSource}; updateJobs();`, context);
assert.equal(context.job.disabled, true, 'job title must be disabled without a department');
assert.match(context.job.innerHTML, /اختر القسم أولاً/);

for (const [department, jobs] of Object.entries(departments)) {
    assert.ok(jobs.length > 0, `${department} must have job titles`);
    context.dep.value = department;
    context.job.value = '';
    vm.runInNewContext(`${updateSource}; updateJobs();`, context);
    assert.equal(context.job.disabled, false, `${department} must enable job titles`);
    for (const job of jobs) assert.match(context.job.innerHTML, new RegExp(`value="${job.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')}"`));
    const otherDepartments = Object.entries(departments).filter(([name]) => name !== department);
    for (const [, otherJobs] of otherDepartments) {
        for (const job of otherJobs) assert.doesNotMatch(context.job.innerHTML, new RegExp(`value="${job.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')}"`), `${job} leaked into ${department}`);
    }
}

console.log('Employee department/job-title runtime PASS.');
console.log(`Verified ${Object.keys(departments).length} departments and ${Object.values(departments).flat().length} dependent job titles.`);

// Keep the script syntax itself covered by the existing inline-script test.
void updateSource;
