import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import ops
from franchise_coverage_seed import seed_sql


class CoverageSeedTest(unittest.TestCase):
    def setUp(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = Path(tmp.name) / "coverage.json"
        self.payload = {"workbook_sha256": "a" * 64, "rows": [{
            "sheet": 1, "cell": "C3", "brand": "测试品牌", "name": "测试覆盖记录", "region": "示例区",
            "province": "420000", "city": "420100", "codes": ["420102"],
            "status": "RESOLVED", "fingerprint": "b" * 64,
        }]}

    def save(self):
        raw = json.dumps(self.payload).encode()
        self.path.write_bytes(raw)
        self.path.chmod(0o600)
        return hashlib.sha256(raw).hexdigest()

    def test_seed_is_insert_only_and_uses_original_source_keys(self):
        sql = seed_sql(self.path, self.save(), 37, ops.REPOSITORY)
        self.assertIn("':s' || row.sheet || ':' || row.cell AS source_key", sql)
        self.assertIn("ON CONFLICT(tenant_id,source_key) DO NOTHING", sql)
        self.assertIn("b.id,seed.name,'COVERAGE'", sql)
        self.assertIn("public.system_tenant WHERE id=37", sql)
        self.assertNotIn("测试品牌", sql)

    def test_wrong_hash_and_wrong_path_permissions_refuse(self):
        digest = self.save()
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            seed_sql(self.path, "0" * 64, 37, ops.REPOSITORY)
        self.path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "private"):
            seed_sql(self.path, digest, 37, ops.REPOSITORY)

    def test_refuses_non_coverage_and_duplicate_source(self):
        row = self.payload["rows"][0]
        self.payload["rows"].append(dict(row))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            seed_sql(self.path, self.save(), 37, ops.REPOSITORY)
        self.payload["rows"].pop()
        row["record_type"] = "FRANCHISE"
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            seed_sql(self.path, self.save(), 37, ops.REPOSITORY)

    def test_default_migrate_never_seeds_and_opt_in_seeds_after_schema(self):
        digest = self.save()
        env = self.path.with_suffix(".env")
        env.touch(mode=0o600)
        call_order = []
        with patch.object(ops.same_host, "env_values", return_value={}), \
             patch.object(ops, "load_model", return_value={}), \
             patch.object(ops, "compose_base", return_value=["base"]), \
             patch.object(ops, "inspect_scope"), \
             patch.object(ops, "migration_command", side_effect=lambda *_: call_order.append("migrate")), \
             patch.object(ops, "enforce_database_policy", side_effect=lambda *_: call_order.append("policy")), \
             patch.object(ops, "psql", side_effect=lambda *_: call_order.append("seed") or b'{"created": 1}'):
            command = ["ops.py", "migrate", "--env-file", str(env)]
            extras = ["--coverage-seed-file", str(self.path), "--coverage-seed-sha256", digest,
                      "--coverage-seed-tenant", "37"]
            with patch("sys.argv", command + extras):
                ops.main()
            self.assertEqual([], call_order)
            command += ["--execute", "--confirm-target", ops.TARGET, "--writes-frozen"]
            with patch("sys.argv", command):
                ops.main()
            self.assertEqual(["migrate", "policy"], call_order)
            with patch("sys.argv", command + extras):
                ops.main()
            self.assertEqual(["migrate", "policy", "migrate", "policy", "seed"], call_order)


if __name__ == "__main__":
    unittest.main()
