from pathlib import Path
import re
import subprocess

html = Path('app/src/main/assets/screens/users.html').read_text(encoding='utf-8')
scripts = re.findall(r'<script(?:\s[^>]*)?>(.*?)</script>', html, flags=re.S | re.I)
if not scripts:
    raise SystemExit('No inline scripts found')
for index, script in enumerate(scripts, 1):
    target = Path(f'/tmp/users-inline-{index}.js')
    target.write_text(script, encoding='utf-8')
    result = subprocess.run(['node', '--check', str(target)], capture_output=True, text=True)
    if result.returncode:
        print(result.stderr)
        raise SystemExit(result.returncode)
print(f'PASS: checked {len(scripts)} inline script blocks')

source = html
if not ('...(id > 0 ? { id } : {})' in source or 'id: id > 0 ? id : undefined' in source):
    raise SystemExit('Missing expected update-user id fix')
for marker in ('z-index: 4000', '#userModal .form-input'):
    if marker not in source:
        raise SystemExit(f'Missing expected fix: {marker}')
print('PASS: expected users-screen fixes are present')

kotlin = Path('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt').read_text(encoding='utf-8')
if 'authenticatedBranchId' not in kotlin:
    raise SystemExit('Missing authenticated branch scope fix')
print('PASS: authenticated branch scope fix is present')

helper = Path('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt').read_text(encoding='utf-8')
if 'LEFT JOIN stations s ON u.station_id = s.id' not in helper:
    raise SystemExit('Missing station display join')
print('PASS: station display join is present')
