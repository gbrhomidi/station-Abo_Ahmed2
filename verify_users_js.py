#!/usr/bin/env python3
"""Verify the users-screen updateUser contract.

This is a source/bridge contract check. The corresponding SQLite mutation,
row isolation, employee-link preservation, missing-id result, and invalid-id
rejection are executed by the DatabaseHelper and MainActivity Robolectric tests.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
HTML_PATH = ROOT / "app/src/main/assets/screens/users.html"
MAIN_PATH = ROOT / "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt"
HELPER_PATH = ROOT / "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt"
RUNTIME_TEST_PATH = ROOT / "app/src/test/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelperUserUpdateContractTest.kt"
BRIDGE_TEST_PATH = ROOT / "app/src/test/java/com/aistudio/dieselstationsms/kxmpzq/MainActivityUserUpdateBridgeTest.kt"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL updateUser contract: {message}")


html = HTML_PATH.read_text(encoding="utf-8")
main = MAIN_PATH.read_text(encoding="utf-8")
helper = HELPER_PATH.read_text(encoding="utf-8")
runtime_test = RUNTIME_TEST_PATH.read_text(encoding="utf-8")
if not BRIDGE_TEST_PATH.is_file():
    fail("real MainActivity WebAppInterface Robolectric test is missing")
bridge_test = BRIDGE_TEST_PATH.read_text(encoding="utf-8")

scripts = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", html, flags=re.S | re.I)
if not scripts:
    fail("no inline scripts found")
for index, script in enumerate(scripts, 1):
    target = Path(f"/tmp/users-inline-{index}.js")
    target.write_text(script, encoding="utf-8")
    result = subprocess.run(["node", "--check", str(target)], capture_output=True, text=True)
    if result.returncode:
        print(result.stderr, end="")
        fail(f"inline script {index} is not valid JavaScript")
print(f"PASS JavaScript syntax: {len(scripts)} inline script block(s)")

# JavaScript contract: the selected/editing id is carried in the payload and
# the native call receives [id, JSON.stringify(data)] in that exact order.
if not re.search(r"return\s*\{[^}]*\bid\s*:\s*state\.editingId", html, re.S):
    fail("collectUser does not carry state.editingId")
if not re.search(r"id\?await\s+call\(\s*['\"]updateUser['\"]\s*,\s*\[id\s*,\s*JSON\.stringify\(data\)\]\s*\)", html):
    fail("saveUser does not call updateUser as [id, JSON.stringify(data)]")
if not re.search(r"state\.editingId\s*=\s*Number\(id\)", html):
    fail("editUser does not bind the selected row id")
if not re.search(r"\$\(['\"]editUserId['\"]\)\.value\s*=\s*id", html):
    fail("editUser does not preserve the selected id in the edit form")
print("PASS JavaScript updateUser contract: selected id and argument order")

# Android bridge contract: Long id first, JSON payload second, then the same
# id is passed to DatabaseHelper and the real affected-row count controls the
# returned success value.
if not re.search(r"fun\s+updateUser\(id:\s*Long,\s*jsonData:\s*String\):\s*String", main):
    fail("Android bridge signature is not updateUser(Long, String)")
if not re.search(r"JSONObject\(jsonData\).*?db\.prepareUserManagementPayload\(data\).*?db\.updateUser\(id,\s*normalized\)", main, re.S):
    fail("Android bridge does not parse, normalize, and forward the same id")
if "successResponse(rows > 0" not in main or "لم يتم العثور على السجل" not in main:
    fail("Android bridge does not expose the actual affected-row result")
print("PASS Android Bridge contract: Long id, JSON payload, affected-row response")

# SQLite contract: update is constrained to the requested non-deleted row and
# returns SQLite's affected-row count; no broad update is accepted here.
if not re.search(r"db\.update\(\s*\"users\"\s*,\s*cv\s*,\s*\"id=\? AND is_deleted = 0\"", helper):
    fail("SQLite update is not constrained by id and is_deleted")
if not re.search(r"val\s+rows\s*=\s*db\.update\(.*?\n\s*if\s*\(rows\s*>\s*0\)", helper, re.S):
    fail("DatabaseHelper does not use the actual affected-row count")
print("PASS SQLite contract: target-row predicate and affected-row count")

# Runtime evidence is deliberately required by this preflight. The Python
# script itself does not claim that static checks are SQLite execution.
for marker in (
    "updateUserUsesTheExplicitIdAndChangesOnlyTheTargetRow",
    "updateUserReturnsZeroForMissingIdWithoutChangingExistingRows",
    "updateUserRejectsNonPositiveIdsBeforeWriting",
    "assertEquals(\"exactly one SQLite row must be updated\", 1, affectedRows)",
    "assertEquals(\"a missing user must produce zero affected rows\", 0, affectedRows)",
    "updateUserPreservesExistingEmployeeAssociationWhenPayloadOmitsEmployeeId",
    "assertEquals(employeeId, userEmployeeId(targetUserId))",
):
    if marker not in runtime_test:
        fail(f"runtime SQLite contract test is missing: {marker}")
for marker in (
    "updateUserInvokesTheRealBridgeAndMutatesOnlyTheExplicitTargetRow",
    "updateUserBridgeReturnsNotFoundWithoutChangingRows",
    "updateUserBridgeRejectsZeroNegativeIdsAndMalformedJsonWithoutPartialWrites",
    "bridge.updateUser(targetUserId, payload.toString())",
    "The bridge normalizer requires the employee link; submit the existing ID and verify it remains bound.",
    '.put("password_hash", "attacker-controlled-password-hash")',
    'assertEquals("synthetic-bridge-password-hash", userString(targetUserId, "password_hash"))',
    "Unknown employee_id must be rejected by SQLite normalization",
    'assertEquals(employeeId, userLong(targetUserId, "employee_id"))',
):
    if marker not in bridge_test:
        fail(f"real Android Bridge Robolectric coverage is missing: {marker}")
print("PASS runtime test declarations: DatabaseHelper SQLite contract + real MainActivity.WebAppInterface calls")
print("RUNTIME: run both Robolectric test classes in :app:testDebugUnitTest; this Python verifier remains static-only")
