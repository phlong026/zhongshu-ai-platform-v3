#!/usr/bin/env python3
"""Source release boundary checks; no Maven, network or database required."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "yudao-platform-server"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
FORBIDDEN = {
    "yudao-server", "yudao-module-identity", "yudao-module-design",
    "yudao-module-commerce", "yudao-module-ai-orchestration",
}
LOCATIONS = {
    "classpath:db/migration/platform-bootstrap",
    "classpath:db/migration/platform", "classpath:db/migration/pf",
    "classpath:db/migration/franchise", "classpath:db/migration/franchise-system",
    "classpath:db/migration/platform-wallet", "classpath:db/migration/franchise-asset",
    "classpath:db/migration/franchise-design-remote",
}


def properties(path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


class PlatformReleaseBoundaryTest(unittest.TestCase):
    def launcher_pom(self):
        path = LAUNCHER / "pom.xml"
        self.assertTrue(path.is_file(), "B needs its own launcher POM")
        return ET.parse(path).getroot()

    def runtime_closure(self):
        self.launcher_pom()
        modules = {}
        for pom in ROOT.rglob("pom.xml"):
            if any(part in {"target", "node_modules"} for part in pom.parts):
                continue
            tree = ET.parse(pom).getroot()
            modules[tree.findtext("m:artifactId", namespaces=NS)] = (pom, tree)
        seen = set()
        pending = ["yudao-platform-server"]
        while pending:
            artifact = pending.pop()
            if artifact in seen or artifact not in modules:
                continue
            seen.add(artifact)
            _, tree = modules[artifact]
            for dependency in tree.findall("m:dependencies/m:dependency", NS):
                scope = dependency.findtext("m:scope", "compile", NS)
                optional = dependency.findtext("m:optional", "false", NS)
                if scope not in {"test", "provided"} and (
                    optional != "true" or artifact == "yudao-platform-server"
                ):
                    pending.append(dependency.findtext("m:artifactId", namespaces=NS))
        return {artifact: modules[artifact][0].parent for artifact in seen}

    def test_b_launcher_is_registered_separately_from_fusion(self):
        self.launcher_pom()
        modules = [node.text for node in ET.parse(ROOT / "pom.xml").findall("m:modules/m:module", NS)]
        self.assertIn("yudao-platform-server", modules)
        self.assertIn("yudao-server", modules)

    def test_runtime_dependency_closure_has_no_design_implementation(self):
        closure = self.runtime_closure()
        self.assertFalse(FORBIDDEN.intersection(closure), sorted(FORBIDDEN.intersection(closure)))
        self.assertTrue({"yudao-module-system", "yudao-module-infra",
                         "yudao-platform-identity", "yudao-domain-franchise-content"}.issubset(closure))

    def test_runtime_resources_contain_only_b_migration_sources(self):
        for artifact, directory in self.runtime_closure().items():
            migration = directory / "src/main/resources/db/migration"
            if migration.exists():
                for sql in migration.rglob("*.sql"):
                    location = "classpath:db/migration/" + sql.relative_to(migration).parts[0]
                    self.assertIn(location, LOCATIONS, f"{artifact}: {sql}")
        self.assertNotIn("yudao-server", self.runtime_closure())

    def test_pg_profile_uses_explicit_b_locations(self):
        path = LAUNCHER / "src/main/resources/application-pg.properties"
        self.assertTrue(path.is_file(), "B needs an independent PostgreSQL profile")
        config = properties(path)
        self.assertEqual(set(config["spring.flyway.locations"].split(",")), LOCATIONS)
        self.assertNotIn("spring.flyway.ignore-migration-patterns", config)
        self.assertNotIn("zhongshu_design", path.read_text(encoding="utf-8"))

    def test_regular_startup_cannot_migrate_and_readiness_checks_dependencies(self):
        path = LAUNCHER / "src/main/resources/application.properties"
        self.assertTrue(path.is_file(), "B needs independent safe startup defaults")
        config = properties(path)
        self.assertEqual(config["spring.flyway.enabled"], "false")
        self.assertEqual(config["spring.sql.init.mode"], "never")
        self.assertEqual(config["spring.jpa.hibernate.ddl-auto"], "none")
        self.assertNotIn("QuartzAutoConfiguration", config.get("spring.autoconfigure.exclude", ""),
                         "infra needs a real scheduler even when automatic job startup is disabled")
        self.assertEqual(set(config["management.endpoint.health.group.readiness.include"].split(",")),
                         {"db", "redis", "readinessState"})
        self.assertEqual(config["management.endpoint.health.show-details"], "never")
        self.assertEqual(config["platform.legacy-design-bridge.enabled"], "false")
        self.assertEqual(config["zhongshu.design.remote.enabled"], "true")

    def test_bootstrap_is_versioned_and_contains_no_legacy_credentials_or_jobs(self):
        bootstrap = LAUNCHER / "src/main/resources/db/migration/platform-bootstrap"
        files = sorted(bootstrap.glob("V*.sql"))
        self.assertEqual(len(files), 2, "B must own versioned system and Quartz bootstrap migrations")
        sql = "\n".join(path.read_text(encoding="utf-8") for path in files)
        for table in ["system_users", "system_user_role", "system_user_post", "system_mail_account",
                      "system_social_client", "system_sms_channel", "infra_file_config", "infra_job"]:
            self.assertNotIn("INSERT INTO " + table + " ", sql, table)
        self.assertIn("CREATE TABLE system_users", sql)
        self.assertIn("CREATE TABLE QRTZ_JOB_DETAILS", sql)
        self.assertFalse({"BEGIN;", "COMMIT;"}.intersection(sql.splitlines()),
                         "Flyway owns bootstrap transactions; inline commits would leave partial schema")

    def test_unknown_nonempty_database_is_not_automatically_baselined(self):
        config = properties(LAUNCHER / "src/main/resources/application-pg.properties")
        self.assertEqual(config["spring.flyway.baseline-on-migrate"], "false")

    def test_fusion_still_retains_all_original_implementations(self):
        tree = ET.parse(ROOT / "yudao-server/pom.xml").getroot()
        dependencies = {node.text for node in tree.findall("m:dependencies/m:dependency/m:artifactId", NS)}
        self.assertTrue((FORBIDDEN - {"yudao-server"}).issubset(dependencies))


if __name__ == "__main__":
    unittest.main(verbosity=2)
