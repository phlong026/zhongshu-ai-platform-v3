#!/usr/bin/env python3
"""Exercise the release verifier with nested executable-JAR fixtures."""
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

VERIFIER = Path(__file__).with_name("verify-platform-artifact.py")
LOCATIONS = ["platform", "pf", "franchise", "franchise-system", "platform-wallet", "franchise-asset", "platform-bootstrap", "franchise-design-remote"]


class ArtifactVerifierTest(unittest.TestCase):
    def fixture(self, path, extra_library=None, extra_resource=None, duplicate=False, fusion_processor=False):
        with zipfile.ZipFile(path, "w") as outer:
            outer.writestr("META-INF/MANIFEST.MF", "Start-Class: cn.iocoder.yudao.platformserver.YudaoPlatformServerApplication\n")
            outer.writestr("BOOT-INF/classes/cn/iocoder/yudao/platformserver/YudaoPlatformServerApplication.class", b"fixture")
            factories = "org.springframework.boot.env.EnvironmentPostProcessor=cn.iocoder.yudao.platformserver.wiring.PlatformEnvironmentPostProcessor\n"
            if fusion_processor:
                factories += "legacy.processor=cn.iocoder.yudao.server.wiring.LegacyEnvironmentPostProcessor\n"
            outer.writestr("META-INF/spring.factories", factories)
            outer.writestr("BOOT-INF/classes/application-pg.properties", "spring.flyway.locations=" + ",".join("classpath:db/migration/" + item for item in LOCATIONS) + "\n")
            outer.writestr("BOOT-INF/classes/db/migration/platform-bootstrap/V0__bootstrap.sql", "SELECT 1;")
            outer.writestr("BOOT-INF/classes/db/migration/franchise-design-remote/V10__remote.sql", "SELECT 1;")
            for index, library in enumerate(["yudao-module-system", "yudao-module-infra", "yudao-platform-identity",
                                             "platform-wallet", "yudao-domain-franchise-content", "yudao-domain-franchise-asset"]):
                content = io.BytesIO()
                with zipfile.ZipFile(content, "w") as nested:
                    version = 1 if duplicate else index + 1
                    nested.writestr(f"db/migration/{LOCATIONS[index]}/V{version}__example.sql", "SELECT 1;")
                    if index == 1 and extra_resource:
                        nested.writestr(extra_resource, "SELECT 1;")
                outer.writestr(f"BOOT-INF/lib/{library}-2026.08-SNAPSHOT.jar", content.getvalue())
            if extra_library:
                content = io.BytesIO()
                with zipfile.ZipFile(content, "w"):
                    pass
                outer.writestr("BOOT-INF/lib/" + extra_library, content.getvalue())

    def check(self, **kwargs):
        self.assertTrue(VERIFIER.is_file(), "A standalone nested-JAR verifier is required")
        with tempfile.TemporaryDirectory(prefix="platform-artifact-test-") as directory:
            path = Path(directory) / "yudao-platform-server.jar"
            self.fixture(path, **kwargs)
            return subprocess.run([sys.executable, "-I", str(VERIFIER), str(path)],
                                  capture_output=True, text=True, timeout=10, check=False)

    def test_accepts_b_package_and_emits_migration_manifest(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('"migrations"', result.stdout)
        self.assertIn('"sha256"', result.stdout)

    def test_rejects_fusion_processor_in_root_metadata(self):
        result = self.check(fusion_processor=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Fusion environment processor", result.stderr)

    def test_rejects_old_design_dependency(self):
        result = self.check(extra_library="yudao-module-design-2026.08-SNAPSHOT.jar")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("yudao-module-design", result.stderr)

    def test_rejects_design_migration_hidden_inside_allowed_dependency(self):
        result = self.check(extra_resource="db/migration/design/V9__leaked.sql")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("db/migration/design", result.stderr)

    def test_rejects_legacy_classes_hidden_inside_allowed_dependency(self):
        result = self.check(extra_resource="cn/iocoder/yudao/module/commerce/Unexpected.class")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("commerce", result.stderr)

    def test_rejects_duplicate_flyway_versions_across_dependencies(self):
        result = self.check(duplicate=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("version", result.stderr)

    def test_includes_java_migration_in_the_packaged_checksum_manifest(self):
        resource = "db/migration/pf/V20261122_989__preserve_changed_staff_passwords.class"
        result = self.check(extra_resource=resource)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(resource, result.stdout)

    def test_rejects_java_migration_version_collision_with_sql(self):
        result = self.check(extra_resource="db/migration/pf/V1__collision.class")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Duplicate Flyway version", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
