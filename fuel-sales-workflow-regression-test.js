const fs = require('fs');
const vm = require('vm');
const { JSDOM } = require('jsdom');

const source = fs.readFileSync('app/src/main/assets/screens/fuel-sales.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

function verifyStaticWorkflowContracts() {
    expect(source.includes("listMethod:'getFuelSalesPage'"), 'تبويبات مبيعات الوقود لا تستخدم استعلام الصفحة الفعلي');
    expect(/params\.payment_method\s*=\s*state\.filter/.test(source), 'لا يتم إرسال فلتر طريقة الدفع إلى قاعدة البيانات');
    expect(/state\.filter\s*=\s*btn\.dataset\.filter\s*;\s*reloadData\s*\(\s*\)\s*;?/.test(source), 'تغيير التبويب لا يعيد الاستعلام من قاعدة البيانات');
    expect(source.includes('id="fuelAdjustmentModal"'), 'مودال مرتجع/تالف الوقود غير موجود');
    expect(source.includes('value="return">مرتجع'), 'خيار مرتجع الوقود غير موجود');
    expect(source.includes('value="damage">تالف'), 'خيار تالف الوقود غير موجود');
    expect(source.includes('processFuelSaleAdjustment'), 'مسار Android لمعالجة مرتجع/تالف الوقود غير مستخدم');
    expect(/if\s*\(!state\.pendingIdempotencyKey\)\s*state\.pendingIdempotencyKey\s*=\s*newIdempotencyKey\(\)\s*;\s*payload\.idempotency_key\s*=\s*state\.pendingIdempotencyKey\s*;/.test(source), 'عملية الوقود لا تنشئ وتستخدم مفتاح منع التكرار');
    expect(source.includes('original_quantity'), 'واجهة الوقود لا تعرض الكمية الأصلية قبل التصحيحات');
    expect(source.includes('returned_quantity'), 'واجهة الوقود لا تعرض كمية المرتجع');
    expect(source.includes('damaged_quantity'), 'واجهة الوقود لا تعرض كمية التالف');
    expect(source.includes('showDeliveryAction(saleId)'), 'زر التوصيل لا يتفعل بعد الحفظ الفعلي');
    expect(/window\.location\.href\s*=\s*'deliveries\.html\?sale_id='\s*\+\s*encodeURIComponent\(id\)/.test(source), 'زر توصيل الطلب لا يستدعي شاشة deliveries.html');

    expect(source.includes('data-sidebar="newSale"'), 'زر طلب جديد غير موجود');
    expect(source.includes('id="formModal"') && source.includes('id="recordForm"') && source.includes('id="saveBtn"'), 'عناصر مودال البيع الجديد غير مكتملة');
    expect(/case\s*'newSale'\s*:\s*openForm\(\)/.test(source), 'زر طلب جديد غير مربوط بفتح مودال البيع');
    const openFormStart = source.indexOf('async function openForm(row=null)');
    const collectFormStart = source.indexOf('function collectForm()', openFormStart);
    const openForm = source.slice(openFormStart, collectFormStart);
    expect(openForm.includes("$('recordForm').innerHTML") && openForm.includes("$('formModal').classList.add('show')"), 'فتح مودال طلب جديد لا ينشئ النموذج أو يعرضه');
    expect(/\$\('saveBtn'\)\.addEventListener\('click',\s*saveForm\)/.test(source), 'زر حفظ البيع غير مربوط بمسار الحفظ');
    const successStart = source.indexOf('const saved = parse(result)');
    const failureStart = source.indexOf('} catch (e)', successStart);
    const saveSuccess = source.slice(successStart, failureStart);
    expect(/toast\('تم حفظ عملية البيع رقم '\s*\+\s*saleId[\s\S]*?false,\s*'form'\)/.test(saveSuccess), 'إشعار النجاح لا يظهر داخل مودال طلب جديد');
    expect(saveSuccess.includes('showDeliveryAction(saleId)'), 'حفظ البيع لا يفعّل إجراء التوصيل بعد النجاح');
    expect(!saveSuccess.includes("closeModal(null, 'formModal')"), 'مودال طلب جديد يُغلق قبل أن يتمكن المستخدم من مراجعة نتيجة الحفظ');

    const inlineScript = [...source.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
        .map(match => match[1])
        .find(script => script.includes('async function saveForm()'));
    expect(Boolean(inlineScript), 'سكربت شاشة مبيعات الوقود غير موجود');
    new vm.Script(inlineScript, { filename: 'fuel-sales.html:inline-script' });
    return inlineScript;
}

function bridgeResponse(data) {
    return JSON.stringify({ success: true, data });
}

async function waitFor(predicate, message, timeoutMs = 2000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await new Promise(resolve => setTimeout(resolve, 5));
    }
    throw new Error(message);
}

async function verifyNewSaleModalRuntime(inlineScript) {
    let saveCalls = 0;
    let savedPayload = null;
    const bridge = {
        getFuelSalesPage: () => bridgeResponse([]),
        getCurrentShift: () => bridgeResponse({ shift_id: 8 }),
        getPumps: () => bridgeResponse([{ id: 3, pump_name: 'مضخة اختبار' }]),
        getFuelTypes: () => bridgeResponse([{ id: 2, fuel_name: 'ديزل', default_sale_price: 2.5 }]),
        getCustomers: () => bridgeResponse([]),
        getPumpNozzles: () => bridgeResponse([{ id: 5, nozzle_number: 'فوهة اختبار', fuel_type_id: 2 }]),
        saveFuelSaleRecord: json => {
            saveCalls += 1;
            savedPayload = JSON.parse(json);
            return JSON.stringify({
                success: true,
                sale_id: 777,
                quantity: 5,
                total_amount: 12.5,
                delivery_fee_display_total: 12.5,
                sms: {}
            });
        }
    };
    const dom = new JSDOM(source, {
        runScripts: 'outside-only',
        url: 'https://fuel-sales.test/',
        pretendToBeVisual: true
    });
    const { window } = dom;
    window.AndroidInterface = bridge;
    window.HTMLFormElement.prototype.reportValidity = () => true;

    try {
        window.eval(inlineScript);
        window.document.querySelector('[data-sidebar="newSale"]').click();
        const modal = window.document.getElementById('formModal');
        await waitFor(() => modal.classList.contains('show'), 'زر طلب جديد لم يفتح المودال');
        await waitFor(() => window.document.querySelector('#recordForm [data-key="pump_id"] option[value="3"]'), 'لم تُحمّل المضخة في نموذج البيع');

        const form = window.document.getElementById('recordForm');
        const setValue = (key, value) => {
            const field = form.querySelector(`[data-key="${key}"]`);
            if (!field) throw new Error(`حقل ${key} غير موجود في المودال`);
            field.value = value;
        };
        setValue('pump_id', '3');
        form.querySelector('[data-key="pump_id"]').dispatchEvent(new window.Event('change', { bubbles: true }));
        await waitFor(() => form.querySelector('[data-key="nozzle_id"] option[value="5"]'), 'لم تُحمّل فوهة المضخة المختارة');

        setValue('nozzle_id', '5');
        setValue('fuel_type_id', '2');
        setValue('quantity', '5');
        setValue('price_per_liter', '2.5');
        setValue('total_amount', '12.5');
        setValue('payment_method', 'cash');
        window.document.getElementById('saveBtn').click();

        await waitFor(() => saveCalls === 1, 'زر الحفظ لم يستدعِ جسر Android مرة واحدة');
        const formToast = window.document.getElementById('formToast');
        await waitFor(() => formToast.classList.contains('show'), 'لم يظهر إشعار الحفظ داخل مودال البيع');
        expect(formToast.textContent.includes('777'), 'إشعار المودال لا يعرض رقم عملية البيع المحفوظة');
        expect(modal.classList.contains('show'), 'المودال لا يبقى مفتوحًا لمراجعة نتيجة الحفظ');
        expect(savedPayload && Boolean(savedPayload.idempotency_key), 'الحمولة المحفوظة تفتقد مفتاح منع التكرار');
        const deliveryButton = window.document.getElementById('deliveryBtn');
        expect(!deliveryButton.disabled && deliveryButton.dataset.saleId === '777', 'زر التوصيل لا يُفعّل برقم البيع الحقيقي');
    } finally {
        dom.window.close();
    }
}

async function run() {
    const inlineScript = verifyStaticWorkflowContracts();
    await verifyNewSaleModalRuntime(inlineScript);
    console.log('Fuel sales workflow regression PASS.');
    console.log('Fuel sale modal runtime PASS: open, lookups, save, in-modal success, idempotency, and delivery action.');
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
