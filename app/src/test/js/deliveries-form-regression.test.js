'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const projectRoot = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(projectRoot, 'main/assets/screens/deliveries.html'), 'utf8');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitFor(predicate, label, timeoutMs = 5000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
        if (predicate()) return;
        await delay(15);
    }
    throw new Error('Timed out waiting for ' + label);
}

function applicationScript() {
    const parsed = new JSDOM(html);
    const scripts = Array.from(parsed.window.document.querySelectorAll('script:not([src])'));
    const script = scripts.find(item => item.textContent.includes('function loadLatestCustomerSale'));
    parsed.window.close();
    assert.ok(script, 'deliveries.html must contain its application controller');
    return script.textContent;
}

function testFeeNormalizer() {
    const match = applicationScript().match(
        /function normalizeDeliveryFeeInput\(value\)\s*\{[\s\S]*?\n    \}\s*\n\n    function setDeliveryFeeInput\(value\)\s*\{[\s\S]*?\n    \}/
    );
    assert.ok(match, 'the production screen must expose the centralized fee normalizer and setter');

    const input = { value: '' };
    const helpers = new Function('$', match[0] + '\nreturn { normalizeDeliveryFeeInput, setDeliveryFeeInput };')(
        id => id === 'f_delivery_service_fee' ? input : null
    );

    for (const invalid of [null, undefined, '', '.', ',', '-.', '-,', 'NaN', 'invalid', '-12.50']) {
        assert.equal(helpers.normalizeDeliveryFeeInput(invalid), '0.00',
            'invalid fee input must normalize to zero: ' + String(invalid));
    }
    assert.equal(helpers.normalizeDeliveryFeeInput('12.5'), '12.50');
    assert.equal(helpers.normalizeDeliveryFeeInput('1,234.5'), '1234.50');
    helpers.setDeliveryFeeInput('.');
    assert.equal(input.value, '0.00');
    helpers.setDeliveryFeeInput('89.7');
    assert.equal(input.value, '89.70');
}

