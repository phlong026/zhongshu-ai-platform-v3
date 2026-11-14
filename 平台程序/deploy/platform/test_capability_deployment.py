import importlib.util
import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verify = load("verify_capability", "verify-capability-deployment.py")
backup = load("full_backup", "full-backup.py")
release = load("release_capabilities", "release-capabilities.py")


class CapabilityDeploymentTest(unittest.TestCase):
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
