import importlib.util
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verify = load("verify_capability", "verify-capability-deployment.py")
backup = load("full_backup", "full-backup.py")
release = load("release_capabilities", "release-capabilities.py")
same_host = load("same_host_storage", "same_host_storage.py")
ops = load("platform_ops", "ops.py")


def plain_full_model():
    digest = "registry.example/test@sha256:" + "a" * 64
    services = {name: {"image": digest, "pull_policy": "never", "networks": {"private": {}}}
                for name in verify.EXPECTED}
    services["api"]["environment"] = {"PF_DESIGN_ORIGIN": "https://design-provider:48443",
                                      "PF_DESIGN_PUBLIC_CASES_ORIGIN": "https://public-a.example.test"}
    services["ai-runtime"].update({"environment": {"ZS_AI_CORE_URL": "https://design-provider:48443"},
                                   "volumes": [{"type": "bind", "source": "/mnt/runtime-journal",
                                                "target": "/data"}]})
    services["design-provider"].update({
        "environment": {"HEALTH_URL": "https://design-provider:48443/actuator/health/readiness"},
        "depends_on": {"ai-runtime": {"condition": "service_healthy"}},
        "command": ["--spring.main.lazy-initialization=false", "--spring.flyway.enabled=false"],
    })
    volumes = {name: {"name": f"zhongshu-platform-b_{name}"}
               for name in same_host.VOLUME_DIRECTORIES}
    return {"name": "zhongshu-platform-b", "services": services, "networks": {"private": {}},
            "volumes": volumes, "secrets": {}}


