import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch


HERE = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cbs = load("tencent_cbs_storage", "tencent_cbs_storage.py")


NOW = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)


def disk(disk_id, usage, *, zone="ap-shanghai-2", instance_id="ins-4m1linvl",
         state="ATTACHED", encrypted=True, size_gib=100):
    return {
        "DiskId": disk_id,
        "InstanceId": instance_id,
        "DiskUsage": usage,
        "DiskState": state,
        "Attached": True,
        "Placement": {"Zone": zone},
        "Shareable": False,
        "Encrypt": encrypted,
        "DiskSize": size_gib,
    }


def evidence(*, collected_at=None, disks=None):
    disks = disks or [
        disk("disk-root0001", "SYSTEM_DISK", size_gib=50),
        disk("disk-data0001", "DATA_DISK"),
        disk("disk-backup01", "DATA_DISK", size_gib=200),
    ]
    return {
        "version": 1,
        "provider": "tencent-cbs",
        "region": "ap-shanghai",
        "instanceId": "ins-4m1linvl",
        "accountUin": "100000000001",
        "collectedAt": (collected_at or NOW).isoformat(),
        "responses": {
            "getCallerIdentity": {
                "request": {},
                "response": {"Response": {
                    "AccountId": "100000000001", "UserId": "100000000001",
                    "PrincipalId": "123456789", "Type": "CAMUser", "RequestId": "req-sts",
                }},
            },
            "describeInstances": {
                "request": {"InstanceIds": ["ins-4m1linvl"]},
                "response": {"Response": {
                    "TotalCount": 1, "RequestId": "req-cvm",
                    "InstanceSet": [{
                        "InstanceId": "ins-4m1linvl", "InstanceState": "RUNNING",
                        "Placement": {"Zone": "ap-shanghai-2"},
                        "SystemDisk": {"DiskId": "disk-root0001"},
                    }],
                }},
            },
            "describeDisks": [{
                "request": {
                    "Filters": [{"Name": "instance-id", "Values": ["ins-4m1linvl"]}],
                    "Offset": 0, "Limit": 100,
                },
                "response": {"Response": {
                    "TotalCount": len(disks), "DiskSet": disks, "RequestId": "req-cbs-0",
                }},
            }],
        },
    }


def binding(target_id="disk-data0001", *, target_major="8:17", target_uuid="uuid-data",
            protected_stable="disk-root0001"):
    return {
        "bootId": "11111111-2222-3333-4444-555555555555",
        "metadata": {
            "instanceId": "ins-4m1linvl", "region": "ap-shanghai", "zone": "ap-shanghai-2",
        },
        "root": {
            "source": "/dev/vda1", "uuid": "uuid-root", "majorMinor": "8:1",
            "leafMajorMinor": "8:0", "stableId": "disk-root0001", "sizeBytes": 50 * 1024**3,
            "ancestry": ["8:0", "8:1"],
        },
        "target": {
            "source": "/dev/vdb1", "uuid": target_uuid, "majorMinor": target_major,
            "leafMajorMinor": "8:16", "stableId": target_id, "sizeBytes": 100 * 1024**3,
            "ancestry": ["8:16", target_major],
        },
        "protectedMounts": [{
            "path": "/opt/zhongshu", "source": "/dev/vda1", "uuid": "uuid-root",
            "majorMinor": "8:1", "leafMajorMinor": "8:0", "stableId": protected_stable,
            "sizeBytes": 50 * 1024**3, "ancestry": ["8:0", "8:1"],
        }],
    }