async function run() {
    const calls = { customers: 0, latest: [], recent: 0, page: 0 };
    const salesByCustomer = {
        11: {
            sale_id: 7011, fuel_sale_id: 7011, customer_party_id: 11,
            quantity: 10, price_per_liter: 1000, total_amount: 10000,
            delivery_service_fee: 125.75, delivery_fee_mode: 'driver',
            delivery_location: 'حي الاختبار', payment_method: 'cash', fuel_type_name: 'ديزل'
        },
        33: {
            sale_id: 7033, fuel_sale_id: 7033, customer_party_id: 33,
            quantity: 8, price_per_liter: 900, total_amount: 7200,
            delivery_service_fee: 88.5, delivery_fee_mode: 'driver',
            delivery_location: 'شارع الاختبار', payment_method: 'cash', fuel_type_name: 'ديزل'
        }
    };
    const json = value => JSON.stringify(value);
    const bridge = {
        getDeliveriesPage() {
            calls.page += 1;
            return json({ success: true, data: { rows: [] } });
        },
        getCustomers() {
            calls.customers += 1;
            return json({ success: true, data: [
                { id: 11, name: 'عميل لديه بيع سابق' },
                { id: 22, name: 'عميل بلا بيع سابق' },
                { id: 33, name: 'عميل آخر لديه بيع سابق' },
                { id: 11, name: 'نسخة مكررة للعميل الأول' }
            ] });
        },
        getVehicleRecords() { return json({ success: true, data: [] }); },
        getDriverRecords() { return json({ success: true, data: [] }); },
        getCurrentShift() { return json({ success: true, data: null }); },
        getRecentSaleCustomerIds() {
            calls.recent += 1;
            return json({ success: true, data: [33, 11] });
        },
        getLatestCustomerSaleForDelivery(customerId) {
            calls.latest.push(Number(customerId));
            const sale = salesByCustomer[Number(customerId)];
            return json({
                success: true,
                data: sale
                    ? { found: true, sale }
                    : { found: false, message: 'لا توجد عملية بيع سابقة' }
            });
        }
    };

    const dom = new JSDOM(html, {
        runScripts: 'outside-only',
        url: 'https://station.local/deliveries.html',
        pretendToBeVisual: true
    });
    const { window } = dom;
    window.AndroidInterface = bridge;
    window.HTMLFormElement.prototype.reportValidity = () => true;
    window.eval(applicationScript());

    try {
        await waitFor(() => calls.page > 0, 'initial deliveries page load');
        await window.openCreateModal();

        const document = window.document;
        const fee = document.getElementById('f_delivery_service_fee');
        const party = document.getElementById('f_party_display');
        const menu = document.getElementById('partyMenu');
        const openCustomerPicker = () => {
            party.value = '';
            party.dispatchEvent(new window.Event('input', { bubbles: true }));
            party.blur();
            party.focus();
        };

        assert.ok(fee, 'the delivery form must render its service-fee field');
        assert.equal(fee.value, '0.00', 'a new delivery form must start at 0.00');
        assert.equal(fee.readOnly, true, 'the service fee must remain readonly');

        openCustomerPicker();
        await waitFor(() => {
            const ids = Array.from(menu.querySelectorAll('[data-id]')).map(item => item.dataset.id);
            return menu.classList.contains('show') && ids.join(',') === '33,11,22';
        }, 'unique customers ordered by latest SQLite sales');

        const orderedIds = Array.from(menu.querySelectorAll('[data-id]')).map(item => item.dataset.id);
        assert.deepEqual(orderedIds, ['33', '11', '22'],
            'recent-sale customers must lead while customers without sales stay available');
        assert.equal(orderedIds.filter(id => id === '11').length, 1,
            'duplicate customer rows must be removed before rendering');

        party.value = 'عميل بلا بيع سابق';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        assert.deepEqual(
            Array.from(menu.querySelectorAll('[data-id]')).map(item => item.dataset.id),
            ['22'],
            'search must find a customer who has no recent sale');

        party.value = '';
        party.dispatchEvent(new window.Event('input', { bubbles: true }));
        menu.querySelector('[data-id="11"]').click();
        await waitFor(() => fee.value === '125.75', 'actual fee from customer 11 original sale');
        assert.equal(fee.value, '125.75',
            'selecting a customer with an existing sale must preserve the actual original sale fee');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker after first selection');
        menu.querySelector('[data-id="22"]').click();
        await waitFor(() => fee.value === '0.00' && document.getElementById('f_party_id').value === '22',
            'no-sale customer selection to clear the previous fee');
        assert.equal(fee.value, '0.00',
            'selecting a customer without a sale must not retain the previous customer fee');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker after no-sale customer');
        menu.querySelector('[data-id="33"]').click();
        await waitFor(() => fee.value === '88.50', 'fee from the next selected customer');
        assert.equal(fee.value, '88.50',
            'changing customers several times must load the newly selected sale fee');

        openCustomerPicker();
        await waitFor(() => menu.classList.contains('show'), 'customer picker before clearing customer data');
        menu.querySelector('[data-anonymous]').click();
        assert.equal(document.getElementById('f_party_id').value, '',
            'clearing customer data must clear the selected party id');
        assert.equal(fee.value, '0.00',
            'clearing customer data must clear the previously loaded fee');

        await window.openCreateModal();
        assert.equal(fee.value, '0.00', 'resetting the form must restore 0.00');
        fee.value = '';
        fee.dispatchEvent(new window.Event('blur'));
        assert.equal(fee.value, '0.00', 'an empty readonly fee value must not leave stale or NaN data');

        assert.ok(calls.customers >= 2, 'opening a fresh form must reload the customers from the native bridge');
        assert.ok(calls.recent >= 1, 'the customer picker must request the SQLite recent-sale ordering');
        assert.deepEqual(calls.latest, [11, 22, 33],
            'the latest-sale lookup must run only for the customer selected by the operator');

        testFeeNormalizer();
        console.log('Deliveries form customer-change and service-fee regression tests passed.');
    } finally {
        dom.window.close();
    }
}

module.exports = run;

if (require.main === module) {
    run().catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
}
