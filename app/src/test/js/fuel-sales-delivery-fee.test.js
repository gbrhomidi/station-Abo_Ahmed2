'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const projectRoot = path.resolve(__dirname, '../..');
const htmlPath = path.join(projectRoot, 'main/assets/screens/fuel-sales.html');
const mainActivityPath = path.join(projectRoot, 'main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt');
const html = fs.readFileSync(htmlPath, 'utf8');
const mainActivity = fs.readFileSync(mainActivityPath, 'utf8');
const asyncBridge = require('../../main/assets/assets-local/js/fuel-sales-async-bridge.js');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitFor(predicate, label, timeoutMs = 6000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
        if (predicate()) return;
        await delay(20);
    }
    throw new Error('Timed out waiting for ' + label);
}

function inlineApplicationScript() {
    const dom = new JSDOM(html);
    const scripts = Array.from(dom.window.document.querySelectorAll('script:not([src])'));
    const script = scripts.find(item => item.textContent.includes('async function saveForm()'));
    dom.window.close();
    assert.ok(script, 'fuel-sales.html must contain its page application script');
    return script.textContent;
}

async function runUiRegression() {
    const calls = { saves: [], updates: [], operations: [] };
    const completed = { formLookups: 0, locations: 0 };
    let lastSavedRow = null;
    let saleSequence = 700;
    let deferredLocationRequest = null;
    let deferFirstLocationRead = true;
    let failNextWrite = false;

    const dom = new JSDOM(html, {
        runScripts: 'outside-only',
        url: 'https://station.local/fuel-sales.html',
        pretendToBeVisual: true
    });
    const { window } = dom;
    window.FuelSalesAsyncBridge = asyncBridge;
    window.HTMLFormElement.prototype.reportValidity = () => true;

    const json = value => JSON.stringify(value);
    const nativeBridge = {
        executeFuelSalesAsync(requestId, operation, argsJson) {
            const args = JSON.parse(argsJson || '[]');
            calls.operations.push(operation);
            let response;
            switch (operation) {
                case 'fuelSalesPage':
                    response = { success: true, data: { rows: lastSavedRow ? [lastSavedRow] : [] } };
                    break;
                case 'formLookups':
                    response = {
                        success: true,
                        shift: json({ success: true, data: { shift_id: 71, station_id: 1 } }),
                        pumps: json({ success: true, data: [{ id: 3, pump_name_ar: 'مضخة اختبار', pump_name: 'Test Pump' }] }),
                        fuels: json({ success: true, data: [{ id: 1, fuel_name_ar: 'ديزل', fuel_name: 'Diesel', default_sale_price: 1000 }] }),
                        customers: json({ success: true, data: [] })
                    };
                    break;
                case 'nozzles':
                    response = { success: true, data: [{ id: 5, nozzle_number: 'A', nozzle_code: 'TEST-A', fuel_type_id: 1 }] };
                    break;
                case 'locations':
                    response = { success: true, data: ['حي المحطة', '  حي المحطة  '] };
                    break;
                case 'promotions':
                    response = { success: true, data: [] };
                    break;
                case 'resolvedPrice':
                    response = { success: true, data: { unit_price: 1000, source: 'test-default', source_id: 1 } };
                    break;
                case 'save': {
                    const payload = JSON.parse(args[0]);
                    calls.saves.push(payload);
                    if (failNextWrite) {
                        failNextWrite = false;
                        response = { success: false, error: 'SQLite test failure: sale was not saved' };
                        break;
                    }
                    const grossQuantity = Number(payload.quantity);
                    const unitPrice = Number(payload.price_per_liter);
                    const grossAmount = Math.round(grossQuantity * unitPrice * 100) / 100;
                    const fee = Number(payload.delivery_service_fee || 0);
                    const mode = payload.delivery_fee_mode;
                    const deduct = mode === 'deduct_from_sales';
                    const fuelAmount = deduct ? grossAmount - fee : grossAmount;
                    const fuelQuantity = deduct ? fuelAmount / unitPrice : grossQuantity;
                    const invoiceTotal = mode === 'add_to_sales' || mode === 'driver'
                        ? grossAmount + fee : grossAmount;
                    const id = ++saleSequence;
                    lastSavedRow = {
                        fuel_sale_id: id,
                        sale_id: id + 1000,
                        quantity: fuelQuantity,
                        total_amount: fuelAmount,
                        gross_quantity: grossQuantity,
                        gross_amount: grossAmount,
                        price_per_liter: unitPrice,
                        delivery_service_fee: fee,
                        delivery_fee_mode: mode,
                        delivery_location: payload.delivery_location,
                        delivery_fee_display_total: invoiceTotal
                    };
                    response = {
                        success: true,
                        id,
                        sale_id: id,
                        quantity: fuelQuantity,
                        total_amount: fuelAmount,
                        gross_quantity: grossQuantity,
                        gross_amount: grossAmount,
                        invoice_total: invoiceTotal,
                        delivery_fee_display_total: invoiceTotal,
                        delivery_fee_accounting: { success: true, mode },
                        sms: { queued: false }
                    };
                    break;
                }
                case 'update': {
                    const payload = JSON.parse(args[1]);
                    calls.updates.push({ id: Number(args[0]), payload });
                    if (failNextWrite) {
                        failNextWrite = false;
                        response = { success: false, error: 'SQLite test failure: update was not saved' };
                        break;
                    }
                    lastSavedRow = {
                        ...lastSavedRow,
                        ...payload,
                        delivery_location: payload.delivery_location,
                        delivery_service_fee: Number(payload.delivery_service_fee || 0),
                        delivery_fee_mode: payload.delivery_fee_mode
                    };
                    response = { success: true, data: { sale_id: lastSavedRow.sale_id } };
                    break;
                }
                default:
                    response = { success: true, data: [] };
            }

            if (operation === 'locations' && deferFirstLocationRead) {
                deferFirstLocationRead = false;
                deferredLocationRequest = { requestId, response };
                return json({ success: true, queued: true });
            }

            window.setTimeout(() => {
                if (operation === 'formLookups') completed.formLookups += 1;
                if (operation === 'locations') completed.locations += 1;
                if (typeof window.__fuelSalesNativeResult === 'function') {
                    window.__fuelSalesNativeResult(requestId, json(response));
                }
            }, 0);
            return json({ success: true, queued: true });
        }
    };
    window.AndroidInterface = nativeBridge;
    window.eval(inlineApplicationScript());

    function resolveDeferredLocationRead() {
        assert.ok(deferredLocationRequest, 'the initial SQLite locations read must be held for the race test');
        const pending = deferredLocationRequest;
        deferredLocationRequest = null;
        completed.locations += 1;
        window.__fuelSalesNativeResult(pending.requestId, json(pending.response));
    }

    async function prepareNewSaleAt(locationValue) {
        const document = window.document;
        const pump = document.querySelector('[data-key="pump_id"]');
        pump.value = '3';
        pump.dispatchEvent(new window.Event('change', { bubbles: true }));
        await waitFor(() => document.querySelector('[data-key="nozzle_id"]').options.length > 1,
            'pump nozzle choices');
        const nozzle = document.querySelector('[data-key="nozzle_id"]');
        nozzle.value = '5';
        nozzle.dispatchEvent(new window.Event('change', { bubbles: true }));

        const fuel = document.querySelector('[data-key="fuel_type_id"]');
        fuel.value = '1';
        fuel.dispatchEvent(new window.Event('change', { bubbles: true }));
        await waitFor(() => document.querySelector('[data-key="price_per_liter"]').value === '1000',
            'resolved fuel price');

        const quantity = document.querySelector('[data-key="quantity"]');
        quantity.value = '10';
        quantity.dispatchEvent(new window.Event('input', { bubbles: true }));
        const mode = document.querySelector('[data-key="delivery_fee_mode"]');
        mode.value = 'deduct_from_sales';
        mode.dispatchEvent(new window.Event('change', { bubbles: true }));
        const fee = document.querySelector('[data-key="delivery_service_fee"]');
        fee.value = '100';
        fee.dispatchEvent(new window.Event('input', { bubbles: true }));
        const location = document.querySelector('[data-key="delivery_location"]');
        location.value = locationValue;
        location.dispatchEvent(new window.Event('input', { bubbles: true }));
    }

    try {
        await waitFor(() => calls.operations.includes('fuelSalesPage'), 'initial fuel-sale page load');
        await window.openForm(null);
        await waitFor(() => !!window.document.querySelector('[data-key="delivery_fee_mode"]'), 'new sale form');
        await waitFor(() => calls.operations.includes('locations'), 'dispatch of saved-location SQLite read');
        await waitFor(() => completed.formLookups > 0 &&
            window.document.querySelector('[data-key="pump_id"]').options.length > 1,
            'fuel-sale form lookup choices');

        const { document } = window;
        const locationList = document.getElementById('savedDeliveryLocations');
        assert.ok(locationList, 'delivery location autocomplete list must exist');
        assert.equal(locationList.options.length, 0,
            'the first SQLite locations response is deliberately pending, leaving the initial cache empty');

        const pump = document.querySelector('[data-key="pump_id"]');
        pump.value = '3';
        pump.dispatchEvent(new window.Event('change', { bubbles: true }));
        await waitFor(() => calls.operations.includes('nozzles'), 'pump nozzle lookup');
        const nozzle = document.querySelector('[data-key="nozzle_id"]');
        await waitFor(() => nozzle.options.length > 1, 'nozzle choices');
        nozzle.value = '5';
        nozzle.dispatchEvent(new window.Event('change', { bubbles: true }));

        const fuel = document.querySelector('[data-key="fuel_type_id"]');
        fuel.value = '1';
        fuel.dispatchEvent(new window.Event('change', { bubbles: true }));
        await waitFor(() => document.querySelector('[data-key="price_per_liter"]').value === '1000', 'resolved fuel price');

        const quantity = document.querySelector('[data-key="quantity"]');
        quantity.value = '10';
        quantity.dispatchEvent(new window.Event('input', { bubbles: true }));
        const mode = document.querySelector('[data-key="delivery_fee_mode"]');
        mode.value = 'deduct_from_sales';
        mode.dispatchEvent(new window.Event('change', { bubbles: true }));
        const fee = document.querySelector('[data-key="delivery_service_fee"]');
        fee.value = '100';
        fee.dispatchEvent(new window.Event('input', { bubbles: true }));

        const location = document.querySelector('[data-key="delivery_location"]');
        location.value = '';
        await window.saveForm();
        assert.equal(calls.saves.length, 0,
            'the UI must stop a fee sale before invoking SQLite when no location is selected');

        // A failed SQLite write must not publish an unsaved location as a suggestion.
        location.value = 'موقع فشل غير محفوظ';
        location.dispatchEvent(new window.Event('input', { bubbles: true }));
        failNextWrite = true;
        await window.saveForm();
        assert.equal(calls.saves.length, 1, 'the failed save must reach SQLite exactly once');
        assert.equal(Array.from(locationList.options).some(option => option.value === 'موقع فشل غير محفوظ'), false,
            'a failed save must never be reflected in the saved-location list');

        // A new location appears only after SQLite confirms the sale.
        location.value = 'شارع الاختبار';
        location.dispatchEvent(new window.Event('input', { bubbles: true }));
        await window.saveForm();
        assert.equal(calls.saves.length, 2, 'the subsequent successful sale must be persisted');
        assert.equal(calls.saves[1].delivery_location, 'شارع الاختبار');
        assert.equal(calls.saves[1].delivery_fee_mode, 'deduct_from_sales');
        assert.equal(locationList.options.length, 1,
            'a successful SQLite save must update the current datalist without waiting for a reload');
        assert.equal(locationList.options[0].value, 'شارع الاختبار',
            'the new location must appear immediately after SQLite confirmation');

        // Return the stale pre-save SQLite snapshot after the successful write.
        resolveDeferredLocationRead();
        await waitFor(() => completed.locations === 1 &&
            Array.from(locationList.options).some(option => option.value === 'حي المحطة'),
            'the delayed SQLite location read');
        const mergedLocations = Array.from(locationList.options).map(option => option.value);
        assert.ok(mergedLocations.includes('شارع الاختبار'),
            'the older SQLite snapshot must not erase a location just confirmed by the sale');
        assert.equal(mergedLocations.filter(value => value === 'حي المحطة').length, 1,
            'duplicate values from SQLite must normalize to one suggestion');
        assert.equal(mergedLocations.filter(value => value === 'شارع الاختبار').length, 1,
            'the newly saved location must remain unique after the delayed response');

        await waitFor(() => lastSavedRow !== null, 'saved row');
        assert.equal(quantity.value, '10', 'after deduction the editor must retain gross quantity as its calculation basis');
        assert.equal(document.querySelector('[data-key="total_amount"]').value, '10000.00',
            'after deduction the editor must retain gross fuel amount rather than the net fuel amount');
        assert.ok(document.getElementById('deliveryFeeDisplaySummary').value.includes('صافي الكمية المباعة = 9.900 لتر'),
            'the immediate post-save summary must deduct the service fee only once');

        await window.openForm(lastSavedRow);
        await waitFor(() => {
            const reopenedMode = document.querySelector('[data-key="delivery_fee_mode"]');
            return !!reopenedMode && reopenedMode.value === 'deduct_from_sales';
        }, 'reopening the saved deduction sale and restoring its saved fields');
        assert.equal(document.querySelector('[data-key="quantity"]').value, '10',
            'reopening a deduction sale must restore gross_quantity');
        assert.equal(document.querySelector('[data-key="total_amount"]').value, '10000.00',
            'reopening a deduction sale must restore gross_amount');
        assert.ok(document.getElementById('deliveryFeeDisplaySummary').value.includes('صافي الكمية المباعة = 9.900 لتر'),
            'reopening must not apply the delivery fee a second time');

        // A new sale may reuse a previously saved location without adding another option.
        await window.openForm(null);
        await waitFor(() => !!document.querySelector('[data-key="delivery_fee_mode"]') &&
            document.querySelector('[data-key="pump_id"]').options.length > 1,
            'second new sale form');
        await prepareNewSaleAt('حي المحطة');
        await window.saveForm();
        assert.equal(calls.saves.length, 3, 'a new sale at an existing location must be persisted');
        assert.equal(Array.from(locationList.options).filter(option => option.value === 'حي المحطة').length, 1,
            'a new sale using an existing location must not duplicate the suggestion');

        // An accepted edit may add a new location; the update response is the commit boundary.
        await window.openForm(lastSavedRow);
        await waitFor(() => !!document.querySelector('[data-key="delivery_location"]') &&
            document.querySelector('[data-key="delivery_location"]').value === 'حي المحطة',
            'editing a previously saved sale');
        const editedLocation = document.querySelector('[data-key="delivery_location"]');
        editedLocation.value = 'موقع تعديل محفوظ';
        editedLocation.dispatchEvent(new window.Event('input', { bubbles: true }));
        await window.saveForm();
        assert.equal(calls.updates.length, 1, 'an edit must use the SQLite update operation');
        assert.equal(calls.updates[0].payload.delivery_location, 'موقع تعديل محفوظ');
        assert.equal(Array.from(locationList.options).filter(option => option.value === 'موقع تعديل محفوظ').length, 1,
            'a new location from a successful update must appear exactly once');

        await window.openForm(lastSavedRow);
        await waitFor(() => !!document.querySelector('[data-key="delivery_location"]') &&
            document.querySelector('[data-key="delivery_location"]').value === 'موقع تعديل محفوظ',
            'reopening the edited sale');
        assert.ok(Array.from(locationList.options).some(option => option.value === 'موقع تعديل محفوظ'),
            'the edited location must remain suggested after reopening the saved sale');

        const feeGroup = document.querySelector('.delivery-fee-form-group .delivery-fee-composite');
        assert.ok(feeGroup, 'fee controls must be grouped in a dedicated layout container');
        assert.ok(feeGroup.querySelector('[data-key="delivery_fee_mode"]') &&
            feeGroup.querySelector('[data-key="delivery_service_fee"]'),
            'both fee controls must remain available in their original logical order');
        assert.match(html, /@media\s*\(max-width:\s*380px\)[\s\S]*?\.delivery-fee-composite\s*\{[^}]*flex-direction:\s*column/s,
            'narrow screens must stack the fee mode before the amount input');
        assert.doesNotMatch(html, /#formModal\s+#recordForm\s+\*:[^{]*font-size:\s*0\.83rem\s*!important/,
            'the old blanket !important rule must not override the fee-specific mobile sizing');

        // Static contract guard for the actual Kotlin bridge response, not merely this JS double.
        assert.match(mainActivity, /put\("total_amount",\s*input\.optDouble\("total_amount",\s*0\.0\)\)/,
            'Kotlin save response total_amount must remain fuel-only');
        assert.match(mainActivity, /put\("invoice_total",\s*invoiceTotal\)/,
            'Kotlin save response must expose invoice_total separately');
        assert.match(mainActivity, /put\("gross_quantity",\s*originalQuantity\)/);
        assert.match(mainActivity, /put\("gross_amount",\s*grossAmount\)/);
        console.log('Fuel-sales delivery-fee UI and bridge contract regression tests passed.');
    } finally {
        dom.window.close();
    }
}

module.exports = async function runFuelSalesDeliveryFeeTests() {
    await runUiRegression();
};
