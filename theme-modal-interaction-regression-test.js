const fs = require('fs');
const path = require('path');
const theme = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/assets-local/css/theme.css'), 'utf8');
const supplier = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/suppliers.html'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(/\.modal-overlay\.active,\s*\.modal-overlay\.show\s*\{[\s\S]*?pointer-events:\s*auto/.test(theme), 'يجب أن تكون طبقة المودال الظاهرة بفئة show قابلة للمس');
expect(theme.includes('.modal-overlay.show :is(input, textarea, select)') && theme.includes('touch-action: manipulation'), 'يجب أن تكون حقول المودال قابلة للمس والكتابة');
expect(theme.includes('-webkit-overflow-scrolling: touch'), 'يجب توفير تمرير WebView للمحتوى الطويل');
expect(theme.includes('.modal.fade.show,') && theme.includes('.modal.fade.show :is(.modal-dialog, .modal-content)'), 'يجب أن تمتلك مودالات Bootstrap النشطة طبقة لمس صريحة');
expect(theme.includes('.modal.fade.show :is(input, textarea, select, button)'), 'يجب أن تبقى عناصر مودال المخزون تفاعلية');
expect(supplier.includes('id="modalOverlay"') && supplier.includes("classList.add('show')"), 'يجب أن تغطي القاعدة مودال المورد التشغيلي');
console.log('Theme modal interaction regression contract PASS.');
