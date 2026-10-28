#!/usr/bin/env python3
"""Preflight the 200 custody/workshop acceptance scenarios against repository contracts.

The JSON/Markdown files in all-test are scenario descriptions, not executable tests:
they do not contain deterministic fixtures, bridge calls, or machine-readable expected
ledger deltas. This runner checks the catalogue and production source contracts. Missing
contracts are reported as BLOCKED; it never mutates SQLite or claims a workflow passed.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "all-test" / "اختبارات_عهد_النزول_وورش_الصيانة.json"
MAIN = ROOT / "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt"
DB = ROOT / "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt"

EXPECTED_TYPES = {
    "مسار سليم",
    "حدود ومدخلات غير صحيحة",
    "تكرار وتزامن",
    "صلاحيات وحالات مقفلة",
    "ترابط ومطابقة وتدقيق",
}

def contains_any(source: str, patterns: list[str]) -> bool:
    return any(re.search(pattern, source, re.IGNORECASE | re.MULTILINE) for pattern in patterns)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--strict", action="store_true",
                        help="exit nonzero if acceptance tests cannot yet be executed")
    args = parser.parse_args()

    for path in (SPEC, MAIN, DB):
        if not path.exists():
            print(f"FAIL preflight: required file missing: {path.relative_to(ROOT)}")
            return 2

    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    cases = spec.get("cases", [])
    if spec.get("count") != 200 or len(cases) != 200:
        print(f"FAIL preflight: expected 200 cases; declared={spec.get('count')}, actual={len(cases)}")
        return 2

    ids = [case.get("id", "") for case in cases]
    if len(set(ids)) != len(ids) or any(not case_id for case_id in ids):
        print("FAIL preflight: missing or duplicate test identifiers")
        return 2
    type_counts = Counter(case.get("نوع_الفحص", "") for case in cases)
    if set(type_counts) != EXPECTED_TYPES or any(type_counts[t] != 40 for t in EXPECTED_TYPES):
        print(f"FAIL preflight: unexpected test-type distribution: {dict(type_counts)}")
        return 2
    scenario_counts = Counter((case.get("المحور", ""), case.get("السيناريو", "")) for case in cases)
    if len(scenario_counts) != 40 or any(count != 5 for count in scenario_counts.values()):
        print(f"FAIL preflight: expected 40 scenarios × 5 verification dimensions; found {len(scenario_counts)} scenarios")
        return 2

    main_source = MAIN.read_text(encoding="utf-8", errors="replace")
    db_source = DB.read_text(encoding="utf-8", errors="replace")

    custody_checks = {
        "dedicated SQLite custody tables": contains_any(db_source, [
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:operating_custodies|cash_custodies|custody_vouchers|employee_custodies|petty_cash_custodies)\b",
        ]),
        "custody-specific Android bridge": contains_any(main_source, [
            r"fun\s+(?:get|create|issue|disburse|approve|settle|return|reverse|reconcile)[A-Za-z0-9_]*Custody[A-Za-z0-9_]*\s*\(",
        ]),
        "custody settlement / immutable audit ledger": contains_any(db_source, [
            r"(?:custody_movements|custody_settlements|custody_audit_events|operating_custody_entries)",
        ]),
        "custody idempotency / duplicate protection": contains_any(db_source, [
            r"(?:custody|imprest)[^\n]{0,100}idempotency_key|idempotency_key[^\n]{0,100}(?:custody|imprest)",
        ]),
    }
    workshop_checks = {
        "dedicated station work-order table": contains_any(db_source, [
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:station_work_orders|work_orders|workshop_work_orders)\b",
        ]),
        "work-order Android bridge": contains_any(main_source, [
            r"fun\s+(?:get|create|add|update|approve|close|cancel|reverse|bill|invoice)[A-Za-z0-9_]*WorkOrder[A-Za-z0-9_]*\s*\(",
        ]),
        "work-order parts issue/return ledger": contains_any(db_source, [
            r"(?:work_order_parts|workshop_parts_movements|work_order_material_issues)",
        ]),
        "technician labor and time costing": contains_any(db_source, [
            r"(?:work_order_labor|workshop_labor_entries|technician_time_entries)",
        ]),
        "workshop invoice / billing posting": contains_any(db_source + "\n" + main_source, [
            r"(?:workshop_invoices|work_order_invoices|invoiceWorkshopOrder|billWorkshopOrder)",
        ]),
        "workshop cost / profitability reporting": contains_any(db_source + "\n" + main_source, [
            r"(?:getWorkshopProfitability|work_order_cost_report|workshop_cost_summary|reportWorkshopProfit)",
        ]),
    }

    # These cases have no structured fixture/payload/expected-result assertions.
    # A source preflight cannot substitute for SQLite transaction and ledger testing.
    has_fixtures = all(
        any(key in case for key in ("fixture", "payload", "given", "expected", "expected_result", "assertions"))
        for case in cases
    )
    results = []
    for case in cases:
        is_custody = "عهد" in case.get("المحور", "")
        domain = "custody" if is_custody else "workshop"
        checks = custody_checks if is_custody else workshop_checks
        missing = [name for name, present in checks.items() if not present]
        results.append({
            "id": case["id"],
            "domain": domain,
            "scenario": case.get("السيناريو", ""),
            "verification_type": case.get("نوع_الفحص", ""),
            "status": "BLOCKED",
            "source_preflight": "FAIL" if missing else "PARTIAL",
            "missing_backend_contracts": missing,
            "reason": "Dedicated backend contracts and deterministic fixtures are absent; functional SQLite workflow was not executed.",
        })

    custody_blocked = sum(row["domain"] == "custody" for row in results)
    workshop_blocked = len(results) - custody_blocked
    report = {
        "report_version": 1,
        "test_suite": spec.get("title", ""),
        "catalog_validation": {
            "status": "PASS",
            "case_count": len(cases),
            "unique_ids": len(set(ids)),
            "scenario_count": len(scenario_counts),
            "verification_types": dict(type_counts),
            "has_machine_readable_fixtures_and_assertions": has_fixtures,
        },
        "runtime_execution": {
            "executed": 0,
            "passed": 0,
            "failed": 0,
            "blocked": len(results),
            "reason": "Natural-language scenarios are not runnable fixtures, and the dedicated custody/workshop schemas and bridge contracts are missing.",
        },
        "backend_preflight": {
            "custody": custody_checks,
            "workshop": workshop_checks,
            "existing_maintenance_requests_are_not_treated_as_full_work_orders": True,
        },
        "additional_findings": {
            "legacy_maintenance_delete_is_station_scoped": contains_any(main_source, [
                r'id=\? AND station_id=\? AND is_deleted=0',
            ]),
            "legacy_maintenance_delete_has_explicit_granular_permission_check": contains_any(main_source, [
                r'checkPermission\("maintenance",\s*"delete"\)',
            ]),
            "legacy_maintenance_delete_permission_note": "No maintenance.delete permission seed was found in DatabaseHelper; this remains an access-control follow-up rather than installing a guard that would deny every user.",
        },
        "results": results,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"PASS test catalogue: {len(cases)} unique cases, {len(scenario_counts)} scenarios, five verification dimensions × 40 cases.")
    print(f"BLOCKED functional execution: {len(results)} cases ({custody_blocked} custody, {workshop_blocked} workshop); executed=0, passed=0.")
    print("Custody source preflight: " + "; ".join(f"{k}={'PRESENT' if v else 'MISSING'}" for k, v in custody_checks.items()))
    print("Workshop source preflight: " + "; ".join(f"{k}={'PRESENT' if v else 'MISSING'}" for k, v in workshop_checks.items()))
    print("IMPORTANT: this is a source-backed preflight, not a claim that the 200 business workflows have been functionally tested.")
    if args.report:
        print(f"Preflight report written to: {args.report}")
    if args.strict and any(row["source_preflight"] == "FAIL" for row in results):
        return 3
    return 0

if __name__ == "__main__":
    sys.exit(main())
