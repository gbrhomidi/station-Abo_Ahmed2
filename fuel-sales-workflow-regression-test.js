const fs = require('fs');
const vm = require('vm');
const { JSDOM } = require('jsdom');
const FuelSalesAsyncBridge = require('./app/src/main/assets/assets-local/js/fuel-sales-async-bridge.js');

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
    expect(source.includes('fuel_sale_id: Number((fuelAdjustmentRow && fuelAdjustmentRow.fuel_sale_id) || 0)'), 'مسار المرتجع لا يرسل معرف سجل الوقود الصحيح');
    expect(source.includes('original_quantity ?? r.quantity'), 'قائمة المرتجعات لا تعرض الكمية الأصلية المباعة');
    expect(source.includes('!(available > 0)'), 'واجهة المرتجع لا تمنع الإرجاع بعد نفاد الرصيد');
    expect(source.includes('showDeliveryAction(saleId)'), 'زر التوصيل لا يتفعل بعد الحفظ الفعلي');
    expect(/window\.location\.href\s*=\s*'deliveries\.html\?sale_id='\s*\+\s*encodeURIComponent\(id\)/.test(source), 'زر توصيل الطلب لا يستدعي شاشة deliveries.html');

    expect(source.includes('data-sidebar="newSale"'), 'زر طلب جديد غير موجود');
    expect(source.includes('id="formModal"') && source.includes('id="recordForm"') && source.includes('id="saveBtn"'), 'عناصر مودال البيع الجديد غير مكتملة');
    expect(/case\s*'newSale'\s*:\s*openForm\(\)/.test(source), 'زر طلب جديد غير مربوط بفتح مودال البيع');
    const openFormStart = source.indexOf('function openForm(row=null)');
    const collectFormStart = source.indexOf('function collectForm()', openFormStart);
    const openForm = source.slice(openFormStart, collectFormStart);
    expect(openForm.includes('form.innerHTML = (CFG.fields || []).map(def => inputHtml(def, formRow)).join(\'\')') && openForm.includes('modal.classList.add(\'show\')'), 'فتح مودال طلب جديد لا ينشئ النموذج أو يعرضه');
    expect(/\$\('saveBtn'\)\.addEventListener\('click',\s*saveForm\)/.test(source), 'زر حفظ البيع غير مربوط بمسار الحفظ');
    const successStart = source.indexOf("showFormToast('تم حفظ عملية البيع رقم '");
    const failureStart = source.indexOf('} catch (e)', successStart);
    const saveSuccess = source.slice(successStart, failureStart);
    expect(saveSuccess.includes('showFormToast(') && saveSuccess.includes('smsNote.trim()'), 'إشعار النجاح ونتيجة SMS لا يظهران داخل مودال طلب جديد');
    expect(saveSuccess.includes('showDeliveryAction(saleId)'), 'حفظ البيع لا يفعّل إجراء التوصيل بعد النجاح');
    expect(!saveSuccess.includes("closeModal(null, 'formModal')"), 'مودال طلب جديد يُغلق قبل أن يتمكن المستخدم من مراجعة نتيجة الحفظ');
    expect(source.includes('data-delivery-fee-charge-dependent') && source.includes('data-delivery-location-field="true"'), 'حقلا الموقع ورسوم الخدمة لا يتبعان قواعد ظهور مستقلة');
    expect(/deliveryFeeDisplaySummary[^\n]*rows="5"/.test(source), 'مساحة ملخص إجمالي رسوم التوصيل لم تزد سطرين');
    expect(source.includes("cashSaleOption.hidden = !allowsCashSale") && source.includes("cashSaleOption.disabled = !allowsCashSale"), 'خيار المبيعات النقدية لا يختفي عند اختيار دفع غير نقدي');
    expect(source.includes("value=\"' + esc(fee) + '\"></div>'"), 'حقل رسوم خدمة التوصيل ما زال يعرض قيمة افتراضية');

    const inlineScript = [...source.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
        .map(match => match[1])
        .find(script => script.includes('async function saveForm()'));
    expect(Boolean(inlineScript), 'سكربت شاشة مبيعات الوقود غير موجود');
    new vm.Script(inlineScript, { filename: 'fuel-sales.html:inline-script' });
    return inlineScript;
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
    let window;
    const bridge = {
        executeFuelSalesAsync: (requestId, operation, argsJson) => {
            const args = JSON.parse(argsJson || '[]');
            let result;
            if (operation === 'formLookups') {
                result = {
                    shift: { shift_id: 8 },
                    pumps: [{ id: 3, pump_name: 'مضخة اختبار' }],
                    fuels: [{ id: 2, fuel_name: 'ديزل', default_sale_price: 2.5 }],
                    customers: [{ id: 11, commercial_name_ar: 'عميل آجل للاختبار' }],
                    paymentTerms: [{ id: 21, code: 'AFTER_1', name_ar: 'بعد يوم واحد', days: 1, applies_to: 'customer' }]
                };
            } else if (operation === 'nozzles') {
                result = [{ id: 5, nozzle_number: 'فوهة اختبار', fuel_type_id: 2 }];
            } else if (operation === 'resolvedPrice') {
                result = { unit_price: 2.5 };
            } else if (operation === 'scope') {
                result = { user_id: 4, station_id: 6 };
            } else if (operation === 'locations') {
                result = { user_id: 4, station_id: 6, rows: [] };
            } else if (operation === 'promotions' || operation === 'fuelSalesPage') {
                result = [];
            } else if (operation === 'save') {
                saveCalls += 1;
                savedPayload = JSON.parse(args[0]);
                result = {
                    success: true,
                    sale_id: 777,
                    quantity: 5,
                    total_amount: 12.5,
                    delivery_fee_display_total: 12.5,
                    user_id: 4,
                    station_id: 6,
                    sms: { queued: true, customer_name: 'عميل آجل للاختبار' }
                };
            } else {
                result = { success: false, error: 'عملية اختبار غير معروفة: ' + operation };
            }
            window.setTimeout(() => {
                window.__fuelSalesNativeResult(requestId, JSON.stringify(result));
            }, 0);
            return JSON.stringify({ success: true, queued: true });
        }
    };
    const dom = new JSDOM(source, {
        runScripts: 'outside-only',
        url: 'https://fuel-sales.test/',
        pretendToBeVisual: true
    });
    window = dom.window;
    window.AndroidInterface = bridge;
    window.FuelSalesAsyncBridge = FuelSalesAsyncBridge;
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
        const payment = form.querySelector('[data-key="payment_method"]');
        const customer = form.querySelector('[data-key="customer_id"]');
        payment.value = 'cash';
        payment.dispatchEvent(new window.Event('change', { bubbles: true }));
        customer.value = 'cash_sale';
        customer.dispatchEvent(new window.Event('change', { bubbles: true }));
        payment.value = 'credit';
        payment.dispatchEvent(new window.Event('change', { bubbles: true }));
        const cashSaleOption = Array.from(customer.options).find(option => option.value === 'cash_sale');
        expect(cashSaleOption.hidden && cashSaleOption.disabled, 'خيار مبيعات نقدية ما زال متاحاً للبيع الآجل');
        expect(customer.value === '', 'اختيار المبيعات النقدية لم يُمسح عند التحويل إلى البيع الآجل');
        customer.value = '11';
        customer.dispatchEvent(new window.Event('change', { bubbles: true }));
        const paymentTerm = form.querySelector('[data-key="payment_terms_id"]');
        paymentTerm.value = '21';
        paymentTerm.dispatchEvent(new window.Event('change', { bubbles: true }));

        const deliveryLocation = form.querySelector('[data-key="delivery_location"]');
        const deliveryFee = form.querySelector('[data-key="delivery_service_fee"]');
        const feeMode = form.querySelector('[data-key="delivery_fee_mode"]');
        expect(deliveryLocation.closest('[data-delivery-location-field]').style.display !== 'none', 'موقع التوصيل مخفي عند إعفاء رسوم التوصيل');
        expect(deliveryFee.value === '', 'حقل رسوم خدمة التوصيل لا يبدأ فارغاً');
        const locationStyle = window.getComputedStyle(deliveryLocation);
        expect(locationStyle.fontWeight === '700' && locationStyle.color === 'rgb(0, 0, 0)', 'خط موقع التوصيل لا يظهر أسود وعريض');
        feeMode.value = 'add_to_sales';
        feeMode.dispatchEvent(new window.Event('change', { bubbles: true }));
        expect(deliveryFee.value === '', 'حقل رسوم الخدمة يُملأ بالصفر عند اختيار طريقة الاحتساب');
        const feeSummary = form.querySelector('#deliveryFeeDisplaySummary');
        expect(feeSummary.rows === 5, 'مساحة ملخص الإجمالي لا تعرض خمسة أسطر');
        const summaryStyle = window.getComputedStyle(feeSummary);
        expect(summaryStyle.fontWeight === '700' && summaryStyle.color === 'rgb(0, 0, 0)', 'خط إجمالي رسوم التوصيل لا يظهر أسود وعريض');
        feeMode.value = 'exempt';
        feeMode.dispatchEvent(new window.Event('change', { bubbles: true }));

        window.document.getElementById('saveBtn').click();

        await waitFor(() => saveCalls === 1, 'زر الحفظ لم يستدعِ جسر Android مرة واحدة');
        const formToast = window.document.getElementById('formToast');
        await waitFor(() => formToast.classList.contains('show'), 'لم يظهر إشعار الحفظ داخل مودال البيع');
        expect(formToast.textContent.includes('777'), 'إشعار المودال لا يعرض رقم عملية البيع المحفوظة');
        expect(formToast.textContent.includes('تم وضع رسالة SMS للعميل عميل آجل للاختبار في طابور الإرسال'), 'نتيجة رسالة SMS للبيع الآجل لا تظهر في إشعار المودال');
        expect(modal.classList.contains('show'), 'المودال لا يبقى مفتوحًا لمراجعة نتيجة الحفظ');
        expect(savedPayload && savedPayload.customer_id === 11 && savedPayload.payment_method === 'credit', 'حمولة البيع الآجل لا تتضمن العميل وطريقة الدفع الصحيحة');
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
