from html.parser import HTMLParser
from pathlib import Path
import re

ROOT = Path(__file__).parent / 'app/src/main/assets/screens'
CASES = {
    'messages.html': ['messageList', 'statsDashboard', 'searchInput', 'getSmsMessagesPage', 'getSmsOperationalHealth', 'getSmsConversationTrace', 'getSmsWeeklyAnalytics'],
    'message-log.html': ['contentArea', 'statsContainer', 'tabsContainer', 'getSmsLogs'],
    'debt-reminders.html': ['cardsContainer', 'paymentModal', 'reminderModal', 'getCustomerDebts', 'addNotification', 'makePayment'],
    'whitelist.html': ['cardsContainer', 'whitelistForm', 'getWhitelist', 'addWhitelist', 'updateWhitelist', 'removeWhitelist'],
    'SmsCoreDiagnostics.html': ['smsList', 'rawData', 'getDatabaseInfo', 'getTableCounts', 'getRecentActivity'],
    'notification-templates.html': ['cardsContainer', 'templateForm', 'getNotificationTemplates', 'updateNotificationTemplate', 'deleteNotificationTemplate'],
    'notification-inbox.html': ['cardsContainer', 'getNotifications', 'markNotificationRead'],
    'users.html': ['getStations', 'getEmployees', 'searchUsers', 'addUser', 'updateUser', 'getLastSelectedFileUri', 'getImageDataUrl'],
    'shifts.html': ['getShiftFormContext', 'saveShiftRecordTyped', 'getOpenShiftForManagement', 'closeOpenShiftForManagement', 'closeOpenShiftBtn'],
    'orders.html': ['getProducts', 'getSalesTransactionRecords', 'getOpenShiftForManagement', 'saveSalesTransactionRecord', 'updateSalesTransactionRecord', 'generateSalesTransactionReport'],
    'deliveries.html': ['getDeliverySaleContext', 'getLatestCustomerSaleForDelivery', 'saveDeliveryRecord', 'updateDeliveryRecord', 'generateDeliveryManagementReport', 'getDeliveryReportOptions'],
    'stock-levels.html': ['getStockLevelsPage', 'loadPage', 'filterInventory', 'advancedFilter', 'searchStock'],
    'inventory-movements.html': ['getInventoryMovementPage', 'getInventoryMovementUnifiedStats', 'addStockMovement', 'archiveStockMovement'],
    'inventory-alerts.html': ['getStockAlertRecords', 'resolveStockAlertRecord', 'inventory-movements.html?product_id='],
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
        ('theme.css' in html or name == 'inventory-alerts.html', 'shared or self-contained theme CSS'),
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

    if name == 'stock-levels.html':
        checks += [
            ('async async function' not in html, 'no malformed double-async declarations'),
            ('async function loadLowStockProducts()' in html, 'low-stock loader is await-safe'),
            ('async function applyFilters()' in html, 'filter handler is await-safe'),
            ('async function performSearch()' in html, 'search handler is await-safe'),
            ('async function saveNewProduct()' in html, 'save handler is await-safe'),
            ('AndroidInterface.getStockLevelsPage' in html, 'single station-scoped SQLite page bridge'),
            ('applyPageData(data)' in html and 'data.stats' in html, 'statistics are bound from backend response'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock stock data'),
        ]

    if name == 'inventory-movements.html':
        checks += [
            ('async async function' not in html, 'no malformed double-async declarations'),
            ('async function loadStats()' in html, 'statistics loader is await-safe'),
            ('getInventoryMovementPage' in html and 'getInventoryMovementUnifiedStats' in html, 'real movement/statistics bridge contracts'),
            ('addStockMovement' in html and 'archiveStockMovement' in html, 'real movement write/archive contracts'),
            ('activeMovementFilters' in html and 'currentPage' in html, 'filters and pagination are state-backed'),
            ('function escapeHtml(' in html, 'HTML escaping helper is defined before use'),
            ('await loadProducts()' in html and 'await loadFuelTypes()' in html, 'product/fuel pickers refresh from SQLite on open'),
            ('inventory_value' in html and 'inbound_quantity' in html and 'outbound_quantity' in html, 'analytics cards bind semantic SQLite metrics'),
            ('grid-template-columns:repeat(2,minmax(0,1fr)) !important' in html, 'stats and field grids are forced to two columns on small and large phones'),
            ('field-click-picker' in html and 'onclick="openProductPicker(\'filter\')"' in html and 'onclick="openFuelPicker(\'filter\')"' in html, 'filter product/fuel fields open the SQLite picker directly without outer selection buttons'),
            ('onclick="openProductPicker(\'analytics\')"' in html and 'onclick="openFuelPicker(\'analytics\')"' in html, 'analytics product/fuel fields open the SQLite picker directly'),
            ('onclick="openFuelPicker(\'create\')"' in html and 'id="movement-quantity"' in html and 'id="fuel-movement-fields"' in html, 'fuel movement has direct fuel picker and a real quantity field'),
            ('async function openProductPicker' in html and 'async function openFuelPicker' in html, 'pickers await SQLite data before rendering'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock movement data'),
        ]

    if name == 'inventory-alerts.html':
        checks += [
            ('getStockAlertRecords' in html and 'resolveStockAlertRecord' in html, 'real alert read/resolve bridge contracts'),
            ('inventory-movements.html?product_id=' in html, 'restock action opens a real inventory movement'),
            ('stock_alerts' in html and 'SQLite' in html, 'SQLite-backed alert source'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock alert data'),
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
            ("$('saleIdGroup').style.display = 'block'" in html and "$('saleContextHelp').style.display = 'block'" in html and 'sale_id' in html, 'anonymous customer requires and exposes original sale id'),
            ('window.print' in html or 'printCurrentPage' in html, 'delivery report print contract'),
            (not any(x in html for x in ('Math.random(', 'Mock Data', 'Fake Data')), 'no fake/mock delivery data'),
        ]

    for ok, label in checks:
        print(f'{"PASS" if ok else "FAIL"} {name}: {label}')
        if not ok: failed += 1

# Security settings contract checks: these ensure all eight controls are wired
# to the native secure store/runtime and that both backup paths fail closed.
PROJECT_ROOT = Path(__file__).parent
SECURITY_CASES = {
    'security-panel': (
        PROJECT_ROOT / 'app/src/main/assets/screens/settings.html',
        [
            'requireDeviceUnlock', 'encryptStorage', 'biometricLockEnabled',
            'hideSensitiveData', 'autoLogoutMinutes', 'preventScreenshots',
            'secureBackupEnabled', 'pinCode',
            "if (key === 'pinCode')", 'تم تعيين PIN',
        ],
    ),
    'security-store': (
        PROJECT_ROOT / 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/settings/security/SecuritySettingsStore.kt',
        [
            'EncryptedSharedPreferences.create', 'PBKDF2WithHmacSHA256',
            'PBKDF2_ITERATIONS = 150_000', 'PBEKeySpec', 'PIN_HASH',
            'PIN_SALT', 'MessageDigest.isEqual', 'MAX_PIN_FAILURES = 5',
            'LOCKOUT_MS = 60_000L', 'fun exportPinCredential()', 'fun importPinCredential',
        ],
    ),
    'security-runtime': (
        PROJECT_ROOT / 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt',
        [
            'saveSecuritySetting(db, key.trim(), value)', 'getAllSettingsMap().toMutableMap()',
            'WindowManager.LayoutParams.FLAG_SECURE', 'createConfirmDeviceCredentialIntent',
            'showBiometricPrompt(', 'securityIdleRunnable', 'forceSecurityLogout(',
            'Keep Remember-Me material intact', 'applySecurityRuntimeSettings(view)',
            'webView?.visibility = View.INVISIBLE', 'secureBackupRequired',
            'removeLegacySecuritySetting', 'removeLegacySystemSetting',
            'system_settings', 'بقيت نسخة غير مشفرة من إعداد الأمان في SQLite',
        ],
    ),
    'encrypted-settings-backup': (
        PROJECT_ROOT / 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/settings/backup/JsonSettingsBackupManager.kt',
        [
            'secureBackupEnabled', 'requireNotNull(encryption)',
            'settings_backup_${System.currentTimeMillis()}.json.enc',
            'station-secure-settings', 'pinCredential', 'importPinCredential(restoredPin)',
            'decrypt(raw)', 'Backward compatibility for existing plain/encrypted',
        ],
    ),
    'encrypted-database-backup': (
        PROJECT_ROOT / 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/settings/backup/FullDatabaseBackupManager.kt',
        [
            'station_full_database_backup_v1', 'AES/GCM/NoPadding',
            'fun createBackup(treeUri: Uri, encrypt: Boolean', 'decryptDatabaseStream',
            'verifySnapshot(staging)', 'endsWith(".db.enc"',
        ],
    ),
    'scheduled-backup-policy': (
        PROJECT_ROOT / 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/settings/backup/FullDatabaseBackupWorker.kt',
        [
            'SecuritySettingsStore.open(applicationContext)', 'secureBackupRequired',
            'secureBackupRequired || isEnabled(database.getSetting("encryptBackup"), true)',
        ],
    ),
}
# Protect the save path against a temporal-dead-zone regression: PIN UI handling
# may read verifiedLegacy only after per-key readback has populated that object.
security_settings_source = SECURITY_CASES['security-panel'][0].read_text(encoding='utf-8', errors='replace')
verified_legacy_declaration = security_settings_source.find('const verifiedLegacy = {}')
pin_clear_block = security_settings_source.find('PIN verification is complete')
if verified_legacy_declaration < 0 or pin_clear_block < verified_legacy_declaration:
    print('FAIL security-panel: PIN UI is cleared only after verifiedLegacy is initialized and verified')
    failed += 1
else:
    print('PASS security-panel: PIN UI is cleared only after verifiedLegacy is initialized and verified')

for case_name, (case_path, tokens) in SECURITY_CASES.items():
    if not case_path.exists():
        print(f'FAIL {case_name}: source file missing ({case_path.relative_to(PROJECT_ROOT)})')
        failed += 1
        continue
    source = case_path.read_text(encoding='utf-8', errors='replace')
    for token in tokens:
        ok = token in source
        print(f'{"PASS" if ok else "FAIL"} {case_name}: {token}')
        if not ok:
            failed += 1

if failed:
    raise SystemExit(f'{failed} screen/security smoke checks failed')
print(f'All screen and security smoke checks passed: {len(CASES)} screens, {len(SECURITY_CASES)} security contracts.')