class TencentCbsStorageTest(unittest.TestCase):
    def test_metadata_reader_uses_only_fixed_official_paths_without_proxy_or_redirect(self):
        requested = []

        class Response:
            status = 200

            def __init__(self, request):
                self.request = request

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def geturl(self):
                return self.request.full_url

            def read(self, limit):
                self.assert_limit = limit
                values = {"instance-id": "ins-4m1linvl", "placement/region": "ap-shanghai",
                          "placement/zone": "ap-shanghai-2"}
                return values[self.request.full_url.split("meta-data/", 1)[1]].encode()

        class Opener:
            def open(self, request, timeout):
                requested.append((request.full_url, timeout))
                return Response(request)

        with patch.object(cbs.urllib.request, "build_opener", return_value=Opener()) as build:
            self.assertEqual(cbs.read_live_metadata(timeout=1), {
                "instanceId": "ins-4m1linvl", "region": "ap-shanghai", "zone": "ap-shanghai-2",
            })
        self.assertEqual([url for url, _ in requested], [
            cbs.METADATA_BASE + "instance-id", cbs.METADATA_BASE + "placement/region",
            cbs.METADATA_BASE + "placement/zone",
        ])
        proxy = build.call_args.args[0]
        self.assertEqual(proxy.proxies, {})

    def test_mode_is_explicit_and_legacy_default_never_reads_cbs_evidence(self):
        self.assertEqual(cbs.identity_mode({}), "serial-wwn-thick-v1")
        self.assertEqual(cbs.identity_mode({"B_SAME_HOST_STORAGE_IDENTITY_MODE": "tencent-cbs-v1"}),
                         "tencent-cbs-v1")
        with self.assertRaises(ValueError):
            cbs.identity_mode({"B_SAME_HOST_STORAGE_IDENTITY_MODE": "automatic"})

    def test_strict_json_rejects_duplicates_constants_bool_numbers_and_oversize(self):
        with self.assertRaises(ValueError):
            cbs.strict_json_bytes(b'{"a":1,"a":2}')
        with self.assertRaises(ValueError):
            cbs.strict_json_bytes(b'{"a":NaN}')
        with self.assertRaises(ValueError):
            cbs.require_integer(True, "count")
        with self.assertRaises(ValueError):
            cbs.strict_json_bytes(b"{}" + b" " * (cbs.MAX_EVIDENCE_BYTES + 1))

    def test_evidence_file_is_hash_bound_private_bounded_and_fresh(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "evidence.json"
            path.write_text(json.dumps(evidence()))
            path.chmod(0o600)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            contract = cbs.load_cloud_evidence(path, digest, now=NOW, expected_uid=os.geteuid())
            self.assertEqual(contract["instanceId"], "ins-4m1linvl")
            with self.assertRaises(ValueError):
                cbs.load_cloud_evidence(path, "0" * 64, now=NOW, expected_uid=os.geteuid())
            path.chmod(0o620)
            with self.assertRaises(ValueError):
                cbs.load_cloud_evidence(path, digest, now=NOW, expected_uid=os.geteuid())

    def test_evidence_stale_future_account_instance_and_state_are_rejected(self):
        for changed in (
                {**evidence(), "collectedAt": (NOW - timedelta(hours=1, seconds=1)).isoformat()},
                {**evidence(), "collectedAt": (NOW + timedelta(minutes=5, seconds=1)).isoformat()},
        ):
            with self.assertRaises(ValueError):
                cbs.validate_cloud_evidence(changed, now=NOW)
        changed = evidence()
        changed["responses"]["getCallerIdentity"]["response"]["Response"]["AccountId"] = "other"
        with self.assertRaises(ValueError):
            cbs.validate_cloud_evidence(changed, now=NOW)
        changed = evidence()
        changed["version"] = True
        with self.assertRaises(ValueError):
            cbs.validate_cloud_evidence(changed, now=NOW)
        changed = evidence()
        changed["responses"]["describeInstances"]["response"]["Response"]["InstanceSet"][0]["InstanceState"] = "STOPPED"
        with self.assertRaises(ValueError):
            cbs.validate_cloud_evidence(changed, now=NOW)

    def test_describe_disks_requires_contiguous_pages_request_ids_and_total_count(self):
        value = evidence(disks=[disk(f"disk-{index:06d}", "DATA_DISK") for index in range(101)])
        value["responses"]["describeInstances"]["response"]["Response"]["InstanceSet"][0]["SystemDisk"]["DiskId"] = "disk-000000"
        pages = value["responses"]["describeDisks"]
        all_disks = pages[0]["response"]["Response"]["DiskSet"]
        all_disks[0]["DiskUsage"] = "SYSTEM_DISK"
        pages[:] = [
            {"request": {"Filters": [{"Name": "instance-id", "Values": ["ins-4m1linvl"]}],
                         "Offset": 0, "Limit": 100},
             "response": {"Response": {"TotalCount": 101, "DiskSet": all_disks[:100],
                                         "RequestId": "page-1"}}},
            {"request": {"Filters": [{"Name": "instance-id", "Values": ["ins-4m1linvl"]}],
                         "Offset": 100, "Limit": 100},
             "response": {"Response": {"TotalCount": 101, "DiskSet": all_disks[100:],
                                         "RequestId": "page-2"}}},
        ]
        self.assertEqual(len(cbs.validate_cloud_evidence(value, now=NOW)["disks"]), 101)
        extra = json.loads(json.dumps(pages[-1]))
        extra["request"]["Offset"] = 101
        extra["response"]["Response"].update(DiskSet=[], RequestId="empty-tail")
        candidate = json.loads(json.dumps(value))
        candidate["responses"]["describeDisks"].append(extra)
        with self.assertRaises(ValueError):
            cbs.validate_cloud_evidence(candidate, now=NOW)
        for mutation in ("offset", "duplicate_disk", "count", "request_id"):
            candidate = json.loads(json.dumps(value))
            if mutation == "offset":
                candidate["responses"]["describeDisks"][1]["request"]["Offset"] = 99
            elif mutation == "duplicate_disk":
                candidate["responses"]["describeDisks"][1]["response"]["Response"]["DiskSet"][0] = all_disks[0]
            elif mutation == "count":
                candidate["responses"]["describeDisks"][1]["response"]["Response"]["TotalCount"] = 102
            else:
                candidate["responses"]["describeDisks"][1]["response"]["Response"]["RequestId"] = "page-1"
            with self.assertRaises(ValueError, msg=mutation):
                cbs.validate_cloud_evidence(candidate, now=NOW)

    def test_guest_binding_requires_exact_cloud_ids_and_disjoint_devices(self):
        cloud = cbs.validate_cloud_evidence(evidence(), now=NOW)
        result = cbs.validate_guest_binding(binding(), cloud, "disk-data0001", ["disk-root0001"])
        self.assertEqual(result["target"]["stableId"], "disk-data0001")
        for changed in (
                binding(target_id="disk-other"),
                binding(target_major="8:1"),
                binding(target_uuid="uuid-root"),
                binding(protected_stable=""),
        ):
            with self.assertRaises(ValueError):
                cbs.validate_guest_binding(changed, cloud, "disk-data0001", ["disk-root0001"])
        wrong_size = binding()
        wrong_size["target"]["sizeBytes"] -= 1
        with self.assertRaises(ValueError):
            cbs.validate_guest_binding(wrong_size, cloud, "disk-data0001", ["disk-root0001"])

    def test_root_missing_stable_id_is_only_allowed_for_unique_system_disk(self):
        cloud = cbs.validate_cloud_evidence(evidence(), now=NOW)
        value = binding()
        value["root"]["stableId"] = ""
        value["protectedMounts"][0]["stableId"] = "disk-root0001"
        cbs.validate_guest_binding(value, cloud, "disk-data0001", ["disk-root0001"])
        value["root"]["leafMajorMinor"] = ""
        with self.assertRaises(ValueError):
            cbs.validate_guest_binding(value, cloud, "disk-data0001", ["disk-root0001"])

    def test_v2_proof_and_attestation_bind_cloud_and_guest_hashes(self):
        cloud = cbs.validate_cloud_evidence(evidence(), now=NOW)
        guest = binding()
        evidence_sha = "e" * 64
        proof = {
            "version": 2, "identityMode": "tencent-cbs-v1", "allocationContract": "managed-block-v1",
            "identityEvidenceSha256": evidence_sha, "volumeId": "disk-data0001",
            "mountpoint": "/mnt/data", "filesystem": "ext4", "fsyncP99Ms": 1.5,
            "capacityBytes": 100, "requiredFreeBytes": 10, "measuredAt": NOW.isoformat(),
            "guestBinding": guest, "runtimeProofSha256": "d" * 64,
        }
        cbs.validate_cbs_proof(proof, cloud, evidence_sha, "/mnt/data", "ext4", 100, 10,
                               guest, now=NOW)
        attestation = {
            "project": "zhongshu-platform-b", "mountpoint": "/mnt/data", "volumeId": "disk-data0001",
            "identityMode": "tencent-cbs-v1", "allocationContract": "managed-block-v1",
            "identityEvidenceSha256": evidence_sha, "accountUin": cloud["accountUin"],
            "region": cloud["region"], "zone": cloud["zone"], "instanceId": cloud["instanceId"],
            "logicalVolumeIds": ["disk-data0001"], "protectedLogicalVolumeIds": ["disk-root0001"],
            "guestBindingSha256": cbs.canonical_sha256(guest), "encrypted": True,
            "measurementSha256": "m" * 64, "protectedPathsSha256": "p" * 64,
            "minimumFreeBytes": 10, "aScopeComplete": True,
            "runtimeProofSha256": "d" * 64, "issuedAt": NOW.isoformat(),
        }
        cbs.validate_cbs_attestation_fields(attestation, proof, cloud, "m" * 64, "p" * 64,
                                            10, ["disk-root0001"])
        for key, replacement in (("allocationContract", "thick"),
                                 ("guestBindingSha256", "0" * 64),
                                 ("encrypted", False), ("encrypted", 1), ("aScopeComplete", 1)):
            changed = {**attestation, key: replacement}
            with self.assertRaises(ValueError):
                cbs.validate_cbs_attestation_fields(changed, proof, cloud, "m" * 64,
                                                    "p" * 64, 10, ["disk-root0001"])
        too_old = {**attestation, "issuedAt": (NOW - timedelta(seconds=1)).isoformat()}
        with self.assertRaises(ValueError):
            cbs.validate_cbs_attestation_fields(too_old, proof, cloud, "m" * 64,
                                                "p" * 64, 10, ["disk-root0001"])
        for changed in ({**proof, "version": True}, {**proof, "capacityBytes": True},
                        {**proof, "fsyncP99Ms": float("inf")}):
            with self.assertRaises(ValueError):
                cbs.validate_cbs_proof(changed, cloud, evidence_sha, "/mnt/data", "ext4",
                                       100, 10, guest, now=NOW)

    def test_unencrypted_existing_disk_is_bound_truthfully_without_kms_requirement(self):
        value = evidence()
        value["responses"]["describeDisks"][0]["response"]["Response"]["DiskSet"][1]["Encrypt"] = False
        cloud = cbs.validate_cloud_evidence(value, now=NOW)
        guest = binding()
        proof = {
            "version": 2, "identityMode": "tencent-cbs-v1", "allocationContract": "managed-block-v1",
            "identityEvidenceSha256": "e" * 64, "volumeId": "disk-data0001",
            "mountpoint": "/mnt/data", "filesystem": "ext4", "fsyncP99Ms": 1.0,
            "capacityBytes": 100, "requiredFreeBytes": 10, "measuredAt": NOW.isoformat(),
            "guestBinding": guest, "runtimeProofSha256": "d" * 64,
        }
        cbs.validate_cbs_proof(proof, cloud, "e" * 64, "/mnt/data", "ext4", 100, 10,
                               guest, now=NOW)
        attestation = {
            "project": "zhongshu-platform-b", "mountpoint": "/mnt/data",
            "volumeId": "disk-data0001", "identityMode": "tencent-cbs-v1",
            "allocationContract": "managed-block-v1", "identityEvidenceSha256": "e" * 64,
            "accountUin": cloud["accountUin"], "region": cloud["region"], "zone": cloud["zone"],
            "instanceId": cloud["instanceId"], "logicalVolumeIds": ["disk-data0001"],
            "protectedLogicalVolumeIds": ["disk-root0001"],
            "guestBindingSha256": cbs.canonical_sha256(guest), "encrypted": False,
            "measurementSha256": "m" * 64, "protectedPathsSha256": "p" * 64,
            "minimumFreeBytes": 10, "aScopeComplete": True,
            "runtimeProofSha256": "d" * 64, "issuedAt": NOW.isoformat(),
        }
        cbs.validate_cbs_attestation_fields(attestation, proof, cloud, "m" * 64, "p" * 64,
                                            10, ["disk-root0001"])


if __name__ == "__main__":
    unittest.main()
