const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

const html = fs.readFileSync('app/src/main/assets/screens/employees.html', 'utf8');
const mapStart = html.indexOf('const departments = {');
const mapEnd = html.indexOf('\n};', mapStart) + 3;
assert.ok(mapStart >= 0 && mapEnd > mapStart, 'department map must exist');
const mapSource = html.slice(mapStart + 'const departments = '.length, mapEnd - 1);
const departments = vm.runInNewContext(`(${mapSource})`);

function extractFunction(source, name) {
    const start = source.indexOf(`function ${name}(`);
    assert.ok(start >= 0, `${name} must exist`);
    const bodyStart = source.indexOf('{', start);
    let depth = 0;
    let quote = null;
    let escaped = false;
    for (let i = bodyStart; i < source.length; i += 1) {
        const char = source[i];
        if (quote) {
            if (escaped) escaped = false;
            else if (char === '\\') escaped = true;
            else if (char === quote) quote = null;
            continue;
        }
        if (char === "'" || char === '"' || char === '`') {
            quote = char;
            continue;
        }
        if (char === '{') depth += 1;
        if (char === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error(`could not extract ${name}`);
}

const refreshSource = extractFunction(html, 'refreshEmployeeJobTitleOptions');

function makeSelect(initialValue = '') {
    return {
        value: initialValue,
        disabled: false,
        innerHTML: '',
        options: [],
        replaceChildren() {
            this.options = [];
            this.innerHTML = '';
        },
        appendChild(option) {
            this.options.push(option);
            this.innerHTML += `<option value="${option.value}">${option.textContent}</option>`;
        },
        removeAttribute(attribute) {
            if (attribute === 'readonly') delete this.readonly;
        }
    };
}

const dep = makeSelect();
const job = makeSelect('غير صالح');
const jobArabic = makeSelect('مسمى قديم');
const fields = { department: dep, job_title: job, job_title_ar: jobArabic };
const form = { querySelector: selector => fields[selector.match(/data-key="([^"]+)"/)[1]] || null };
const context = {
    departments,
    getEmployeeFormField: key => fields[key] || null,
    getEmployeeJobTitles: department => {
        const titles = departments[String(department || '').trim()];
        return Array.isArray(titles) ? titles.slice() : [];
    },
    document: {
        createElement: tag => {
            assert.equal(tag, 'option');
            return { value: '', textContent: '' };
        }
    },
    $: id => id === 'employeeForm' ? form : null
};

vm.runInNewContext(`${refreshSource}; refreshEmployeeJobTitleOptions();`, context);
assert.equal(job.disabled, true, 'job title must be disabled without a department');
assert.match(job.innerHTML, /اختر القسم أولاً/);
assert.equal(jobArabic.value, '', 'Arabic job title must clear without a department');

for (const [department, jobs] of Object.entries(departments)) {
    assert.ok(jobs.length > 0, `${department} must have job titles`);
    dep.value = department;
    job.value = '';
    vm.runInNewContext(`${refreshSource}; refreshEmployeeJobTitleOptions();`, context);
    assert.equal(job.disabled, false, `${department} must enable job titles`);
    for (const title of jobs) {
        assert.ok(job.options.some(option => option.value === title), `${title} must be available in ${department}`);
    }
    const otherDepartments = Object.entries(departments).filter(([name]) => name !== department);
    for (const [, otherJobs] of otherDepartments) {
        for (const title of otherJobs) {
            assert.ok(!job.options.some(option => option.value === title), `${title} leaked into ${department}`);
        }
    }
}

// Regression coverage for the exact cashier spelling used by the database query.
dep.value = 'الشؤون المالية';
vm.runInNewContext(`${refreshSource}; refreshEmployeeJobTitleOptions();`, context);
assert.ok(job.options.some(option => option.value === 'أمين صندوق'), 'exact cashier title must be available');
job.value = 'أمين صندوق';
vm.runInNewContext(`${refreshSource}; refreshEmployeeJobTitleOptions(true, 'أمين صندوق');`, context);
assert.equal(job.value, 'أمين صندوق', 'existing cashier title must be preserved');
assert.equal(jobArabic.value, 'أمين صندوق', 'Arabic job title must mirror the selected cashier title');

console.log('Employee department/job-title runtime PASS.');
console.log(`Verified ${Object.keys(departments).length} departments and ${Object.values(departments).flat().length} dependent job titles.`);
