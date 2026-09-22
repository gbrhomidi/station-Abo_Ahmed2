const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/customer-debts.html'), 'utf8');
const scriptBlocks = [...source.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(match => match[1]);
const expect = (condition, message) => { if (!condition) throw new Error(message); };

scriptBlocks.forEach((script, index) => new Function(script));
expect(source.includes('bridge.getPartyCrmBundleAsync(requestId, Number(partyId))'), 'يلزم جسر تفاصيل العميل غير المتزامن');
expect(source.includes('customerDebtDetailsCallbacks = new Map()'), 'يلزم حفظ callback مؤقت وقابل للإلغاء');
expect(source.includes('انتهت مهلة تحميل تفاصيل العميل'), 'يلزم حد زمني صريح');
expect(source.includes("Array.isArray(c.invoices) ? c.invoices : []"), 'يجب استهلاك فواتير SQLite من bundle الحقيقي');
expect(source.includes('cancelPendingCustomerDebtDetails();'), 'يلزم تنظيف callbacks عند إغلاق المودال أو مغادرة الشاشة');
expect(!/async function viewCustomerDetails\([\s\S]*?Promise\.all\(/.test(source), 'لا يجوز تنفيذ قراءتي جسر متزامنتين قبل فتح مودال التفاصيل');
console.log(`Customer debts modal async bridge regression PASS (${scriptBlocks.length} script block(s)).`);
