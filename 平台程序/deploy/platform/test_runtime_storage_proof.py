import hashlib
import json
import unittest
import tempfile
from pathlib import Path
import same_host_storage
from runtime_storage_proof import validate_runtime_proof


class RuntimeStorageProofTest(unittest.TestCase):
    def setUp(self):
        self.value = dict(version=2, project="zhongshu-platform-b", storageRole="runtime-journal",
                          identityMode="tencent-cbs-v1", allocationContract="managed-block-v1",
                          accountUin="100045498012", region="ap-shanghai", zone="ap-shanghai-5",
                          instanceId="ins-fixture", volumeId="disk-fixture", filesystem="ext4",
                          filesystemUuid="e07286c2-5dc1-421f-9cd3-6840a95dadc3", exclusiveWriter=True)
        self.payload = json.dumps(self.value, separators=(",", ":")).encode()
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.binding = {**self.value, "runtimeProofSha256": self.digest}

    def test_stable_proof_bound_to_current_verified_identity(self):
        self.assertEqual(validate_runtime_proof(self.payload, self.digest, "tencent-cbs-v1", self.binding), self.value)
        for field in ("accountUin", "region", "zone", "instanceId", "volumeId", "filesystem", "filesystemUuid", "runtimeProofSha256"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_runtime_proof(self.payload, self.digest, "tencent-cbs-v1", {**self.binding, field: "wrong"})

    def test_mixed_modes_missing_signature_and_fresh_fields_fail_closed(self):
        for mode, binding in (("serial-wwn-thick-v1", self.binding), ("unknown", self.binding), ("tencent-cbs-v1", None)):
            with self.assertRaises(ValueError):
                validate_runtime_proof(self.payload, self.digest, mode, binding)
        for extra in ({"collectedAt": "now"}, {"identityEvidenceSha256": "a" * 64}, {"version": True}):
            payload = json.dumps({**self.value, **extra}, separators=(",", ":")).encode()
            with self.assertRaises(ValueError):
                validate_runtime_proof(payload, hashlib.sha256(payload).hexdigest(), "tencent-cbs-v1", self.binding)

    def test_duplicate_keys_and_whitespace_are_rejected(self):
        for payload in (self.payload + b"\n", self.payload.replace(b'"version":2', b'"version":1,"version":2')):
            with self.assertRaises(ValueError):
                validate_runtime_proof(payload, hashlib.sha256(payload).hexdigest(), "tencent-cbs-v1", self.binding)

    def test_readonly_inspection_does_not_require_unsigned_release_placeholders(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = str(Path(temporary).resolve())
            values = {"B_SAME_HOST_STORAGE_IDENTITY_MODE": "tencent-cbs-v1",
                      "B_SAME_HOST_MOUNT": directory,
                      "B_SAME_HOST_PROTECTED_A_PATHS_FILE": directory + "/protected.txt"}
            with self.assertRaisesRegex(ValueError, "actual mount point"):
                same_host_storage.inspect_cbs_host(values)

    def test_legacy_remains_supported_only_in_legacy_mode(self):
        payload = b'{"version":1,"filesystem":"ext4","allocation":"thick","exclusiveWriter":true,"volumeId":"fixture"}'
        digest = hashlib.sha256(payload).hexdigest()
        self.assertEqual(validate_runtime_proof(payload, digest, "serial-wwn-thick-v1")["version"], 1)
        with self.assertRaises(ValueError):
            validate_runtime_proof(payload, digest, "tencent-cbs-v1", self.binding)


if __name__ == "__main__":
    unittest.main()
