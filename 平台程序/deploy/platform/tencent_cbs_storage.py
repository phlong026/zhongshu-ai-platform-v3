#!/usr/bin/env python3
"""Strict offline validation for Tencent CBS-backed same-host storage evidence."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone


IDENTITY_MODE = "tencent-cbs-v1"
LEGACY_IDENTITY_MODE = "serial-wwn-thick-v1"
MAX_EVIDENCE_BYTES = 1024 * 1024
MAX_EVIDENCE_AGE = timedelta(hours=1)
MAX_FUTURE_SKEW = timedelta(minutes=5)
MAX_PROOF_AGE = timedelta(hours=24)
METADATA_BASE = "http://metadata.tencentyun.com/latest/meta-data/"
METADATA_PATHS = {
    "instanceId": "instance-id",
    "region": "placement/region",
    "zone": "placement/zone",
}


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def identity_mode(values):
    mode = values.get("B_SAME_HOST_STORAGE_IDENTITY_MODE", LEGACY_IDENTITY_MODE)
    demand(mode in (LEGACY_IDENTITY_MODE, IDENTITY_MODE), "Unknown same-host storage identity mode")
    return mode


def _pairs_without_duplicates(pairs):
    result = {}
    for key, value in pairs:
        demand(key not in result, "Cloud evidence JSON contains a duplicate object key")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError(f"Cloud evidence JSON contains forbidden numeric constant {value}")


def strict_json_bytes(payload):
    demand(isinstance(payload, bytes) and 0 < len(payload) <= MAX_EVIDENCE_BYTES,
           "Cloud evidence file must be non-empty and no larger than 1 MiB")
    try:
        value = json.loads(payload.decode("utf-8"), object_pairs_hook=_pairs_without_duplicates,
                           parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Cloud evidence is not strict UTF-8 JSON") from error
    demand(isinstance(value, dict), "Cloud evidence root must be an object")
    return value


def require_integer(value, label, minimum=0):
    demand(isinstance(value, int) and not isinstance(value, bool) and value >= minimum,
           f"{label} must be an integer of at least {minimum}")
    return value


def _required_string(value, label, pattern=None):
    demand(isinstance(value, str) and value.strip() == value and value,
           f"{label} must be a non-empty string")
    if pattern:
        demand(re.fullmatch(pattern, value), f"{label} has an invalid format")
    return value


def _required_object(value, label):
    demand(isinstance(value, dict), f"{label} must be an object")
    return value


def _required_list(value, label):
    demand(isinstance(value, list), f"{label} must be an array")
    return value


def _timestamp(value, label):
    try:
        parsed = datetime.fromisoformat(_required_string(value, label).replace("Z", "+00:00"))
    except (TypeError, ValueError) as error:
        raise ValueError(f"{label} must be an ISO-8601 timestamp") from error
    demand(parsed.tzinfo is not None, f"{label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def canonical_sha256(value):
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                         allow_nan=False).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def read_live_metadata(timeout=2):
    demand(isinstance(timeout, (int, float)) and 0 < timeout <= 5,
           "Metadata timeout must be positive and no more than five seconds")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    result = {}
    for key, relative_path in METADATA_PATHS.items():
        request = urllib.request.Request(METADATA_BASE + relative_path,
                                         headers={"Accept": "text/plain"}, method="GET")
        try:
            with opener.open(request, timeout=timeout) as response:
                demand(response.status == 200 and response.geturl() == request.full_url,
                       "Tencent metadata response was redirected or unsuccessful")
                payload = response.read(257)
        except (OSError, urllib.error.URLError) as error:
            raise ValueError("Tencent metadata lookup failed") from error
        demand(0 < len(payload) <= 256, "Tencent metadata response has an invalid size")
        result[key] = _required_string(payload.decode("utf-8").strip(), f"metadata {key}")
    return result


def _flatten_topology(devices, parent=None, nodes=None, parents=None):
    nodes = {} if nodes is None else nodes
    parents = {} if parents is None else parents
    for item in devices:
        identity = item.get("maj:min")
        if identity:
            nodes[identity] = item
            if parent:
                parents.setdefault(identity, set()).add(parent)
        _flatten_topology(item.get("children", []), identity, nodes, parents)
    names = {item.get("kname") or item.get("name"): identity for identity, item in nodes.items()}
    for identity, item in nodes.items():
        if item.get("pkname") in names:
            parents.setdefault(identity, set()).add(names[item["pkname"]])
    return nodes, parents


def _device_from_topology(mount_identity, nodes, parents, label, stable_required):
    identity = mount_identity.get("maj:min")
    demand(identity in nodes, f"{label} is absent from lsblk topology")
    ancestry = set()
    pending = [identity]
    while pending:
        current = pending.pop()
        if current in ancestry:
            continue
        ancestry.add(current)
        pending.extend(parents.get(current, ()))
    leaves = [item for item in ancestry if not parents.get(item)]
    demand(len(leaves) == 1, f"{label} must have exactly one block-device leaf")
    leaf_identity = leaves[0]
    leaf = nodes[leaf_identity]
    stable_id = str(leaf.get("wwn") or leaf.get("serial") or "").strip()
    if stable_required:
        _required_string(stable_id, f"{label} stable ID", r"disk-[A-Za-z0-9]{6,64}")
    size = leaf.get("size")
    if isinstance(size, str) and size.isdigit():
        size = int(size)
    require_integer(size, f"{label} sizeBytes", minimum=1)
    return {
        "source": _required_string(mount_identity.get("source"), f"{label} source"),
        "uuid": _required_string(mount_identity.get("uuid"), f"{label} UUID"),
        "majorMinor": identity, "leafMajorMinor": leaf_identity,
        "stableId": stable_id, "sizeBytes": size, "ancestry": sorted(ancestry),
    }


def collect_live_guest_binding(target_identity, root_identity, protected_identities, cloud,
                               metadata_reader=read_live_metadata,
                               boot_id_file=Path("/proc/sys/kernel/random/boot_id")):
    metadata = metadata_reader()
    demand(metadata == {"instanceId": cloud["instanceId"], "region": cloud["region"],
                        "zone": cloud["zone"]},
           "Live Tencent metadata differs from cloud evidence")
    result = subprocess.run(
        ["lsblk", "-b", "-J", "-o", "NAME,KNAME,TYPE,PKNAME,MAJ:MIN,SERIAL,WWN,SIZE"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30, check=False)
    demand(result.returncode == 0, "Block-device ancestry discovery failed")
    try:
        topology = json.loads(result.stdout).get("blockdevices", [])
    except json.JSONDecodeError as error:
        raise ValueError("lsblk returned invalid JSON") from error
    nodes, parents = _flatten_topology(topology)
    target = _device_from_topology(target_identity, nodes, parents, "target mount", True)
    root = _device_from_topology(root_identity, nodes, parents, "root mount", False)
    protected = []
    for path, identity in protected_identities:
        item = _device_from_topology(identity, nodes, parents, f"protected mount {path}", False)
        if not item["stableId"] and set(item["ancestry"]) == set(root["ancestry"]):
            item["stableId"] = cloud["systemDiskId"]
        _required_string(item["stableId"], f"protected mount {path} stable ID",
                         r"disk-[A-Za-z0-9]{6,64}")
        protected.append({"path": str(path), **item})
    protected.sort(key=lambda item: (item["path"], item["majorMinor"]))
    boot_path = Path(boot_id_file)
    demand(boot_path.is_file() and not boot_path.is_symlink(), "Linux boot ID is unavailable")
    boot_id = _required_string(boot_path.read_text().strip(), "Linux boot ID",
                               r"[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,36}")
    return {"bootId": boot_id, "metadata": metadata, "root": root, "target": target,
            "protectedMounts": protected}


def _private_bounded_file(path, expected_uid):
    path = Path(path)
    demand(path.is_absolute(), "Cloud evidence file must use an absolute path")
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        demand(not current.is_symlink(), "Cloud evidence path must not contain symlinks")
    metadata = path.stat()
    demand(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == expected_uid
           and not (metadata.st_mode & 0o022),
           "Cloud evidence must be a private regular file owned by root")
    demand(0 < metadata.st_size <= MAX_EVIDENCE_BYTES,
           "Cloud evidence file must be non-empty and no larger than 1 MiB")
    return metadata


def load_cloud_evidence(path, expected_sha256, now=None, expected_uid=0):
    demand(re.fullmatch(r"[0-9a-f]{64}", str(expected_sha256 or "")),
           "Cloud evidence SHA-256 is required")
    before = _private_bounded_file(path, expected_uid)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        demand((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
               == (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns),
               "Cloud evidence changed before it was opened")
        chunks = []
        remaining = MAX_EVIDENCE_BYTES + 1
        while remaining:
            chunk = os.read(descriptor, remaining)
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        demand(len(payload) == opened.st_size, "Cloud evidence changed while it was read")
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    demand((opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns)
           == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
           "Cloud evidence changed while it was read")
    digest = hashlib.sha256(payload).hexdigest()
    demand(digest == expected_sha256, "Cloud evidence SHA-256 mismatch")
    result = validate_cloud_evidence(strict_json_bytes(payload), now=now)
    result["identityEvidenceSha256"] = digest
    return result


def _response(call, label):
    call = _required_object(call, label)
    _required_object(call.get("request"), f"{label} request")
    envelope = _required_object(call.get("response"), f"{label} response")
    response = _required_object(envelope.get("Response"), f"{label} Response")
    _required_string(response.get("RequestId"), f"{label} RequestId")
    demand("Error" not in response, f"{label} contains a cloud API error")
    return call["request"], response


def _instance_contract(evidence, responses):
    request, response = _response(responses.get("describeInstances"), "DescribeInstances")
    instance_id = _required_string(evidence.get("instanceId"), "instanceId", r"ins-[A-Za-z0-9]{6,64}")
    demand(request == {"InstanceIds": [instance_id]},
           "DescribeInstances request must select exactly the evidence instance")
    require_integer(response.get("TotalCount"), "DescribeInstances TotalCount")
    instances = _required_list(response.get("InstanceSet"), "DescribeInstances InstanceSet")
    demand(response["TotalCount"] == 1 and len(instances) == 1,
           "DescribeInstances must return exactly one instance")
    instance = _required_object(instances[0], "DescribeInstances instance")
    demand(instance.get("InstanceId") == instance_id and instance.get("InstanceState") == "RUNNING",
           "Cloud instance identity or state does not match the reviewed running instance")
    placement = _required_object(instance.get("Placement"), "CVM Placement")
    zone = _required_string(placement.get("Zone"), "CVM zone", r"[a-z][a-z0-9-]{1,63}")
    system_disk = _required_object(instance.get("SystemDisk"), "CVM SystemDisk")
    system_disk_id = _required_string(system_disk.get("DiskId"), "CVM system DiskId", r"disk-[A-Za-z0-9]{6,64}")
    return instance, zone, system_disk_id


def _disk_pages(evidence, responses, zone):
    pages = _required_list(responses.get("describeDisks"), "DescribeDisks pages")
    demand(pages, "DescribeDisks requires at least one page")
    instance_id = evidence["instanceId"]
    disks = []
    disk_ids = set()
    request_ids = set()
    total_count = None
    expected_offset = 0
    for index, call in enumerate(pages):
        demand(total_count is None or expected_offset < total_count,
               "DescribeDisks includes a page after TotalCount was reached")
        request, response = _response(call, f"DescribeDisks page {index}")
        demand(set(request) == {"Filters", "Offset", "Limit"},
               "DescribeDisks request contains an unreviewed parameter")
        expected_filter = [{"Name": "instance-id", "Values": [instance_id]}]
        demand(request.get("Filters") == expected_filter,
               "DescribeDisks must use the exact instance-id filter")
        offset = require_integer(request.get("Offset"), "DescribeDisks Offset")
        limit = require_integer(request.get("Limit"), "DescribeDisks Limit", minimum=1)
        demand(offset == expected_offset and limit <= 100,
               "DescribeDisks pages must be contiguous and bounded to 100 records")
        current_total = require_integer(response.get("TotalCount"), "DescribeDisks TotalCount")
        total_count = current_total if total_count is None else total_count
        demand(current_total == total_count, "DescribeDisks TotalCount changed between pages")
        request_id = response["RequestId"]
        demand(request_id not in request_ids, "DescribeDisks RequestId is duplicated")
        request_ids.add(request_id)
        page_disks = _required_list(response.get("DiskSet"), "DescribeDisks DiskSet")
        demand(len(page_disks) <= limit, "DescribeDisks returned more disks than requested")
        demand(page_disks or expected_offset == total_count,
               "DescribeDisks returned an empty page before TotalCount")
        for raw_disk in page_disks:
            item = _required_object(raw_disk, "CBS disk")
            disk_id = _required_string(item.get("DiskId"), "CBS DiskId", r"disk-[A-Za-z0-9]{6,64}")
            demand(disk_id not in disk_ids, "DescribeDisks returned a duplicate DiskId")
            disk_ids.add(disk_id)
            demand(item.get("InstanceId") == instance_id and item.get("DiskState") == "ATTACHED"
                   and item.get("Attached") is True and item.get("Shareable") is False
                   and item.get("DiskUsage") in ("SYSTEM_DISK", "DATA_DISK"),
                   "CBS disk is not an attached system/data disk of the reviewed instance")
            placement = _required_object(item.get("Placement"), "CBS Placement")
            demand(placement.get("Zone") == zone, "CBS disk zone differs from the reviewed CVM zone")
            demand(isinstance(item.get("Encrypt"), bool), "CBS Encrypt must be a JSON boolean")
            require_integer(item.get("DiskSize"), "CBS DiskSize GiB", minimum=1)
            disks.append(item)
        expected_offset += len(page_disks)
        demand(expected_offset <= total_count, "DescribeDisks returned more disks than TotalCount")
    demand(expected_offset == total_count, "DescribeDisks pages did not reach TotalCount")
    return disks


def validate_cloud_evidence(evidence, now=None):
    evidence = _required_object(evidence, "Cloud evidence")
    demand(type(evidence.get("version")) is int and evidence["version"] == 1
           and evidence.get("provider") == "tencent-cbs",
           "Cloud evidence version/provider is unsupported")
    region = _required_string(evidence.get("region"), "region", r"[a-z][a-z0-9-]{1,31}")
    account_uin = _required_string(evidence.get("accountUin"), "accountUin", r"[0-9]{5,32}")
    collected_at = _timestamp(evidence.get("collectedAt"), "collectedAt")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    demand(current - MAX_EVIDENCE_AGE <= collected_at <= current + MAX_FUTURE_SKEW,
           "Cloud evidence is stale or future-dated")
    responses = _required_object(evidence.get("responses"), "responses")
    identity_request, identity = _response(responses.get("getCallerIdentity"), "GetCallerIdentity")
    demand(identity_request == {}, "GetCallerIdentity request must be empty")
    demand(identity.get("AccountId") == account_uin,
           "GetCallerIdentity account does not match evidence accountUin")
    for key in ("UserId", "PrincipalId", "Type"):
        _required_string(identity.get(key), f"GetCallerIdentity {key}")
    instance, zone, system_disk_id = _instance_contract(evidence, responses)
    disks = _disk_pages(evidence, responses, zone)
    system_disks = [item for item in disks if item["DiskUsage"] == "SYSTEM_DISK"]
    demand(len(system_disks) == 1 and system_disks[0]["DiskId"] == system_disk_id,
           "CVM SystemDisk must match the unique CBS SYSTEM_DISK")
    return {
        "accountUin": account_uin, "region": region, "zone": zone,
        "instanceId": evidence["instanceId"], "collectedAt": collected_at,
        "instance": instance, "systemDiskId": system_disk_id, "disks": disks,
        "disksById": {item["DiskId"]: item for item in disks},
    }


def _binding_device(value, label, stable_required=True):
    item = _required_object(value, label)
    for key in ("source", "uuid", "majorMinor", "leafMajorMinor"):
        _required_string(item.get(key), f"{label} {key}")
    stable_id = item.get("stableId", "")
    demand(isinstance(stable_id, str), f"{label} stableId must be a string")
    if stable_required:
        _required_string(stable_id, f"{label} stableId", r"disk-[A-Za-z0-9]{6,64}")
    require_integer(item.get("sizeBytes"), f"{label} sizeBytes", minimum=1)
    ancestry = _required_list(item.get("ancestry"), f"{label} ancestry")
    demand(ancestry and all(isinstance(identity, str) and identity for identity in ancestry)
           and len(ancestry) == len(set(ancestry))
           and item["majorMinor"] in ancestry and item["leafMajorMinor"] in ancestry,
           f"{label} ancestry is incomplete or duplicated")
    return item


def _disjoint(left, right):
    return (left["uuid"] != right["uuid"] and left["majorMinor"] != right["majorMinor"]
            and left["leafMajorMinor"] != right["leafMajorMinor"]
            and set(left["ancestry"]).isdisjoint(right["ancestry"]))


def validate_guest_binding(binding, cloud, volume_id, protected_logical_ids):
    binding = _required_object(binding, "guestBinding")
    _required_string(binding.get("bootId"), "guestBinding bootId",
                     r"[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,36}")
    metadata = _required_object(binding.get("metadata"), "guestBinding metadata")
    demand(metadata == {"instanceId": cloud["instanceId"], "region": cloud["region"],
                        "zone": cloud["zone"]},
           "Live metadata does not match the signed cloud evidence")
    target = _binding_device(binding.get("target"), "guestBinding target")
    root = _binding_device(binding.get("root"), "guestBinding root", stable_required=False)
    protected = [_binding_device(item, "guestBinding protected mount")
                 for item in _required_list(binding.get("protectedMounts"),
                                            "guestBinding protectedMounts")]
    disk = cloud["disksById"].get(volume_id)
    demand(disk is not None and disk["DiskUsage"] == "DATA_DISK",
           "Target volume is not an attached CBS data disk")
    demand(target["stableId"] == volume_id, "Target guest stable ID does not equal CBS DiskId")
    demand(target["sizeBytes"] == disk["DiskSize"] * 1024**3,
           "Target guest leaf size differs from CBS DiskSize")
    demand(_disjoint(target, root) and all(_disjoint(target, item) for item in protected),
           "Target CBS disk overlaps root or a protected mount")
    system_disk_id = cloud["systemDiskId"]
    demand(root["sizeBytes"] == cloud["disksById"][system_disk_id]["DiskSize"] * 1024**3,
           "Root guest leaf size differs from the CBS system DiskSize")
    demand(not root["stableId"] or root["stableId"] == system_disk_id,
           "Root stable ID differs from the CVM system DiskId")
    expected = set(protected_logical_ids)
    actual = {item["stableId"] for item in protected}
    demand("" not in actual and actual == expected,
           "Protected mounts must bind every reviewed logical CBS DiskId exactly")
    demand(system_disk_id in expected,
           "Protected logical disk scope must include the CVM system disk")
    demand(volume_id not in expected and volume_id != system_disk_id,
           "Target, root and protected logical disk identities must be distinct")
    demand(expected <= set(cloud["disksById"]),
           "Protected logical disk identity is absent from cloud evidence")
    return binding


def validate_cbs_proof(proof, cloud, evidence_sha256, mountpoint, filesystem,
                       capacity_bytes, required_free_bytes, live_guest_binding, now=None,
                       storage_role="data", maximum_backup_bytes=None):
    proof = _required_object(proof, "Storage proof")
    measured_at = _timestamp(proof.get("measuredAt"), "Storage proof measuredAt")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    demand(current - MAX_PROOF_AGE <= measured_at <= current,
           "Storage proof is stale or future-dated")
    demand(measured_at >= cloud["collectedAt"],
           "Storage proof predates its cloud identity evidence")
    demand(type(proof.get("version")) is int and proof["version"] == 2
           and proof.get("identityMode") == IDENTITY_MODE
           and proof.get("allocationContract") == "managed-block-v1"
           and proof.get("identityEvidenceSha256") == evidence_sha256
           and proof.get("mountpoint") == str(mountpoint) and proof.get("filesystem") == filesystem
           and type(proof.get("capacityBytes")) is int and proof["capacityBytes"] == capacity_bytes
           and type(proof.get("requiredFreeBytes")) is int
           and proof["requiredFreeBytes"] == required_free_bytes,
           "Storage proof lacks the reviewed Tencent CBS managed-block contract")
    if storage_role == "data":
        demand(re.fullmatch(r"[0-9a-f]{64}", str(proof.get("runtimeProofSha256", ""))),
               "Data storage proof must bind the stable runtime proof SHA-256")
    fsync = proof.get("fsyncP99Ms")
    demand(isinstance(fsync, (int, float)) and not isinstance(fsync, bool)
           and math.isfinite(fsync) and fsync > 0,
           "Storage proof fsyncP99Ms must be positive")
    volume_id = _required_string(proof.get("volumeId"), "Storage proof volumeId",
                                 r"disk-[A-Za-z0-9]{6,64}")
    demand(proof.get("guestBinding") == live_guest_binding,
           "Signed storage proof guest binding differs from the live host")
    demand(capacity_bytes <= live_guest_binding["target"]["sizeBytes"],
           "Filesystem capacity exceeds the target CBS guest leaf size")
    if storage_role == "backup":
        demand(proof.get("storageRole") == "backup"
               and type(proof.get("maximumBackupBytes")) is int
               and proof["maximumBackupBytes"] == maximum_backup_bytes,
               "Backup proof lacks the reviewed maximum backup budget")
    validate_guest_binding(live_guest_binding, cloud, volume_id,
                           [item["stableId"] for item in live_guest_binding["protectedMounts"]])
    return proof


def validate_cbs_attestation_fields(attestation, proof, cloud, measurement_sha256,
                                    protected_paths_sha256, minimum_free_bytes,
                                    protected_logical_ids, storage_role="data",
                                    maximum_backup_bytes=None):
    expected = {
        "project": "zhongshu-platform-b",
        "mountpoint": proof["mountpoint"],
        "volumeId": proof["volumeId"],
        "identityMode": IDENTITY_MODE,
        "allocationContract": "managed-block-v1",
        "identityEvidenceSha256": proof["identityEvidenceSha256"],
        "accountUin": cloud["accountUin"],
        "region": cloud["region"],
        "zone": cloud["zone"],
        "instanceId": cloud["instanceId"],
        "logicalVolumeIds": [proof["volumeId"]],
        "protectedLogicalVolumeIds": sorted(set(protected_logical_ids)),
        "guestBindingSha256": canonical_sha256(proof["guestBinding"]),
        "encrypted": cloud["disksById"][proof["volumeId"]]["Encrypt"],
        "measurementSha256": measurement_sha256,
        "protectedPathsSha256": protected_paths_sha256,
        "minimumFreeBytes": minimum_free_bytes,
        "aScopeComplete": True,
    }
    if storage_role == "backup":
        expected.update({"storageRole": "backup", "maximumBackupBytes": maximum_backup_bytes})
    else:
        expected["runtimeProofSha256"] = proof["runtimeProofSha256"]
    demand(type(attestation.get("encrypted")) is bool and attestation.get("aScopeComplete") is True,
           "Attestation encryption and A-scope declarations must be exact booleans")
    demand(type(attestation.get("minimumFreeBytes")) is int,
           "Attestation minimumFreeBytes must be an integer")
    if storage_role == "backup":
        demand(type(attestation.get("maximumBackupBytes")) is int,
               "Attestation maximumBackupBytes must be an integer")
    issued_at = _timestamp(attestation.get("issuedAt"), "Attestation issuedAt")
    demand(issued_at >= cloud["collectedAt"], "Attestation predates cloud identity evidence")
    demand(all(attestation.get(key) == value for key, value in expected.items()),
           "Independent attestation does not bind the reviewed CBS identity and guest binding")
    return attestation


def get_cbs_contract(values, mountpoint, storage_role="data", now=None):
    """Load the shared cloud evidence and return identity fields for deployment gates."""
    demand(identity_mode(values) == IDENTITY_MODE, "Tencent CBS contract requires explicit CBS mode")
    cloud = load_cloud_evidence(values.get("B_SAME_HOST_TENCENT_CBS_EVIDENCE_FILE", ""),
                                values.get("B_SAME_HOST_TENCENT_CBS_EVIDENCE_SHA256", ""), now=now)
    return {
        "identityMode": IDENTITY_MODE,
        "allocationContract": "managed-block-v1",
        "identityEvidenceSha256": cloud["identityEvidenceSha256"],
        "accountUin": cloud["accountUin"], "region": cloud["region"], "zone": cloud["zone"],
        "instanceId": cloud["instanceId"], "systemDiskId": cloud["systemDiskId"],
        "disksById": cloud["disksById"], "cloud": cloud,
        "mountpoint": str(mountpoint), "storageRole": storage_role,
    }
