const fs = require('fs');
const source = fs.readFileSync('app/src/main/assets/screens/products.html', 'utf8');

function expect(condition, message) {
  if (!condition) throw new Error(message);
}

expect(source.includes("<td>${product.status === 'active' ? 'نشط' : 'غير نشط'}</td>"),
  'جدول المنتجات لا يعتمد على status === active لعرض الحالة');
expect(source.includes("document.getElementById('is_active').value = p.status === 'active' ? '1' : '0';"),
  'نموذج تعديل المنتج لا يحافظ على الحالة النصية القادمة من قاعدة البيانات');
expect(!source.includes("<td>${product.is_active ? 'نشط' : 'غير نشط'}</td>"),
  'ما زال شرط is_active القديم مستخدمًا في عرض جدول المنتجات');
expect(!source.includes("document.getElementById('is_active').value = p.is_active ? '1' : '0';"),
  'ما زال تعيين is_active القديم مستخدمًا في نموذج التعديل');

console.log('Product status regression test PASS');