def actual_bind_volumes(model):
    items = []
    for logical, config in model["volumes"].items():
        options = None
        if logical == "pgdata":
            options = {"type": "none", "o": "bind",
                       "device": "/mnt/datadisk0/zhongshu-platform-b/postgres-b"}
        items.append({"Name": config["name"], "Driver": "local", "Options": options,
                      "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                 "com.docker.compose.volume": logical}})
    return items


class CapabilityDeploymentTest(unittest.TestCase):
    def test_same_host_overlay_is_optional_but_partial_configuration_is_refused(self):
        self.assertEqual(same_host.compose_overlay({}), [])
        self.assertEqual(same_host.compose_overlay({"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}), [])
        values = same_host.example_values("/mnt/datadisk0")
        self.assertEqual(same_host.compose_overlay(values), ["-f", str(HERE / "compose.same-host.yaml")])
        del values["B_SAME_HOST_STORAGE_PROOF_FILE"]
        with self.assertRaises(ValueError):
            same_host.compose_overlay(values)

    def test_same_host_paths_are_fixed_b_children_and_never_overlap_a(self):
        values = same_host.example_values("/mnt/datadisk0")
        planned = same_host.path_plan(values, protected_paths=[Path("/srv/zhongshu-platform-a")])
        self.assertEqual(planned["postgres-b"], Path("/mnt/datadisk0/zhongshu-platform-b/postgres-b"))
        self.assertEqual(planned["backup-staging"],
                         Path("/mnt/datadisk0/zhongshu-platform-b/backup-staging"))
        values["B_SAME_HOST_DATA_ROOT"] = "/srv/zhongshu-platform-a/b-data"
        with self.assertRaises(ValueError):
            same_host.path_plan(values, protected_paths=[Path("/srv/zhongshu-platform-a")])

    def test_same_host_directory_contracts_keep_database_uids_and_private_modes(self):
        contracts = same_host.directory_contracts(Path("/mnt/datadisk0/zhongshu-platform-b"))
        for name in ("postgres-b", "redis-b", "postgres-design", "redis-design"):
            self.assertEqual((contracts[name].uid, contracts[name].gid, contracts[name].mode), (999, 999, 0o700))
        self.assertEqual((contracts["runtime-journal"].uid, contracts["runtime-journal"].gid,
                          contracts["runtime-journal"].mode), (1000, 1000, 0o700))
        self.assertEqual(contracts["backup-staging"].mode, 0o700)

    def test_every_deployment_path_uses_the_same_host_overlay(self):
        values = same_host.example_values("/mnt/datadisk0")
        overlay = str(HERE / "compose.same-host.yaml")
        self.assertEqual(verify.compose_files(values)[-2:], ["-f", overlay])
        self.assertEqual(backup.compose(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])
        self.assertEqual(release.compose_base(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])
        self.assertEqual(ops.compose_base(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])

    def test_same_host_volume_devices_symlinks_and_backup_escape_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory)
            values = same_host.example_values(mount)
            root = mount / "zhongshu-platform-b"
            staging = root / "backup-staging"
            staging.mkdir(parents=True)
            model = {"volumes": {}}
            for volume, child in same_host.VOLUME_DIRECTORIES.items():
                model["volumes"][volume] = {
                    "name": f"zhongshu-platform-b_{volume}", "driver": "local",
                    "driver_opts": {"type": "none", "o": "bind", "device": str(root / child)},
                }
            same_host.validate_compose_volumes(model, values)
            model["volumes"]["pgdata"]["driver_opts"]["device"] = str(mount / "a-data")
            with self.assertRaises(ValueError):
                same_host.validate_compose_volumes(model, values)

            link = mount / "linked"
            link.symlink_to(staging, target_is_directory=True)
            with self.assertRaises(ValueError):
                same_host._reject_symlink_components(link / "backup")
            data = root / "postgres-b"
            data.mkdir()
            (data / "pg_wal").symlink_to(staging, target_is_directory=True)
            with self.assertRaises(ValueError):
                same_host.validate_tree_with_mounts(data, data.stat().st_dev, set())
            (data / "pg_wal").unlink()
            with self.assertRaises(ValueError):
                same_host.validate_tree_with_mounts(data, data.stat().st_dev, {data.resolve()})

            journal = root / "runtime-journal"
            journal.mkdir()
            same_host.validate_journal(values, journal, set())
            with self.assertRaises(ValueError):
                same_host.validate_journal({}, journal)

            with self.assertRaises(ValueError):
                same_host.validate_backup_output(values, staging / "backup-1")
            with self.assertRaises(ValueError):
                ops.validate_backup_location("backup", values, staging / "backup-2")

            volume = {
                "Name": "zhongshu-platform-b_pgdata", "Driver": "local",
                "Options": {"type": "none", "o": "bind", "device": str(root / "postgres-b")},
                "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                           "com.docker.compose.volume": "pgdata"},
            }
            same_host.validate_volume_inspection([volume], values)
            volume["Options"] = None
            with self.assertRaises(ValueError):
                same_host.validate_volume_inspection([volume], values)

    def test_full_backup_binds_actual_container_mounts_to_reviewed_model(self):
        model = {
            "services": {"redis-b": {"image": "redis@sha256:" + "a" * 64, "volumes": [
                {"type": "volume", "source": "redisdata", "target": "/data"},
                {"type": "bind", "source": str(HERE / "redis-start.sh"),
                 "target": "/etc/platform/redis-start.sh", "read_only": True},
            ]}},
            "volumes": {"redisdata": {"name": "zhongshu-platform-b_redisdata"}},
            "secrets": {},
        }
        container = {
            "Id": "redis-container",
            "Config": {"Image": "redis@sha256:" + "a" * 64,
                       "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                   "com.docker.compose.service": "redis-b"}},
            "NetworkSettings": {"Networks": {"zhongshu-platform-b_private": {}}},
            "Mounts": [
                {"Type": "volume", "Name": "zhongshu-platform-b_redisdata",
                 "Destination": "/data", "RW": True},
                {"Type": "bind", "Source": str(HERE / "redis-start.sh"),
                 "Destination": "/etc/platform/redis-start.sh", "RW": False},
            ],
        }
        backup.validate_container_scope(container, "redis-b", model)
        container["Mounts"][0]["Name"] = "a-shared-volume"
        with self.assertRaises(ValueError):
            backup.validate_container_scope(container, "redis-b", model)

    def test_same_host_release_is_closed_without_reviewed_approver_trust_anchor(self):
        with tempfile.TemporaryDirectory() as directory:
            values = same_host.example_values(directory)
            with patch.object(same_host, "TRUSTED_APPROVER_KEY_HASH_FILE",
                              Path(directory) / "missing-trust-anchor"):
                with self.assertRaisesRegex(ValueError, "not provisioned"):
                    same_host.validate_attestation(values, {}, Path(directory) / "proof",
                                                   Path(directory) / "protected", set(), set())

    def test_physical_block_ancestors_must_be_stable_and_disjoint(self):
        topology = [
            {"name": "vda", "kname": "vda", "maj:min": "8:0", "serial": "A-DISK",
             "children": [{"name": "vda1", "kname": "vda1", "pkname": "vda",
                           "maj:min": "8:1"}]},
            {"name": "vdb", "kname": "vdb", "maj:min": "8:16", "serial": "B-DISK",
             "children": [{"name": "vdb1", "kname": "vdb1", "pkname": "vdb",
                           "maj:min": "8:17"}]},
        ]
        b_ids, a_ids = same_host.validate_block_independence("8:17", ["8:1"], topology)
        self.assertEqual((b_ids, a_ids), ({"B-DISK"}, {"A-DISK"}))
        with self.assertRaises(ValueError):
            same_host.validate_block_independence("8:17", ["8:16"], topology)

    def test_actual_same_host_bind_volume_cannot_be_hidden_by_plain_env(self):
        plain_model = {"volumes": {"pgdata": {"name": "zhongshu-platform-b_pgdata"}}}
        actual = [{
            "Name": "zhongshu-platform-b_pgdata", "Driver": "local",
            "Options": {"type": "none", "o": "bind", "device": "/mnt/datadisk0/zhongshu-platform-b/postgres-b"},
            "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                       "com.docker.compose.volume": "pgdata"},
        }]
        with self.assertRaises(ValueError):
            same_host.validate_actual_volumes_against_model(actual, plain_model)

    def test_verify_cli_rejects_hidden_bind_volume_before_external_validation(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        gate = lambda rendered, environment=None: same_host.validate_actual_volumes_against_model(hidden, rendered)
        with tempfile.NamedTemporaryFile() as env_file, \
                patch.object(verify, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}, {})), \
                patch.object(verify, "run", return_value=json.dumps(model).encode()), \
                patch.object(verify.same_host, "inspect_existing_model_volumes", side_effect=gate):
            with self.assertRaises(ValueError):
                verify.validate(Path(env_file.name), False)

    def test_release_cli_rechecks_hidden_bind_volume_before_any_up(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        commands = []

        def fake_run(command, context, environment, timeout=1800):
            commands.append(command)
            return json.dumps(model).encode()

        gate = lambda rendered, environment=None: same_host.validate_actual_volumes_against_model(hidden, rendered)
        args = SimpleNamespace(env_file=Path("/external/deployment.env"))
        with patch.object(release.VERIFY, "validate"), \
                patch.object(release.VERIFY, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}, {})), \
                patch.object(release, "run", side_effect=fake_run), \
                patch.object(release.VERIFY.same_host, "inspect_existing_model_volumes", side_effect=gate):
            with self.assertRaises(ValueError):
                release.release(args)
        self.assertFalse(any("up" in command for command in commands))

    def test_ops_cli_rejects_hidden_bind_volume_before_dump(self):
        model = {"services": {name: {} for name in ("api", "postgres-b", "redis-b")},
                 "volumes": {name: {"name": f"zhongshu-platform-b_{name}"}
                             for name in ("pgdata", "redisdata")}}
        hidden = actual_bind_volumes({"volumes": model["volumes"]})
        containers = [{"Id": service, "Config": {"Labels": {
            "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": service}},
            "State": {"Running": service != "api", "Health": {"Status": "healthy"}}}
            for service in model["services"]]

        def fake_run(command, **kwargs):
            if command[:3] == ["docker", "ps", "-aq"]:
                return b"api postgres-b redis-b"
            if command[:2] == ["docker", "inspect"]:
                return json.dumps(containers).encode()
            if command[:3] == ["docker", "volume", "inspect"]:
                name = command[-1]
                return json.dumps([next(item for item in hidden if item["Name"] == name)]).encode()
            if command[:3] == ["docker", "network", "inspect"]:
                return json.dumps([{"Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                                "com.docker.compose.network": "private"}}]).encode()
            raise AssertionError(command)

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "deployment.env"
            env_file.write_text("AI_RUNTIME_JOURNAL_DIR=/mnt/runtime-journal\n")
            env_file.chmod(0o600)
            backup_dir = Path(directory) / "backup"
            argv = ["ops.py", "backup", "--env-file", str(env_file), "--execute", "--writes-frozen",
                    "--confirm-target", ops.TARGET, "--backup-dir", str(backup_dir)]
            with patch.object(sys, "argv", argv), patch.object(ops, "load_model", return_value=model), \
                    patch.object(ops, "compose_base", return_value=["docker", "compose"]), \
                    patch.object(ops, "run", side_effect=fake_run), patch.object(ops, "validate_container"), \
                    patch.object(ops, "backup") as dump:
                with self.assertRaises(ValueError):
                    ops.main()
                dump.assert_not_called()

    def test_full_backup_cli_rejects_hidden_bind_volume_before_dump(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        gate = lambda items, rendered, values: same_host.validate_actual_volumes_against_model(items, rendered)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file, inventory = root / "deployment.env", root / "cos.json"
            journal = root / "journal"
            journal.mkdir()
            env_file.write_text(f"AI_RUNTIME_JOURNAL_DIR={journal}\nCOS_B_BUCKET=b-test\n")
            inventory.write_text("{}")
            args = SimpleNamespace(confirm_target="zhongshu-platform-b/full", writes_frozen=True,
                                   output=root / "backup", env_file=env_file, cos_inventory=inventory)
            with patch.object(backup, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": str(journal), "COS_B_BUCKET": "b-test"}, {})), \
                    patch.object(backup, "run", return_value=json.dumps(model).encode()), \
                    patch.object(backup, "require_frozen", side_effect=lambda base, environment, rendered, values:
                                 gate(hidden, rendered, values)), \
                    patch.object(backup, "pg_dump") as dump:
                with self.assertRaises(ValueError):
                    backup.backup(args)
                dump.assert_not_called()

    def test_independent_attestation_requires_a_real_reviewed_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private_key, public_key = root / "private.pem", root / "public.pem"
            proof, protected = root / "proof.json", root / "protected.txt"
            attestation, signature, trust = root / "attestation.json", root / "attestation.sig", root / "trust.sha256"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                            "-out", str(private_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            proof.write_text('{"volumeId":"volume-b"}\n')
            protected.write_text('/opt/zhongshu\n')
            values = same_host.example_values(root)
            values.update({
                "B_SAME_HOST_STORAGE_ATTESTATION_FILE": str(attestation),
                "B_SAME_HOST_STORAGE_ATTESTATION_SIGNATURE_FILE": str(signature),
                "B_SAME_HOST_APPROVER_PUBLIC_KEY_FILE": str(public_key),
                "B_SAME_HOST_STORAGE_PROOF_FILE": str(proof),
                "B_SAME_HOST_PROTECTED_A_PATHS_FILE": str(protected),
            })
            now = datetime.now(timezone.utc)
            payload = {
                "project": "zhongshu-platform-b", "mountpoint": str(root), "volumeId": "volume-b",
                "provisioning": "thick", "measurementSha256": hashlib.sha256(proof.read_bytes()).hexdigest(),
                "protectedPathsSha256": hashlib.sha256(protected.read_bytes()).hexdigest(),
                "minimumFreeBytes": int(values["B_SAME_HOST_MIN_FREE_BYTES"]), "aScopeComplete": True,
                "physicalVolumeIds": ["B-DISK"], "protectedPhysicalVolumeIds": ["A-DISK"],
                "issuedAt": now.isoformat(), "expiresAt": (now + timedelta(days=1)).isoformat(),
            }
            attestation.write_text(json.dumps(payload))
            subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key), "-out", str(signature),
                            str(attestation)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            trust.write_text(hashlib.sha256(public_key.read_bytes()).hexdigest())
            with patch.object(same_host, "TRUSTED_APPROVER_KEY_HASH_FILE", trust), \
                    patch.object(same_host, "_private_root_file"):
                same_host.validate_attestation(values, {"volumeId": "volume-b"}, proof, protected,
                                               {"B-DISK"}, {"A-DISK"})
                for changed in (
                        {**payload, "minimumFreeBytes": 1},
                        {**payload, "physicalVolumeIds": ["OTHER-B-DISK"]},
                        {**payload, "protectedPathsSha256": "0" * 64},
                ):
                    attestation.write_text(json.dumps(changed))
                    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key),
                                    "-out", str(signature), str(attestation)], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    with self.assertRaises(ValueError):
                        same_host.validate_attestation(values, {"volumeId": "volume-b"}, proof, protected,
                                                       {"B-DISK"}, {"A-DISK"})

    def test_only_sha256_image_references_are_immutable(self):
        self.assertTrue(verify.immutable("registry.example/b@sha256:" + "a" * 64))
        self.assertFalse(verify.immutable("registry.example/b:2026.11.14"))

    def test_env_file_rejects_ambient_override(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "deployment.env"
            path.write_text("DESIGN_PROVIDER_IMAGE=file-value\n")
            with self.assertRaisesRegex(ValueError, "ambient"):
                verify.selected_env(path, {"DESIGN_PROVIDER_IMAGE": "shell-value"})

    def test_provider_runtime_api_and_cos_contracts_must_match(self):
        key = base64.b64encode(b"k" * 32).decode()
        platform = {
            "zhongshu.design.remote.active-key-id": "b-platform",
            "zhongshu.design.remote.caller-app-id": "platform-b",
            "zhongshu.design.remote.keys.b-platform": key,
        }
        provider = {
            "zhongshu.design.asset.storage.provider": "cos",
            "zhongshu.design.asset.storage.cos.bucket": "platform-b",
            "zhongshu.design.asset.storage.cos.endpoint": "https://platform-b.cos.example.test",
            "zhongshu.ai.internal-secret": "s" * 32,
            "zhongshu.design.capability.keys.b-platform.caller-app-id": "platform-b",
            "zhongshu.design.capability.keys.b-platform.secret-base64": key,
        }
        runtime = {"ZS_INTERNAL_SECRET": "s" * 32, "ZS_AI_STORAGE_MODE": "https",
                   "ZS_AI_STORAGE_ORIGINS": "https://platform-b.cos.example.test"}
        verify.validate_configuration_contract(platform, provider, runtime, "platform-b")
        for changed in ({**provider, "zhongshu.design.asset.storage.cos.bucket": "other"},
                        {**provider, "zhongshu.ai.internal-secret": "x" * 32},
                        {**provider, "zhongshu.design.asset.storage.provider": "local"},
                        {**provider, "zhongshu.design.capability.keys.b-platform.secret-base64":
                         base64.b64encode(b"z" * 32).decode()}):
            with self.assertRaises(ValueError):
                verify.validate_configuration_contract(platform, changed, runtime, "platform-b")
        with self.assertRaises(ValueError):
            verify.validate_configuration_contract(platform, provider,
                                                   {**runtime, "ZS_AI_STORAGE_MODE": "local-fs"}, "platform-b")
        with self.assertRaises(ValueError):
            verify.validate_configuration_contract(platform, provider,
                                                   {**runtime, "ZS_AI_STORAGE_ORIGINS": "https://other.example"},
                                                   "platform-b")

    def test_cos_inventory_is_bound_to_bucket_freeze_and_hashes(self):
        valid = {
            "bucket": "platform-b", "generatedAt": "2026-11-14T10:00:05+00:00",
            "versioningEnabled": True,
            "objects": [{"key": "outputs/a", "versionId": "v1", "size": 3, "sha256": "b" * 64}],
        }
        backup.validate_cos_inventory(valid, "platform-b", "2026-11-14T10:00:00+00:00",
                                      "2026-11-14T10:01:00+00:00")
        for field, value in (("bucket", "other"), ("generatedAt", "2026-11-14T09:59:59+00:00")):
            invalid = dict(valid)
            invalid[field] = value
            with self.assertRaises(ValueError):
                backup.validate_cos_inventory(invalid, "platform-b", "2026-11-14T10:00:00+00:00",
                                              "2026-11-14T10:01:00+00:00")
        invalid = {**valid, "objects": [{"key": "outputs/a", "versionId": "v1", "size": 3}]}
        with self.assertRaises(ValueError):
            backup.validate_cos_inventory(invalid, "platform-b", "2026-11-14T10:00:00+00:00",
                                          "2026-11-14T10:01:00+00:00")

    def test_freeze_requires_exact_writers_and_successful_migration(self):
        provider_ref = "registry.example/provider@sha256:" + "c" * 64
        rows = [
            {"Service": service, "ID": service, "State": "exited", "ExitCode": 0,
             "FinishedAt": "2026-11-14T10:00:00Z",
             "ImageRef": (provider_ref if service == "design-provider" else
                          "registry.example/" + service + "@sha256:" + "a" * 64),
             "ImageID": ("sha256:" + ("d" if service == "design-provider" else "a") * 64)}
            for service in ("api", "design-provider", "ai-runtime")
        ]
        rows.append({"Service": "design-provider-migrate", "ID": "migration", "State": "exited",
                     "ExitCode": 0, "FinishedAt": "2026-11-14T09:59:00Z", "ImageRef": provider_ref,
                     "ImageID": "sha256:" + "d" * 64})
        rows.extend({"Service": service, "ID": service, "State": "running", "Health": "healthy"}
                    | {"ImageRef": "registry.example/" + service + "@sha256:" + "b" * 64,
                       "ImageID": "sha256:" + "b" * 64}
                    for service in ("postgres-b", "redis-b", "postgres-design", "redis-design"))
        backup.freeze_evidence(rows)
        with self.assertRaises(ValueError):
            backup.freeze_evidence([row for row in rows if row["Service"] != "ai-runtime"])
        failed = [dict(row) for row in rows]
        next(row for row in failed if row["Service"] == "design-provider-migrate")["ExitCode"] = 1
        with self.assertRaises(ValueError):
            backup.freeze_evidence(failed)

    def test_candidate_container_is_bound_to_digest_and_actual_image_id(self):
        digest = "registry.example/provider@sha256:" + "c" * 64
        container = {"Config": {"Image": digest, "Labels": {
            "com.docker.compose.project": "zhongshu-platform-b",
            "com.docker.compose.service": "design-provider-migrate",
        }}, "Image": "sha256:" + "d" * 64}
        release.validate_candidate_container(container, "design-provider-migrate", digest,
                                             "sha256:" + "d" * 64)
        container["Image"] = "sha256:" + "e" * 64
        with self.assertRaises(ValueError):
            release.validate_candidate_container(container, "design-provider-migrate", digest,
                                                 "sha256:" + "d" * 64)

    def test_failed_up_stops_attempted_service_and_verifies_all_writers(self):
        digest = "registry.example/service@sha256:" + "c" * 64
        image_id = "sha256:" + "d" * 64
        expected = {service: (digest, image_id) for service in ("ai-runtime", "design-provider", "api")}
        calls = []

        def fake_run(command, context, environment, timeout=1800):
            calls.append(command)
            if "up" in command and command[-1] == "design-provider":
                raise RuntimeError("injected up failure")
            return b""

        container = {"Id": "cid", "Config": {"Image": digest, "Labels": {
            "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": "ai-runtime",
        }}, "Image": image_id}
        with patch.object(release, "run", side_effect=fake_run), \
                patch.object(release, "service_container", return_value=container), \
                patch.object(release, "wait_healthy", return_value=container), \
                patch.object(release, "validate_candidate_container"), \
                patch.object(release, "require_writers_stopped") as stopped:
            with self.assertRaisesRegex(RuntimeError, "injected up failure"):
                release.start_writers(["docker", "compose"], expected, {})

        stop = next(command for command in calls if "stop" in command)
        self.assertEqual(stop[-2:], ["ai-runtime", "design-provider"])
        stopped.assert_called_once()

    def test_smoke_failure_never_attempts_api_and_stops_runtime_provider(self):
        digest = "registry.example/service@sha256:" + "c" * 64
        image_id = "sha256:" + "d" * 64
        expected = {service: (digest, image_id) for service in ("ai-runtime", "design-provider", "api")}
        calls = []

        def fake_run(command, context, environment, timeout=1800):
            calls.append(command)
            if "exec" in command and "ai-runtime" in command:
                raise RuntimeError("injected smoke failure")
            return b""

        def container(base, service, environment):
            return {"Id": service, "Config": {"Image": digest, "Labels": {
                "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": service,
            }}, "Image": image_id}

        with patch.object(release, "run", side_effect=fake_run), \
                patch.object(release, "service_container", side_effect=container), \
                patch.object(release, "wait_healthy"), patch.object(release, "require_writers_stopped"):
            with self.assertRaisesRegex(RuntimeError, "injected smoke failure"):
                release.start_writers(["docker", "compose"], expected, {})

        self.assertFalse(any("up" in command and command[-1] == "api" for command in calls))
        stop = next(command for command in calls if "stop" in command)
        self.assertEqual(stop[-2:], ["ai-runtime", "design-provider"])


if __name__ == "__main__":
    unittest.main()
