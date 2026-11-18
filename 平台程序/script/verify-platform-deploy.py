#!/usr/bin/env python3
"""Offline deployment regression checks. Never contacts a Docker daemon."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "deploy/platform"
# Isolated Python mode removes the script search path; load only the trusted deployment helpers.
sys.path.insert(0, str(DEPLOY))
REQUIRED = (
    "Dockerfile", "Dockerfile.dockerignore", "compose.yaml", "runtime.properties",
    "Healthcheck.java", "postgres-init.sh", "postgres-policy.sql", "pg_hba.conf",
    "redis-start.sh", "redis-health.sh", "ops.py", "deployment.env.example",
)


class DeploymentChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        missing = [name for name in REQUIRED if not (DEPLOY / name).is_file()]
        if missing:
            raise AssertionError("Missing deployment implementation: " + ", ".join(missing))
        spec = importlib.util.spec_from_file_location("platform_ops", DEPLOY / "ops.py")
        cls.ops = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.ops)
        cls.temp = tempfile.TemporaryDirectory(prefix=".verify-", dir=DEPLOY)
        cls.addClassCleanup(cls.temp.cleanup)
        cls.tmp = Path(cls.temp.name)
        cls.env = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"],
                   "PLATFORM_API_IMAGE": "local/platform-api@sha256:" + "a" * 64}
        for key in cls.ops.SECRET_VARIABLES:
            path = cls.tmp / key.lower()
            content = "platform.deployment-id=verification-only\n" if key == "PLATFORM_CONFIG_FILE" else secrets.token_hex(32)
            path.write_text(content)
            path.chmod(0o640)
            cls.env[key] = str(path)
        cls.model = cls.parse(DEPLOY / "compose.yaml")

    @classmethod
    def parse(cls, source, env=None, expect_ok=True):
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "-f", str(source),
             "config", "--format", "json"], env=cls.env if env is None else env,
            capture_output=True, text=True, timeout=30,
        )
        if expect_ok:
            if result.returncode:
                raise AssertionError("Compose parsing failed: " + result.stderr)
            return json.loads(result.stdout)
        if not result.returncode:
            raise AssertionError("Compose accepted missing mandatory configuration")

    def mutate(self, change):
        model = copy.deepcopy(self.model)
        change(model)
        source = self.tmp / "mutation.json"
        source.write_text(json.dumps(model))
        return self.parse(source)

    def test_01_real_compose_parser_and_policy(self):
        self.ops.validate_model(self.model)
        self.assertEqual(self.model["name"], "zhongshu-platform-b")
        self.assertEqual(set(self.model["services"]), {"api", "postgres-b", "redis-b"})
        for service, expected in (
            ("api", ["/tmp:size=128m,mode=1777"]),
            ("redis-b", ["/tmp:size=16m,mode=1777"]),
        ):
            with self.subTest(service=service):
                self.assertEqual(self.model["services"][service]["tmpfs"], expected)

    def test_spool_mount_is_private_fixed_and_required(self):
        for change in (
            lambda m: m["volumes"]["asset-spool"].update(external=True),
            lambda m: m["volumes"]["asset-spool"].update(driver_opts={"device": "/other"}),
            lambda m: m["services"]["api"].update(volumes=[x for x in m["services"]["api"]["volumes"] if x["type"] == "bind"]),
            lambda m: next(x for x in m["services"]["api"]["volumes"] if x["type"] == "volume").update(target="/other"),
            lambda m: m["services"]["redis-b"]["volumes"].append({"type": "volume", "source": "asset-spool", "target": "/uploads"}),
        ):
            model = copy.deepcopy(self.model)
            change(model)
            with self.assertRaises(ValueError):
                self.ops.validate_model(model)

    def test_02_missing_configuration_fails_closed(self):
        for key in ("PLATFORM_API_IMAGE", *self.ops.SECRET_VARIABLES):
            with self.subTest(key=key):
                env = dict(self.env)
                env.pop(key)
                self.parse(DEPLOY / "compose.yaml", env=env, expect_ok=False)

    def test_03_negative_compose_models(self):
        changes = {
            "wrong project": lambda m: m.update(name="design-a"),
            "unversioned image": lambda m: m["services"]["api"].update(image="platform-api:latest"),
            "host network": lambda m: m["services"]["api"].update(network_mode="host", networks=None),
            "privileged": lambda m: m["services"]["api"].update(privileged=True),
            "root user": lambda m: m["services"]["api"].update(user="0:0"),
            "exposed postgres": lambda m: m["services"]["postgres-b"].update(ports=["5432:5432"]),
            "public API": lambda m: m["services"]["api"]["ports"][0].update(host_ip="0.0.0.0"),
            "shared volume": lambda m: m["volumes"]["pgdata"].update(name="design_data"),
            "external network": lambda m: m["networks"]["private"].update(external=True),
            "no memory limit": lambda m: m["services"]["api"].pop("mem_limit"),
            "no log rotation": lambda m: m["services"]["api"].pop("logging"),
            "no health dependency": lambda m: m["services"]["api"]["depends_on"]["postgres-b"].update(condition="service_started"),
            "production build": lambda m: m["services"]["api"].update(build={"context": str(DEPLOY)}),
            "production pull": lambda m: m["services"]["api"].update(pull_policy="always"),
            "A database": lambda m: m["services"]["api"]["command"].append("--spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://host.docker.internal/zhongshu_design"),
            "automatic migration": lambda m: m["services"]["api"]["command"].append("--spring.flyway.enabled=true"),
            "docker socket": lambda m: m["services"]["api"].setdefault("volumes", []).append({"type": "bind", "source": "/var/run/docker.sock", "target": "/var/run/docker.sock"}),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                # Docker itself may reject an invalid model; otherwise our policy must.
                try:
                    model = self.mutate(change)
                except AssertionError:
                    continue
                with self.assertRaises(ValueError):
                    self.ops.validate_model(model)

    def test_04_secret_files_are_external_distinct_and_not_defaults(self):
        # Exercise Linux policy on every host without privileged chgrp of test fixtures.
        fixture_repository = self.tmp / "source-tree"
        fixture_paths = {Path(secret["file"]).resolve() for secret in self.model["secrets"].values()}
        real_stat = Path.stat

        def validate_with_gid(gid=20000, repository=fixture_repository):
            def fixture_stat(path, *args, **kwargs):
                metadata = real_stat(path, *args, **kwargs)
                if path in fixture_paths:
                    fields = list(metadata)
                    fields[5] = gid  # stat_result.st_gid; keep real mode, size and file identity.
                    return os.stat_result(fields)
                return metadata

            with mock.patch.object(self.ops.sys, "platform", "linux"), \
                    mock.patch.object(Path, "stat", fixture_stat):
                self.ops.validate_secret_files(self.model, repository=repository)

        with self.assertRaisesRegex(ValueError, "outside the repository"):
            validate_with_gid(repository=ROOT)
        validate_with_gid()
        with self.assertRaisesRegex(ValueError, "GID 20000"):
            validate_with_gid(gid=12345)
        key = "PLATFORM_PG_APP_PASSWORD_FILE"
        path = Path(self.env[key])
        original = path.read_bytes()
        try:
            path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "group-readable"):
                validate_with_gid()
            path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "world-accessible"):
                validate_with_gid()
            path.chmod(0o640)
            for bad in (b"", b"changeme", b"x" * 32 + b"\n"):
                path.write_bytes(bad)
                with self.subTest(value_length=len(bad)), self.assertRaises(ValueError):
                    validate_with_gid()
        finally:
            path.write_bytes(original)
            path.chmod(0o640)

    def test_05_mutations_require_explicit_confirmation(self):
        for action in ("migrate", "restore", "rollback"):
            with self.subTest(action=action):
                with self.assertRaises(ValueError):
                    self.ops.require_confirmation(action, execute=True, target="design-a", frozen=True)
                with self.assertRaises(ValueError):
                    self.ops.require_confirmation(action, execute=True, target=self.ops.TARGET, frozen=False)
                self.ops.require_confirmation(action, execute=False, target=None, frozen=False)

    def test_06_image_scope_and_labels(self):
        for image in ("alpine", "alpine:latest", "a:v1\n--privileged", "a:edge"):
            with self.subTest(image=image), self.assertRaises(ValueError):
                self.ops.validate_image(image)
        with self.assertRaises(ValueError):
            self.ops.validate_container({"Config": {"Labels": {"com.docker.compose.project": "design-a"}}}, "postgres-b", self.model)

    def test_07_migration_compose_owner_only(self):
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "-f", str(DEPLOY / "compose.yaml"),
             "-f", str(DEPLOY / "compose.migrate.yaml"), "config", "--format", "json"],
            env=self.env, capture_output=True, text=True, timeout=30, check=True,
        )
        model = json.loads(result.stdout)
        api = model["services"]["api"]
        self.assertEqual(model["name"], self.ops.PROJECT)
        self.assertFalse(api.get("ports"))
        self.assertFalse(api.get("depends_on"))
        self.assertEqual(api["command"][0], "migrate")
        self.assertIn("--spring.profiles.active=pg,migration", api["command"])
        self.assertIn("--spring.flyway.user=platform_owner", api["command"])
        self.assertEqual({s["source"] for s in api["secrets"]}, {"pg_owner_password", "platform_config"})
        self.assertEqual({s["source"] for s in self.model["services"]["api"]["secrets"]},
                         {"pg_app_password", "redis_app_password", "platform_config"})
        self.ops.validate_migration_model(model, self.model)
        for mutation in (
            lambda m: m["services"]["api"]["command"].append("--spring.flyway.url=jdbc:postgresql://design-a/zhongshu_design"),
            lambda m: m["services"]["api"].update(privileged=True),
            lambda m: m["services"]["api"].update(user="0:0"),
        ):
            unsafe = copy.deepcopy(model)
            mutation(unsafe)
            with self.assertRaises(ValueError):
                self.ops.validate_migration_model(unsafe, self.model)

    def test_08_backup_integrity_and_nonempty_restore_refused(self):
        folder = self.tmp / "backup"
        folder.mkdir(exist_ok=True)
        for name in ("postgres.dump", "redis.rdb"):
            (folder / name).write_bytes(secrets.token_bytes(32))
        manifest = {"project": self.ops.PROJECT, "database": self.ops.DATABASE, "writes_frozen": True,
                    "files": {n: self.ops.sha256(folder / n) for n in ("postgres.dump", "redis.rdb")}}
        manifest_path = folder / "manifest.json"
        manifest_path.write_text(json.dumps(manifest))
        digest = self.ops.sha256(manifest_path)
        self.ops.verify_backup(folder, digest)
        with self.assertRaises(ValueError):
            self.ops.verify_backup(folder, "0" * 64)
        (folder / "postgres.dump").write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.ops.verify_backup(folder, digest)
        with mock.patch.object(self.ops, "psql", return_value=b"3\n"), mock.patch.object(self.ops, "pg") as pg:
            with self.assertRaises(ValueError):
                self.ops.restore(["docker", "compose"], folder)
            pg.assert_not_called()

    def test_09_runtime_scope_rejects_wrong_volume_and_running_api(self):
        containers = []
        for service, volume in (("api", None), ("postgres-b", "pgdata"), ("redis-b", "redisdata")):
            container = {
                "Config": {"User": self.model["services"][service]["user"], "Labels": {
                    "com.docker.compose.project": self.ops.PROJECT, "com.docker.compose.service": service}},
                "NetworkSettings": {"Networks": {"zhongshu-platform-b_private": {}}},
                "State": {"Running": True, "Health": {"Status": "healthy"}},
                "Mounts": [],
            }
            for item in self.model["services"][service].get("volumes", []):
                if item["type"] == "volume":
                    mount = {"Type": "volume", "Name": self.model["volumes"][item["source"]]["name"]}
                else:
                    mount = {"Type": "bind", "Source": item["source"], "RW": False}
                mount["Destination"] = item["target"]
                container["Mounts"].append(mount)
            for item in self.model["services"][service]["secrets"]:
                target = item["target"]
                if not target.startswith("/"):
                    target = "/run/secrets/" + target
                container["Mounts"].append({"Type": "bind", "Source": self.model["secrets"][item["source"]]["file"], "Destination": target, "RW": False})
            container["Mounts"].append({"Type": "tmpfs", "Destination": "/tmp", "RW": True})
            self.ops.validate_container(container, service, self.model)
            containers.append(container)
        wrong = copy.deepcopy(containers[1])
        next(m for m in wrong["Mounts"] if m["Type"] == "volume")["Name"] = "design-a-data"
        with self.assertRaises(ValueError):
            self.ops.validate_container(wrong, "postgres-b", self.model)
        wrong_bind = copy.deepcopy(containers[0])
        wrong_bind["Mounts"] = [{"Type": "bind", "Source": "/srv/design-a/data", "Destination": "/read-only-a", "RW": False}]
        with self.assertRaises(ValueError):
            self.ops.validate_container(wrong_bind, "api", self.model)
        with mock.patch.object(self.ops, "run", side_effect=[b"one\ntwo\nthree", json.dumps(containers).encode()]):
            with self.assertRaisesRegex(ValueError, "Stop only B API"):
                self.ops.inspect_scope(["docker", "compose"], self.model, require_frozen=True)

    def test_10_default_migration_is_plan_without_daemon(self):
        envfile = self.tmp / "deployment.env"
        envfile.write_text("# fixture\n")
        envfile.chmod(0o600)
        with mock.patch("sys.argv", ["ops.py", "migrate", "--env-file", str(envfile)]), \
                mock.patch.object(self.ops, "REPOSITORY", self.tmp / "source-tree"), \
                mock.patch.object(self.ops, "load_model", return_value=self.model), \
                mock.patch.object(self.ops, "inspect_scope") as inspect_scope, \
                mock.patch.object(self.ops, "migration_command") as migration:
            self.ops.main()
            inspect_scope.assert_not_called()
            migration.assert_not_called()

    def test_11_shell_and_python_syntax(self):
        for source in DEPLOY.glob("*.sh"):
            subprocess.run(["sh", "-n", str(source)], check=True, timeout=10)
        for source in [DEPLOY / "ops.py", Path(__file__)]:
            compile(source.read_text(), str(source), "exec")

    def test_12_minimal_image_and_no_unsafe_commands(self):
        dockerfile = (DEPLOY / "Dockerfile").read_text()
        self.assertIn("yudao-platform-server/target/yudao-platform-server.jar", dockerfile)
        self.assertNotIn("COPY . ", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        ignore = (DEPLOY / "Dockerfile.dockerignore").read_text().splitlines()
        self.assertEqual(ignore[0], "**")
        for path in DEPLOY.iterdir():
            if path.suffix in (".py", ".sh", ".yaml", ".sql"):
                text = path.read_text()
                for forbidden in ("down -v", "system prune", "volume prune", "--privileged", "network_mode: host"):
                    self.assertNotIn(forbidden, text, f"{path.name}: {forbidden}")

    def test_13_redis_health_requires_actuator_info_permission(self):
        fake_bin = self.tmp / "fake-redis-bin"
        fake_bin.mkdir(exist_ok=True)
        password = self.tmp / "health-password"
        password.write_text(secrets.token_hex(32))
        (fake_bin / "cat").write_text('#!/bin/sh\nexec /bin/cat "$TEST_PASSWORD_FILE"\n')
        (fake_bin / "redis-cli").write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$TEST_REDIS_CALLS"
case "$*" in
  '--no-auth-warning --user platform_app -h 127.0.0.1 ping') printf 'PONG\\n' ;;
  '--no-auth-warning --user platform_app -h 127.0.0.1 info server')
    if [ "$TEST_INFO_ACCESS" = allowed ]; then
      printf '# Server\\r\\nredis_version:7.4.11\\r\\n'
    else
      printf 'NOPERM this user has no permissions to run the info command\\n'
    fi ;;
  *) exit 9 ;;
esac
''')
        for file in fake_bin.iterdir():
            file.chmod(0o755)
        calls = self.tmp / "redis-health-calls"
        for access, expected in (("denied", False), ("allowed", True)):
            with self.subTest(info_access=access):
                calls.write_text("")
                env = {**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                       "TEST_PASSWORD_FILE": str(password), "TEST_INFO_ACCESS": access,
                       "TEST_REDIS_CALLS": str(calls)}
                result = subprocess.run(["sh", str(DEPLOY / "redis-health.sh")], env=env, capture_output=True, timeout=5)
                self.assertEqual(calls.read_text().splitlines(), [
                    "--no-auth-warning --user platform_app -h 127.0.0.1 ping",
                    "--no-auth-warning --user platform_app -h 127.0.0.1 info server",
                ])
                self.assertEqual(result.returncode == 0, expected)
                self.assertNotIn(password.read_bytes(), result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
