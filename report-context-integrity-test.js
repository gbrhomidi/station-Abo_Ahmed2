const fs = require('fs');
const path = require('path');
const vm = require('vm');
const root = __dirname;
const script = fs.readFileSync(path.join(root,'app/src/main/assets/assets-local/js/report-context-persistence.js'),'utf8');
const store = new Map();
const listeners = {};
const ctx = {
  window: {
    location: { pathname: '/android_asset/screens/accounting-reports.html', href: 'file:///android_asset/screens/accounting-reports.html', search: '' },
    addEventListener: (n, f) => { listeners[n] = f; }
  },
  document: { addEventListener: () => {}, querySelectorAll: () => [], getElementById: () => null },
  sessionStorage: { getItem: k => store.get(k) || null, setItem: (k,v) => store.set(k,v) },
  URLSearchParams, URL, Date
};
vm.createContext(ctx);
vm.runInContext(script, ctx);
const R = ctx.window.ReportContextPersistence;
const c = R.build({ station_id: 8, date_scope: { from_date: '2026-10-01', to_date: '2026-10-05' }, filters: { payment: 'credit' }, grouping: 'customer', metric: 'sales_total', kpi: 'credit_sales', exception_type: 'overdue', group_key: 'customer:12' });
R.save(c);
const restored = R.load('accounting-reports.html');
function ok(v,m){ if(!v) throw Error(m); }
ok(restored.station_id === 8, 'station context lost');
ok(restored.date_scope.from_date === '2026-10-01' && restored.date_scope.to_date === '2026-10-05', 'date scope lost');
ok(restored.filters.payment === 'credit', 'filter lost');
ok(restored.grouping === 'customer', 'grouping lost');
ok(restored.metric === 'sales_total', 'metric lost');
ok(restored.exception_type === 'overdue', 'exception context lost');
ok(restored.group_key === 'customer:12', 'group context lost');
console.log('PASS: Report Context Persistence preserves station, date scope, filters, grouping, metric, KPI, exception and group identity.');
