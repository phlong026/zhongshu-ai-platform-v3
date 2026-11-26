"""Offline P3 checks use synthetic records and never connect to a database."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

DIRECTORY = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("ownership", DIRECTORY / "ownership.py")
ownership = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ownership)


class OwnershipTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((DIRECTORY / "fixtures" / "synthetic.json").read_text())

    def rejected(self, evidence, code):
        with self.assertRaisesRegex(ownership.EvidenceError, code):
            ownership.validate_fixture(evidence)

    def test_a_reserved_24_is_preserved_and_does_not_count_as_b(self):
        before = copy.deepcopy(self.fixture)
        ownership.validate_fixture(self.fixture)
        self.assertEqual(self.fixture, before)
        self.assertEqual(self.fixture["balances"][0]["reserved"], 24)

    def test_b_available_or_reserved_is_not_zero_path(self):
        for field in ("available", "reserved"):
            with self.subTest(field=field):
                evidence = copy.deepcopy(self.fixture)
                evidence["balances"][1][field] = 1
                self.rejected(evidence, "NONZERO_B_FUNDS")

    def test_missing_b_balance_is_not_evidence_of_zero(self):
        self.fixture["balances"].pop()
        self.rejected(self.fixture, "MISSING_B_BALANCE")

    def test_unassigned_wallet_is_blocked(self):
        self.fixture["balances"].append({"account_id": "999", "available": 0, "reserved": 0})
        self.rejected(self.fixture, "UNKNOWN_ACCOUNT_OWNER")

    def test_duplicate_owner_is_blocked(self):
        self.fixture["accounts"].append(copy.deepcopy(self.fixture["accounts"][1]))
        self.rejected(self.fixture, "DUPLICATE_ACCOUNT")

    def test_b_inflight_blocks_zero_path_even_with_zero_balance(self):
        for kind in ("ai", "payment", "refund", "inbox", "usage", "entitlement"):
            with self.subTest(kind=kind):
                evidence = copy.deepcopy(self.fixture)
                evidence["obligations"].append({"id": "72", "account_id": "2001", "kind": kind,
                                                "executor": "B_OLD", "watermark": "fixture-only"})
                self.rejected(evidence, "B_OBLIGATION_REQUIRES_REVIEW")

    def test_obligation_requires_executor_and_watermark(self):
        for field in ("executor", "watermark"):
            with self.subTest(field=field):
                evidence = copy.deepcopy(self.fixture)
                evidence["obligations"][0].pop(field)
                self.rejected(evidence, "OBLIGATION_OWNER_WATERMARK")

    def test_binding_event_cannot_leak_another_org_or_org_zero(self):
        for org_id in ("0", "999"):
            with self.subTest(org_id=org_id):
                evidence = copy.deepcopy(self.fixture)
                evidence["binding_events"][0]["org_id"] = org_id
                self.rejected(evidence, "BINDING_ORG_OUTSIDE_SCOPE")

    def test_missing_previous_binding_is_blocked(self):
        self.fixture["binding_events"][0]["previous_binding_id"] = "404"
        self.rejected(self.fixture, "BINDING_REFERENCE_MISSING")

    def test_asset_reference_checks_org_and_state(self):
        for field, value, code in (("org_id", "999", "ASSET_OWNER_MISMATCH"),
                                   ("status", "REJECTED", "ASSET_NOT_ACCEPTED")):
            with self.subTest(field=field):
                evidence = copy.deepcopy(self.fixture)
                evidence["assets"][1][field] = value
                self.rejected(evidence, code)

    def test_unknown_asset_owner_is_not_inferred(self):
        self.fixture["assets"][0]["app"] = "UNKNOWN"
        self.rejected(self.fixture, "UNKNOWN_ASSET_OWNER")

    def test_duplicate_wallet_and_non_integer_money_are_rejected(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["balances"].append(copy.deepcopy(evidence["balances"][1]))
        self.rejected(evidence, "DUPLICATE_BALANCE")
        for invalid in (False, 0.0, "0", -1):
            with self.subTest(invalid=invalid):
                evidence = copy.deepcopy(self.fixture)
                evidence["balances"][1]["available"] = invalid
                self.rejected(evidence, "BALANCE_INVALID")

    def test_missing_avatar_asset_blocks_closure(self):
        self.fixture["references"][0]["asset_id"] = "404"
        self.rejected(self.fixture, "ASSET_REFERENCE_MISSING")

    def test_sequence_uncalled_value_and_increment_are_checked(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["sequences"][0].update(last_value=10, is_called=False, max_id=10)
        self.rejected(evidence, "SEQUENCE_COLLISION")
        evidence["sequences"][0].update(last_value=10, is_called=True, increment=7, max_id=16)
        ownership.validate_fixture(evidence)
        evidence["sequences"][0]["max_id"] = 17
        self.rejected(evidence, "SEQUENCE_COLLISION")

    def test_non_synthetic_input_is_refused(self):
        self.fixture["synthetic"] = False
        self.rejected(self.fixture, "SYNTHETIC_ONLY")

    def test_default_plan_has_no_execute_and_makes_no_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run([sys.executable, "-I", str(DIRECTORY / "ownership.py")],
                                    cwd=temporary, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            plan = json.loads(result.stdout)
            self.assertEqual(plan["status"], "STATIC_INVENTORY_ONLY")
            for field in ("freeze_ready", "database_connected", "migration_ready", "production_verified"):
                self.assertIs(plan[field], False)
            self.assertEqual(list(Path(temporary).iterdir()), [])
            rejected = subprocess.run([sys.executable, "-I", str(DIRECTORY / "ownership.py"), "--execute"],
                                      cwd=temporary, capture_output=True, timeout=10)
            self.assertNotEqual(rejected.returncode, 0)

    def test_source_hash_change_and_new_migration_are_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = "platform/platform-org/src/main/resources/db/migration/pf/V1__fixture.sql"
            sql = root / relative
            sql.parent.mkdir(parents=True)
            sql.write_text("CREATE TABLE example (id BIGINT);\n")
            manifest = {"format": ownership.MANIFEST_FORMAT,
                        "locations": list(ownership.LOCATIONS),
                        "migrations": [{"path": relative, "location": "pf", "version": "1",
                                        "source_sha256": ownership.digest(sql)}],
                        "tables": [{"name": "example", "owner": "B", "strategy": "filtered-copy",
                                    "source_migrations": [relative], "selector": "fixture"}],
                        "sequences": []}
            ownership.validate_manifest(manifest, root)
            missing_table = copy.deepcopy(manifest)
            missing_table["tables"][0]["name"] = "different_table"
            with self.assertRaisesRegex(ownership.EvidenceError, "TABLE_COVERAGE_CHANGED"):
                ownership.validate_manifest(missing_table, root)
            sql.write_text("CREATE TABLE example (id BIGINT, secret TEXT);\n")
            with self.assertRaisesRegex(ownership.EvidenceError, "MIGRATION_HASH_CHANGED"):
                ownership.validate_manifest(manifest, root)
            manifest["migrations"][0]["source_sha256"] = ownership.digest(sql)
            sql.with_name("V2__unreviewed.sql").write_text("SELECT 1;\n")
            with self.assertRaisesRegex(ownership.EvidenceError, "MIGRATION_SET_CHANGED"):
                ownership.validate_manifest(manifest, root)

    def test_operations_migrations_ship_with_b_and_legacy_server_migrations_do_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suffix = "src/main/resources/db/migration/pf/V1__operations.sql"
            shipped = root / "yudao-platform-operations" / suffix
            legacy = root / "yudao-server" / suffix
            for sql in (shipped, legacy):
                sql.parent.mkdir(parents=True)
                sql.write_text("CREATE TABLE operations_job (id BIGINT);\n")
            self.assertEqual(ownership.migration_paths(root), [shipped])
            with self.assertRaisesRegex(ownership.EvidenceError, "MIGRATION_PATH_INVALID"):
                ownership.valid_migration_path(legacy.relative_to(root).as_posix(), "pf")
            relative = shipped.relative_to(root).as_posix()
            manifest = {"format": ownership.MANIFEST_FORMAT, "locations": list(ownership.LOCATIONS),
                        "migrations": [{"path": relative, "location": "pf", "version": "1",
                                        "source_sha256": ownership.digest(shipped)}],
                        "tables": [{"name": "operations_job", "owner": "B", "strategy": "filtered-copy",
                                    "source_migrations": [relative], "selector": "fixture"}],
                        "sequences": []}
            ownership.validate_manifest(manifest, root)
            shipped.with_name("V2__later.sql").write_text("SELECT 1;\n")
            with self.assertRaisesRegex(ownership.EvidenceError, "MIGRATION_SET_CHANGED"):
                ownership.validate_manifest(manifest, root)

    def test_unlisted_identity_sequence_is_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = "yudao-module-infra/src/main/resources/db/migration/platform/V1__fixture.sql"
            sql = root / relative
            sql.parent.mkdir(parents=True)
            sql.write_text("CREATE TABLE example (id BIGINT GENERATED BY DEFAULT AS IDENTITY);\n")
            manifest = {"format": ownership.MANIFEST_FORMAT, "locations": list(ownership.LOCATIONS),
                        "migrations": [{"path": relative, "location": "platform", "version": "1",
                                        "source_sha256": ownership.digest(sql)}],
                        "tables": [{"name": "example", "owner": "SHARED", "strategy": "subset-review",
                                    "source_migrations": [relative], "selector": "fixture"}],
                        "sequences": []}
            with self.assertRaisesRegex(ownership.EvidenceError, "SEQUENCE_COVERAGE_CHANGED"):
                ownership.validate_manifest(manifest, root)
            manifest["sequences"].append({"name": "example_id_seq", "source_migration": relative,
                                           "catalog_verified": False})
            ownership.validate_manifest(manifest, root)

    def test_writer_inventory_matches_gate_without_claiming_live_coverage(self):
        coverage = ownership.read_json(DIRECTORY / "writer-coverage.json")
        ownership.validate_coverage(coverage, DIRECTORY.parents[1])
        for status in ("NOT_WIRED", "WIRED"):
            with self.subTest(source_status=status):
                sample = copy.deepcopy(coverage)
                sample["writers"][0]["status"] = status
                ownership.validate_coverage(sample)

    def test_sequence_catalog_includes_implicit_column_sequences(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = "platform/platform-org/src/main/resources/db/migration/platform/V1__columns.sql"
            sql = root / relative
            sql.parent.mkdir(parents=True)
            sql.write_text(
                "CREATE TABLE design_job (command_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY);\n"
                "CREATE TABLE pending (id BIGSERIAL PRIMARY KEY);\n"
                "CREATE TABLE audit_row (entry_id bigint GENERATED BY DEFAULT AS IDENTITY);\n"
                "CREATE TABLE existing (id bigint DEFAULT 0);\n"
            )
            migrations = [{"path": relative, "location": "platform", "version": "1",
                           "source_sha256": ownership.digest(sql)}]
            tables = [{"name": name, "owner": "B", "strategy": "filtered-copy",
                       "source_migrations": [relative], "selector": "fixture"}
                      for name in ("design_job", "pending", "audit_row", "existing")]
            expected = [{"name": name, "source_migration": relative, "catalog_verified": False}
                        for name in ("design_job_command_id_seq", "pending_id_seq", "audit_row_entry_id_seq")]
            manifest = {"format": ownership.MANIFEST_FORMAT, "locations": list(ownership.LOCATIONS),
                        "migrations": migrations, "tables": tables, "sequences": expected}
            ownership.validate_manifest(manifest, root)
            for missing in expected:
                with self.subTest(sequence=missing["name"]):
                    manifest["sequences"] = [sequence for sequence in expected if sequence != missing]
                    with self.assertRaisesRegex(ownership.EvidenceError, "SEQUENCE_COVERAGE_CHANGED"):
                        ownership.validate_manifest(manifest, root)

    def test_missing_or_duplicate_writer_is_rejected(self):
        original = ownership.read_json(DIRECTORY / "writer-coverage.json")
        for duplicate in (False, True):
            coverage = copy.deepcopy(original)
            coverage["writers"].pop()
            if duplicate:
                coverage["writers"].append(copy.deepcopy(coverage["writers"][0]))
            with self.assertRaises(ownership.EvidenceError):
                ownership.validate_coverage(coverage)

    def test_static_coverage_cannot_claim_freeze_or_live_proof(self):
        original = ownership.read_json(DIRECTORY / "writer-coverage.json")
        for field in ("freeze_ready", "migration_ready", "production_verified", "ownership_universe_verified",
                      "durable_work_reviewed", "callback_receiver_verified", "unique_consumers_verified"):
            coverage = copy.deepcopy(original)
            coverage[field] = True
            with self.subTest(field=field), self.assertRaisesRegex(ownership.EvidenceError, "STATIC_COVERAGE_ONLY"):
                ownership.validate_coverage(coverage)
        for field in ("instance_acknowledgements", "watermarks"):
            coverage = copy.deepcopy(original)
            coverage[field] = {"instance": "claimed"}
            with self.subTest(field=field), self.assertRaisesRegex(ownership.EvidenceError, "STATIC_COVERAGE_ONLY"):
                ownership.validate_coverage(coverage)
        original["writers"][0]["status"] = "PRODUCTION_VERIFIED"
        with self.assertRaisesRegex(ownership.EvidenceError, "WRITER_STATUS_INVALID"):
            ownership.validate_coverage(original)

    def test_writer_candidate_path_escape_is_rejected(self):
        coverage = ownership.read_json(DIRECTORY / "writer-coverage.json")
        coverage["writers"][0]["candidate_paths"] = ["../../.env"]
        with self.assertRaisesRegex(ownership.EvidenceError, "WRITER_PATH_INVALID"):
            ownership.validate_coverage(coverage)

    def test_manifest_rejects_missing_table_and_path_escape(self):
        manifest = json.loads((DIRECTORY / "manifest.json").read_text())
        manifest["tables"][0]["owner"] = "UNKNOWN"
        with self.assertRaisesRegex(ownership.EvidenceError, "TABLE_OWNER_INVALID"):
            ownership.validate_manifest(manifest)
        manifest = json.loads((DIRECTORY / "manifest.json").read_text())
        manifest["migrations"][0]["path"] = "../../.env"
        with self.assertRaisesRegex(ownership.EvidenceError, "MIGRATION_PATH_INVALID"):
            ownership.validate_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
