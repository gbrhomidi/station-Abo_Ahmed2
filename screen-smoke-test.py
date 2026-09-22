from html.parser import HTMLParser
from pathlib import Path
import re

ROOT = Path(__file__).parent / 'app/src/main/assets/screens'
CASES = {
    'messages.html': ['messageList', 'statsDashboard', 'searchInput', 'getSmsMessagesPage', 'getSmsOperationalHealth', 'getSmsConversationTrace', 'getSmsWeeklyAnalytics'],
    'message-log.html': ['contentArea', 'statsContainer', 'tabsContainer', 'getSmsLogs'],
    'debt-reminders.html': ['cardsContainer', 'paymentForm', 'reminderForm', 'getCustomerDebts', 'addNotification', 'makePayment'],
    'whitelist.html': ['cardsContainer', 'whitelistForm', 'getWhitelist', 'addWhitelist', 'updateWhitelist', 'removeWhitelist'],
    'SmsCoreDiagnostics.html': ['smsList', 'rawData', 'getDatabaseInfo', 'getTableCounts', 'getRecentActivity'],
    'notification-templates.html': ['cardsContainer', 'templateForm', 'getNotificationTemplates', 'updateNotificationTemplate', 'deleteNotificationTemplate'],
    'notification-inbox.html': ['cardsContainer', 'getNotifications', 'markNotificationRead'],
    'users.html': ['getStations', 'getEmployees', 'searchUsers', 'addUser', 'updateUser', 'getLastSelectedFileUri', 'getImageDataUrl'],
    'shifts.html': ['getShiftFormContext', 'saveShiftRecordTyped', 'getOpenShiftForManagement', 'closeOpenShiftForManagement', 'closeOpenShiftBtn'],
    'orders.html': ['getProducts', 'getSalesTransactionRecords', 'getOpenShiftForManagement', 'saveSalesTransactionRecord', 'updateSalesTransactionRecord', 'generateSalesTransactionReport'],
    'deliveries.html': ['getDeliverySaleContext', 'getLatestCustomerSaleForDelivery', 'saveDeliveryRecord', 'updateDeliveryRecord', 'generateDeliveryManagementReport', 'getDeliveryReportOptions'],
}

class DOMAudit(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get('id'):
            self.ids.append(attrs['id'])

failed = 0
for name, required in CASES.items():
    path = ROOT / name
    if not path.exists():
        print(f'FAIL {name}: file missing'); failed += 1; continue
    html = path.read_text(encoding='utf-8', errors='replace')
    parser = DOMAudit(); parser.feed(html)
    checks = [
        (bool(re.search(r'<html[^>]*\bdir=["\']rtl["\']', html, re.I)), 'RTL'),
        ('theme.css' in html, 'shared theme CSS'),
        (not re.search(r'!party\s*&&\s*party\.', html), 'null-safe party access'),
        (len(parser.ids) == len(set(parser.ids)), 'unique DOM ids'),
    ] + [(token in html, token) for token in required]
    if name == 'users.html':
        checks += [
            ('<input id="roleId" type="hidden"' in html, 'hidden role field'),
            ('<select id="roleId"' not in html, 'no role dropdown'),
            ('syncEmployeeDerivedFields' in html, 'employee job_title derivation'),
            ('preferred_language:$(\'preferredLanguage\').value' in html, 'preferred language persistence'),
            ('branch_id' in html and 'company_id' in html, 'station branch/company mapping'),
            ('avatar_path:$(\'avatarPath\').value' in html, 'avatar_path persistence'),
            ('getImageDataUrl' in html, 'avatar re-read/display bridge'),
            ('must_change_password:(state.editingId===0?1:' in html, 'password-change flag persistence'),
            (not any(x in html for x in ('Math.random(', 'TODO', 'Mock Data', 'Fake Data')), 'no fake/mock user data'),
        ]
    if name == 'shifts.html':
        checks += [
            ('option.value = person.id ?? person.employee_id ?? "";' in html, 'cashier employee id'),
            ('لا يملك حساب مستخدم مرتبطاً' not in html, 'no employee-user cashier validation'),
            ('closeOpenShiftBtn' in html and 'openShiftOverlay' in html, 'open-shift close modal'),
            ('getOpenShiftForManagement' in html and 'closeOpenShiftForManagement' in html, 'SQLite open/close bridge contract'),
            ('SQLite' in html or 'sqlite' in html.lower(), 'SQLite-backed shift messaging'),
            (not any(x in html for x in ('Math.random(', 'TODO', 'Mock Data', 'Fake Data')), 'no fake/mock shift data'),
        ]

    if name == 'orders.html':
        checks += [
            ('getSalesTransactionFormContext' not in html, 'no stale form-context bridge contract'),
            ('getOpenShiftForManagement' in html and 'ensureOpenShift' in html, 'native open-shift gate'),
            ('getProducts' in html and 'getSalesTransactionRecords' in html, 'real product/order bridge contracts'),
            ('payment_method' in html and 'item_kind' in html, 'server-side tab query parameters'),
            ('appearance:none' in html and 'background-image:url' in html, 'single custom dropdown arrow'),
            ('hidden: true' in html and 'invoice_number' in html and 'receipt_number' in html, 'internal fields hidden from UI'),
            ('defaultValue: 1000' in html and 'ادخل رسوم الخدمة' in html, 'service fee default and hint'),
            ('getProductPriceHistory' in html, 'effective product price lookup'),
            ("setCalcValue('subtotal', subtotal)" not in html, 'subtotal calculation uses defined base value'),
            ("x.vehicle_id" in html and "vehicle.driver_id" in html, 'vehicle-driver relationship uses real FK data'),
            ("brand" in html and "model" in html and "plate_number" in html, 'vehicle display includes brand/model and plate'),
            ('window.print' in html and '@media print' in html, 'report print uses rendered report'),
            ('new Date().toISOString().slice(0, 10)' not in html, 'local-date boundary'),
            ("var readonlyAttr = def.auto ? 'readonly disabled' : (def.readonly ? 'disabled' : '');" in html, 'disabled state is field-definition driven'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock order data'),
        ]

    if name == 'deliveries.html':
        checks += [
            ('getLatestCustomerSaleForDelivery' in html and 'sale_id' in html, 'delivery is linked to an existing sale'),
            ('delete payload.sale_id' not in html, 'create payload preserves original sale reference'),
            ('idempotency_key' in html, 'delivery create idempotency key'),
            ('generateDeliveryManagementReport' in html and 'getDeliveryReportOptions' in html, 'real delivery report bridge contracts'),
            ('reportType' in html and 'reportDetailed' in html and 'reportCustomer' in html and 'reportSite' in html, 'dynamic report filters'),
            ('f_delivery_service_fee' in html and 'delivery_service_fee' in html, 'delivery service fee field'),
            ('vehiclePreview' in html and 'vehicle_photo' in html, 'vehicle image preview'),
            ('عميل غير مسجل' in html and 'party_id' in html, 'anonymous customer option without fake id'),
            ("f_sale_id').style.display = 'block'" in html and 'sale_id' in html, 'anonymous customer requires original sale id'),
            ('window.print' in html or 'printCurrentPage' in html, 'delivery report print contract'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock delivery data'),
        ]

    for ok, label in checks:
        print(f'{"PASS" if ok else "FAIL"} {name}: {label}')
        if not ok: failed += 1
if failed:
    raise SystemExit(f'{failed} screen smoke checks failed')
print(f'All screen smoke checks passed: {len(CASES)} screens.')
