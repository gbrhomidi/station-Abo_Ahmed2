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
    'notification-templates.html': ['cardsContainer', 'templateForm', 'getNotificationTemplates', 'addNotificationTemplate', 'updateNotificationTemplate', 'deleteNotificationTemplate'],
    'notification-inbox.html': ['cardsContainer', 'getNotifications', 'markNotificationRead'],
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
    for ok, label in checks:
        print(f'{"PASS" if ok else "FAIL"} {name}: {label}')
        if not ok: failed += 1
if failed:
    raise SystemExit(f'{failed} screen smoke checks failed')
print(f'All screen smoke checks passed: {len(CASES)} screens.')
