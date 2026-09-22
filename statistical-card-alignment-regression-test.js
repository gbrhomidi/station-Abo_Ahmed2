const fs = require('fs');
const path = require('path');
const theme = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/assets-local/css/theme.css'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(theme.includes(':where(.kpi-card, .stat-card, .stats-card, .metric-card, .summary-card, .dashboard-stat) {'), 'يجب أن يغطي التوسيط كل بطاقات المؤشرات المعروفة');
expect(theme.includes('text-align: center;') && theme.includes('margin-inline: auto;'), 'يجب توسيط تسميات وقيم المؤشرات دون تغيير البيانات');
console.log('Statistical card alignment regression PASS.');
