const fs = require('fs');
const p = 'app/src/main/assets/screens/inventory-movements.html';
const s = fs.readFileSync(p, 'utf8');
function assert(c,m){ if(!c) throw new Error(m); }
assert((s.match(/class="nav-tab(?: active)?" data-tab=/g)||[]).length === 3, 'يجب أن تكون التبويبات ثلاثة فقط');
assert(s.includes('grid-template-columns:repeat(3,minmax(0,1fr))') || s.includes('grid-template-columns: repeat(3, minmax(0,1fr))'), 'التبويبات ليست في حاوية ذات ثلاثة أعمدة');
assert(s.includes('.nav-tab.active, .nav-tab:active') && s.includes('background: var(--primary-color)'), 'لون التبويب النشط ليس أزرق');
assert(s.includes('id="sunIcon"') && s.includes('id="moonIcon"'), 'أيقونات الثيم غير موجودة');
assert(s.includes('onclick="goToDashboard()">🏠'), 'زر الرئيسية غير موجود');
assert(!s.includes('↩️ رجوع'), 'زر الرجوع القديم ما زال موجوداً');
assert(s.includes('class="bottom-action-bar"') && s.includes('onclick="showCreateModal()"'), 'إجراء الحركة الجديدة ليس في الشريط السفلي');
assert(s.includes('⛽ استلام / تعبئة الخزان') && s.includes('🚚 صرف / استهلاك الوقود') && s.includes('⚖️ تسوية رصيد الخزان'), 'حركات الوقود غير مكتملة');
assert(s.includes('function toggleTheme(theme)') && s.includes("localStorage.setItem('theme'"), 'منطق الثيم غير محفوظ');
console.log('UI/UX inventory movements regression: PASS');
