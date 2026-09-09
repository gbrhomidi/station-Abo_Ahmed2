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
    for ok, label in checks:
        print(f'{"PASS" if ok else "FAIL"} {name}: {label}')
        if not ok: failed += 1
if failed:
    raise SystemExit(f'{failed} screen smoke checks failed')
print(f'All screen smoke checks passed: {len(CASES)} screens.')
