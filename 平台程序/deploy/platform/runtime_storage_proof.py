"""Bind stable Runtime identity to an already verified host storage contract."""
import hashlib
import json
import re

FIELDS = {"version", "project", "storageRole", "identityMode", "allocationContract", "accountUin",
          "region", "zone", "instanceId", "volumeId", "filesystem", "filesystemUuid", "exclusiveWriter"}
IDENTITY_FIELDS = ("accountUin", "region", "zone", "instanceId", "volumeId", "filesystem", "filesystemUuid")


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        demand(key not in result, "Duplicate Runtime proof key")
        result[key] = value
    return result


def invalid_constant(_):
    raise ValueError("Non-finite Runtime proof number")


def validate_runtime_proof(payload, expected_sha256, identity_mode, binding=None):
    demand(len(payload) <= 8192 and re.fullmatch(r"[a-f0-9]{64}", expected_sha256 or ""),
           "Runtime proof size or configured hash is invalid")
    digest = hashlib.sha256(payload).hexdigest()
    demand(digest == expected_sha256, "Runtime capacity proof hash mismatch")
    value = json.loads(payload, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    demand(isinstance(value, dict) and type(value.get("version")) is int,
           "Runtime proof requires an integer schema version")
    demand(value.get("filesystem") in ("ext4", "xfs") and value.get("exclusiveWriter") is True
           and isinstance(value.get("volumeId"), str) and value["volumeId"].strip(),
           "Runtime proof filesystem, volume or writer contract is invalid")
    if identity_mode == "serial-wwn-thick-v1":
        demand(value["version"] == 1 and value.get("allocation") == "thick",
               "Legacy storage requires a v1 thick Runtime proof")
        return value
    demand(identity_mode == "tencent-cbs-v1", "Unknown Runtime storage identity mode")
    demand(value["version"] == 2 and set(value) == FIELDS
           and all(isinstance(value[key], str) for key in FIELDS - {"version", "exclusiveWriter"}),
           "CBS Runtime proof requires the exact stable v2 schema")
    demand(payload == json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode(),
           "CBS Runtime proof must use compact JSON without a trailing newline")
    demand(value["project"] == "zhongshu-platform-b" and value["storageRole"] == "runtime-journal"
           and value["identityMode"] == identity_mode and value["allocationContract"] == "managed-block-v1",
           "CBS Runtime proof project, role or allocation contract mismatch")
    demand(re.fullmatch(r"[0-9]{6,20}", value["accountUin"])
           and re.fullmatch(r"ap-[a-z]+(?:-[a-z]+)*", value["region"])
           and re.fullmatch(re.escape(value["region"]) + r"-[0-9]+", value["zone"])
           and re.fullmatch(r"ins-[a-z0-9]+", value["instanceId"])
           and re.fullmatch(r"disk-[a-z0-9]+", value["volumeId"])
           and re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", value["filesystemUuid"]),
           "CBS Runtime proof identity format is invalid")
    demand(isinstance(binding, dict) and binding.get("runtimeProofSha256") == digest,
           "Verified host attestation must bind the stable Runtime proof hash")
    for field in IDENTITY_FIELDS:
        demand(value[field] == binding.get(field), f"Runtime proof differs from verified host {field}")
    return value
