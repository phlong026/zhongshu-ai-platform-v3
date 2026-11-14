#!/usr/bin/env python3
"""Validate or prepare B-only same-host storage. Never formats disks, prunes Docker, or touches A."""
import argparse
from collections import namedtuple
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timedelta, timezone


HERE = Path(__file__).resolve().parent
PROJECT = "zhongshu-platform-b"
TARGET = f"{PROJECT}/storage"
OVERLAY = HERE / "compose.same-host.yaml"
SAME_HOST_VARIABLES = (
    "B_SAME_HOST_MOUNT", "B_SAME_HOST_DATA_ROOT", "B_SAME_HOST_STORAGE_PROOF_FILE",
    "B_SAME_HOST_STORAGE_PROOF_SHA256", "B_SAME_HOST_PROTECTED_A_PATHS_FILE",
    "B_SAME_HOST_MIN_FREE_BYTES", "B_SAME_HOST_STORAGE_ATTESTATION_FILE",
    "B_SAME_HOST_STORAGE_ATTESTATION_SIGNATURE_FILE", "B_SAME_HOST_APPROVER_PUBLIC_KEY_FILE",
)
BACKUP_VARIABLES = (
    "B_SAME_HOST_BACKUP_MOUNT", "B_SAME_HOST_BACKUP_ROOT",
    "B_SAME_HOST_BACKUP_STORAGE_PROOF_FILE", "B_SAME_HOST_BACKUP_STORAGE_PROOF_SHA256",
    "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_FILE",
    "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_SIGNATURE_FILE",
    "B_SAME_HOST_BACKUP_APPROVER_PUBLIC_KEY_FILE", "B_SAME_HOST_BACKUP_MIN_FREE_BYTES",
    "B_SAME_HOST_BACKUP_MAX_BYTES",
)
VARIABLES = (*SAME_HOST_VARIABLES, *BACKUP_VARIABLES, "AI_RUNTIME_JOURNAL_DIR")
TRUSTED_APPROVER_KEY_HASH_FILE = HERE / "trusted-storage-approver.sha256"
DirectoryContract = namedtuple("DirectoryContract", "path uid gid mode")
VOLUME_DIRECTORIES = {
    "pgdata": "postgres-b", "redisdata": "redis-b",
    "design-pgdata": "postgres-design", "design-redisdata": "redis-design",
}
OWNER_MARKER = ".b-storage-owner.json"
BACKUP_OWNER_MARKER = ".b-backup-owner.json"


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def env_values(path):
    values = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        demand(separator and re.fullmatch(r"[A-Z][A-Z0-9_]*", key) and key not in values,
               "Invalid or duplicate deployment env line")
        values[key] = value.strip().strip("'\"")
    return values


def example_values(mount):
    root = str(Path(mount) / PROJECT)
    return {
        "B_SAME_HOST_MOUNT": str(mount),
        "B_SAME_HOST_DATA_ROOT": root,
        "B_SAME_HOST_STORAGE_PROOF_FILE": str(Path(mount) / ".zhongshu-platform-b-storage-proof.json"),
        "B_SAME_HOST_STORAGE_PROOF_SHA256": "a" * 64,
        "B_SAME_HOST_PROTECTED_A_PATHS_FILE": str(Path(mount) / ".zhongshu-platform-a-protected-paths"),
        "B_SAME_HOST_MIN_FREE_BYTES": str(12 * 1024**3),
        "B_SAME_HOST_STORAGE_ATTESTATION_FILE": str(Path(mount) / ".storage-attestation.json"),
        "B_SAME_HOST_STORAGE_ATTESTATION_SIGNATURE_FILE": str(Path(mount) / ".storage-attestation.sig"),
        "B_SAME_HOST_APPROVER_PUBLIC_KEY_FILE": str(Path(mount) / ".storage-approver.pem"),
        "AI_RUNTIME_JOURNAL_DIR": root + "/runtime-journal",
    }


def example_backup_values(mount):
    mount = Path(mount)
    evidence = Path("/var/lib/zhongshu-platform-b-storage-evidence")
    return {
        "B_SAME_HOST_BACKUP_MOUNT": str(mount),
        "B_SAME_HOST_BACKUP_ROOT": str(mount / f"{PROJECT}-backups"),
        "B_SAME_HOST_BACKUP_STORAGE_PROOF_FILE": str(evidence / "backup-storage-proof.json"),
        "B_SAME_HOST_BACKUP_STORAGE_PROOF_SHA256": "b" * 64,
        "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_FILE": str(evidence / "backup-storage-attestation.json"),
        "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_SIGNATURE_FILE": str(evidence / "backup-storage-attestation.sig"),
        "B_SAME_HOST_BACKUP_APPROVER_PUBLIC_KEY_FILE": str(evidence / "storage-approver.pem"),
        "B_SAME_HOST_BACKUP_MIN_FREE_BYTES": str(8 * 1024**3),
        "B_SAME_HOST_BACKUP_MAX_BYTES": str(16 * 1024**3),
    }


def compose_overlay(values):
    present = [key for key in SAME_HOST_VARIABLES if values.get(key)]
    if not present:
        return []
    demand(len(present) == len(SAME_HOST_VARIABLES) and values.get("AI_RUNTIME_JOURNAL_DIR"),
           "Same-host storage configuration must be complete")
    path_plan(values, protected_paths=[])
    demand(re.fullmatch(r"[0-9a-f]{64}", values["B_SAME_HOST_STORAGE_PROOF_SHA256"]),
           "Same-host storage proof SHA-256 is required")
    demand(values["B_SAME_HOST_MIN_FREE_BYTES"].isdigit()
           and int(values["B_SAME_HOST_MIN_FREE_BYTES"]) > 0,
           "Same-host minimum free bytes must be positive")
    return ["-f", str(OVERLAY)]


def _absolute_clean(value, label):
    path = Path(value)
    demand(path.is_absolute() and ".." not in path.parts, f"{label} must be a clean absolute path")
    return path


def _overlaps(left, right):
    return left == right or left.is_relative_to(right) or right.is_relative_to(left)


def path_plan(values, protected_paths):
    mount = _absolute_clean(values.get("B_SAME_HOST_MOUNT", ""), "Same-host mount")
    root = _absolute_clean(values.get("B_SAME_HOST_DATA_ROOT", ""), "Same-host B root")
    demand(root == mount / PROJECT, "Same-host B root must be the fixed project child of its mount")
    planned = {name: root / name for name in
               ("postgres-b", "redis-b", "postgres-design", "redis-design",
                "runtime-journal", "backup-staging")}
    demand(_absolute_clean(values.get("AI_RUNTIME_JOURNAL_DIR", ""), "Runtime journal")
           == planned["runtime-journal"], "Runtime journal must use the fixed B same-host path")
    denied = [Path("/var/lib/docker"), HERE.parent.parent.resolve()]
    denied.extend(Path(path) for path in protected_paths)
    for path in [root, *planned.values()]:
        demand(path != Path("/") and not any(_overlaps(path, protected) for protected in denied),
               "B same-host path overlaps a protected A/system/repository path")
    return planned


def backup_path_plan(values, required=True):
    present = [key for key in BACKUP_VARIABLES if values.get(key)]
    if not present and not required:
        return None
    demand(len(present) == len(BACKUP_VARIABLES),
           "Same-host backup storage configuration must be complete")
    mount = _absolute_clean(values["B_SAME_HOST_BACKUP_MOUNT"], "Same-host backup mount")
    root = _absolute_clean(values["B_SAME_HOST_BACKUP_ROOT"], "Same-host backup root")
    demand(root == mount / f"{PROJECT}-backups",
           "Same-host backup root must be the fixed project child of its mount")
    data_mount = _absolute_clean(values["B_SAME_HOST_MOUNT"], "Same-host data mount")
    demand(mount != data_mount and not _overlaps(mount, data_mount),
           "Backup storage must be a separate mount from B data storage")
    demand(re.fullmatch(r"[0-9a-f]{64}", values["B_SAME_HOST_BACKUP_STORAGE_PROOF_SHA256"]),
           "Backup storage proof SHA-256 is required")
    for key in ("B_SAME_HOST_BACKUP_MIN_FREE_BYTES", "B_SAME_HOST_BACKUP_MAX_BYTES"):
        demand(values[key].isdigit() and int(values[key]) > 0,
               f"{key} must be a positive byte count")
    return root


def directory_contracts(root):
    root = Path(root)
    contracts = {
        name: DirectoryContract(root / name, 999, 999, 0o700)
        for name in ("postgres-b", "redis-b", "postgres-design", "redis-design")
    }
    contracts["runtime-journal"] = DirectoryContract(root / "runtime-journal", 1000, 1000, 0o700)
    contracts["backup-staging"] = DirectoryContract(root / "backup-staging", 0, 0, 0o700)
    return contracts


def validate_compose_volumes(model, values, required_volumes=None):
    if not compose_overlay(values):
        return
    root = Path(values["B_SAME_HOST_DATA_ROOT"])
    volumes = model.get("volumes", {})
    required_volumes = set(VOLUME_DIRECTORIES) if required_volumes is None else set(required_volumes)
    for volume in required_volumes:
        directory = VOLUME_DIRECTORIES[volume]
        config = volumes.get(volume, {})
        demand(config.get("name") == f"{PROJECT}_{volume}" and not config.get("external")
               and config.get("driver") == "local"
               and config.get("driver_opts") == {
                   "type": "none", "o": "bind", "device": str(root / directory)},
               f"Same-host volume is not bound to the reviewed B path: {volume}")


def validate_volume_inspection(items, values):
    expected = {f"{PROJECT}_{name}": (name, str(Path(values["B_SAME_HOST_DATA_ROOT"]) / directory))
                for name, directory in VOLUME_DIRECTORIES.items()}
    demand(len(items) == len({item.get("Name") for item in items}), "Duplicate Docker volume evidence refused")
    for item in items:
        name = item.get("Name")
        demand(name in expected, "Unexpected Docker volume in same-host evidence")
        logical, device = expected[name]
        labels = item.get("Labels") or {}
        demand(item.get("Driver") == "local" and item.get("Options") == {
                   "type": "none", "o": "bind", "device": device}
               and labels.get("com.docker.compose.project") == PROJECT
               and labels.get("com.docker.compose.volume") == logical,
               f"Existing volume cannot be implicitly adopted or rebound: {name}")


def validate_actual_volumes_against_model(items, model):
    expected = {config["name"]: (logical, config) for logical, config in model.get("volumes", {}).items()}
    demand(len(items) == len({item.get("Name") for item in items}), "Duplicate Docker volume evidence refused")
    for item in items:
        name = item.get("Name")
        demand(name in expected, "Actual Docker volume is absent from the rendered B model")
        logical, config = expected[name]
        labels = item.get("Labels") or {}
        demand(labels.get("com.docker.compose.project") == PROJECT
               and labels.get("com.docker.compose.volume") == logical,
               f"Actual volume lacks exact B Compose ownership: {name}")
        demand(item.get("Driver") == config.get("driver", "local")
               and (item.get("Options") or {}) == (config.get("driver_opts") or {}),
               f"Actual volume driver/options differ from the rendered B model: {name}")


def inspect_existing_model_volumes(model, environment=None):
    listed = subprocess.run(["docker", "volume", "ls", "--format", "{{.Name}}"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            timeout=30, check=False, env=environment)
    demand(listed.returncode == 0, "Docker volume inventory failed")
    existing = set(listed.stdout.splitlines())
    names = [config["name"] for config in model.get("volumes", {}).values() if config["name"] in existing]
    if not names:
        return []
    inspected = subprocess.run(["docker", "volume", "inspect", *names], stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, timeout=30, check=False, env=environment)
    demand(inspected.returncode == 0, "Existing B volume inspection failed")
    items = json.loads(inspected.stdout)
    validate_actual_volumes_against_model(items, model)
    return items


def validate_existing_docker_volumes(values):
    existing = []
    for logical in VOLUME_DIRECTORIES:
        name = f"{PROJECT}_{logical}"
        listed = subprocess.run(["docker", "volume", "ls", "--filter", f"name={name}", "--format", "{{.Name}}"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                timeout=30, check=False)
        demand(listed.returncode == 0, "Docker volume inventory failed")
        if name not in listed.stdout.splitlines():
            continue
        inspected = subprocess.run(["docker", "volume", "inspect", name], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, timeout=30, check=False)
        demand(inspected.returncode == 0, "Existing B volume inspection failed")
        existing.extend(json.loads(inspected.stdout))
    validate_volume_inspection(existing, values)


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _reject_symlink_components(path):
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if current.exists() or current.is_symlink():
            demand(not stat.S_ISLNK(current.lstat().st_mode), "Same-host path contains a symlink")


def _protected_paths(path):
    demand(path.is_absolute() and path.is_file() and not path.is_symlink(),
           "Protected A paths file must be an absolute regular file")
    metadata = path.stat()
    demand(metadata.st_uid == 0 and not (metadata.st_mode & 0o022),
           "Protected A paths file must be root-owned and not group/world-writable")
    paths = []
    for raw in path.read_text().splitlines():
        value = raw.strip()
        if not value or value.startswith("#"):
            continue
        protected = _absolute_clean(value, "Protected A path")
        demand(protected.exists(), "Every protected A path must exist during validation")
        paths.append(protected.resolve(strict=True))
    demand(paths, "At least one real A path must be protected on a same-host deployment")
    demand(Path("/opt/zhongshu") in paths,
           "Same-host A protection scope must include the discovered /opt/zhongshu root")
    return paths


def _mount_identity(path):
    result = subprocess.run(["findmnt", "-J", "-o", "TARGET,SOURCE,UUID,MAJ:MIN", "--target", str(path)],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                            timeout=10, check=False)
    demand(result.returncode == 0, "Mount identity lookup failed")
    filesystems = json.loads(result.stdout).get("filesystems", [])
    demand(len(filesystems) == 1, "Mount identity must resolve exactly once")
    item = filesystems[0]
    return {"source": item.get("source"), "uuid": item.get("uuid"), "maj:min": item.get("maj:min"),
            "device": Path(path).stat().st_dev}


def _same_storage(left, right):
    return left["device"] == right["device"] or any(
        left.get(key) and left.get(key) == right.get(key) for key in ("source", "uuid", "maj:min"))


def _flatten_block_devices(devices, parent=None, nodes=None, parents=None):
    nodes = {} if nodes is None else nodes
    parents = {} if parents is None else parents
    for item in devices:
        identity = item.get("maj:min")
        if identity:
            nodes[identity] = item
            if parent:
                parents.setdefault(identity, set()).add(parent)
        _flatten_block_devices(item.get("children", []), identity, nodes, parents)
    by_name = {item.get("kname") or item.get("name"): identity for identity, item in nodes.items()}
    for identity, item in nodes.items():
        if item.get("pkname") in by_name:
            parents.setdefault(identity, set()).add(by_name[item["pkname"]])
    return nodes, parents


def block_ancestry(nodes, parents, identity):
    demand(identity in nodes, "Mounted device is absent from lsblk topology")
    result, pending = set(), [identity]
    while pending:
        current = pending.pop()
        if current in result:
            continue
        result.add(current)
        pending.extend(parents.get(current, ()))
    leaves = {item for item in result if not parents.get(item)}
    physical_ids = set()
    for item in leaves:
        node = nodes[item]
        stable = str(node.get("wwn") or node.get("serial") or "").strip()
        demand(stable, "Physical block ancestor lacks a stable WWN/serial identity")
        physical_ids.add(stable)
    return result, physical_ids


def validate_block_independence(b_identity, shared_identities, topology):
    nodes, parents = _flatten_block_devices(topology)
    b_ancestors, b_physical = block_ancestry(nodes, parents, b_identity)
    shared_physical = set()
    for identity in shared_identities:
        ancestors, physical = block_ancestry(nodes, parents, identity)
        demand(b_ancestors.isdisjoint(ancestors) and b_physical.isdisjoint(physical),
               "B and A/root/Docker block-device ancestry overlaps")
        shared_physical.update(physical)
    return b_physical, shared_physical


def _block_independence(mount_identity, shared_identities):
    result = subprocess.run(["lsblk", "-J", "-o", "NAME,KNAME,TYPE,PKNAME,MAJ:MIN,SERIAL,WWN"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            timeout=30, check=False)
    demand(result.returncode == 0, "Block-device ancestry discovery failed")
    return validate_block_independence(mount_identity["maj:min"],
                                       [item["maj:min"] for item in shared_identities],
                                       json.loads(result.stdout).get("blockdevices", []))


def _private_root_file(path, label):
    _reject_symlink_components(path)
    demand(path.is_absolute() and path.is_file() and not path.is_symlink(), f"{label} must be a regular file")
    metadata = path.stat()
    demand(metadata.st_uid == 0 and not (metadata.st_mode & 0o022),
           f"{label} must be root-owned and not group/world-writable")


def validate_attestation(values, proof, proof_file, protected_file, b_physical, protected_physical):
    attestation_file = Path(values["B_SAME_HOST_STORAGE_ATTESTATION_FILE"])
    signature_file = Path(values["B_SAME_HOST_STORAGE_ATTESTATION_SIGNATURE_FILE"])
    public_key_file = Path(values["B_SAME_HOST_APPROVER_PUBLIC_KEY_FILE"])
    attestation = _verified_attestation(attestation_file, signature_file, public_key_file)
    demand(attestation.get("project") == PROJECT and attestation.get("mountpoint") == str(Path(values["B_SAME_HOST_MOUNT"]))
           and attestation.get("volumeId") == proof.get("volumeId")
           and attestation.get("provisioning") == "thick"
           and attestation.get("measurementSha256") == _sha256(proof_file)
           and attestation.get("protectedPathsSha256") == _sha256(protected_file)
           and attestation.get("minimumFreeBytes") == int(values["B_SAME_HOST_MIN_FREE_BYTES"])
           and set(attestation.get("physicalVolumeIds", [])) == set(b_physical)
           and set(attestation.get("protectedPhysicalVolumeIds", [])) == set(protected_physical)
           and attestation.get("aScopeComplete") is True,
           "Independent attestation does not bind the reviewed B disk, floor and complete A scope")
    _validate_attestation_window(attestation)


def _verified_attestation(attestation_file, signature_file, public_key_file):
    demand(TRUSTED_APPROVER_KEY_HASH_FILE.is_file() and not TRUSTED_APPROVER_KEY_HASH_FILE.is_symlink(),
           "Trusted storage approver key hash is not provisioned in the reviewed deployment package")
    trusted_hash = TRUSTED_APPROVER_KEY_HASH_FILE.read_text().strip()
    demand(re.fullmatch(r"[0-9a-f]{64}", trusted_hash), "Trusted storage approver key hash is invalid")
    for path, label in ((attestation_file, "Storage attestation"), (signature_file, "Attestation signature"),
                        (public_key_file, "Approver public key")):
        _private_root_file(path, label)
    demand(_sha256(public_key_file) == trusted_hash, "Storage approver key is not the reviewed trust anchor")
    verified = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public_key_file),
                               "-signature", str(signature_file), str(attestation_file)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
    demand(verified.returncode == 0, "Independent storage attestation signature is invalid")
    return json.loads(attestation_file.read_text())


def _validate_attestation_window(attestation):
    issued = datetime.fromisoformat(str(attestation.get("issuedAt", "")).replace("Z", "+00:00"))
    expires = datetime.fromisoformat(str(attestation.get("expiresAt", "")).replace("Z", "+00:00"))
    demand(issued.tzinfo is not None and expires.tzinfo is not None
           and issued.astimezone(timezone.utc) <= datetime.now(timezone.utc) < expires.astimezone(timezone.utc)
           and expires - issued <= timedelta(days=7),
           "Independent storage attestation is expired")


def validate_backup_attestation(values, proof, proof_file, protected_file,
                                backup_physical, protected_physical):
    attestation_file = Path(values["B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_FILE"])
    signature_file = Path(values["B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_SIGNATURE_FILE"])
    public_key_file = Path(values["B_SAME_HOST_BACKUP_APPROVER_PUBLIC_KEY_FILE"])
    attestation = _verified_attestation(attestation_file, signature_file, public_key_file)
    demand(attestation.get("project") == PROJECT and attestation.get("storageRole") == "backup"
           and attestation.get("mountpoint") == values["B_SAME_HOST_BACKUP_MOUNT"]
           and attestation.get("volumeId") == proof.get("volumeId")
           and attestation.get("provisioning") == "thick"
           and attestation.get("measurementSha256") == _sha256(proof_file)
           and attestation.get("protectedPathsSha256") == _sha256(protected_file)
           and attestation.get("minimumFreeBytes") == int(values["B_SAME_HOST_BACKUP_MIN_FREE_BYTES"])
           and attestation.get("maximumBackupBytes") == int(values["B_SAME_HOST_BACKUP_MAX_BYTES"])
           and set(attestation.get("physicalVolumeIds", [])) == set(backup_physical)
           and set(attestation.get("protectedPhysicalVolumeIds", [])) == set(protected_physical)
           and attestation.get("aScopeComplete") is True,
           "Backup attestation does not bind the independent disk, capacity budget and protected scope")
    _validate_attestation_window(attestation)


def validate_tree(path, expected_device):
    validate_tree_with_mounts(path, expected_device, _mountpoints())


def _unescape_mountinfo(value):
    return re.sub(r"\\([0-7]{3})", lambda match: chr(int(match.group(1), 8)), value)


def _mountpoints(path=Path("/proc/self/mountinfo")):
    demand(path.is_file(), "Linux mountinfo is required for same-host storage validation")
    points = set()
    for line in path.read_text().splitlines():
        fields = line.split()
        demand(len(fields) >= 6, "Malformed Linux mountinfo")
        points.add(Path(_unescape_mountinfo(fields[4])).resolve())
    return points


def validate_tree_with_mounts(path, expected_device, mountpoints):
    for root, directories, files in os.walk(path, topdown=True, followlinks=False):
        root_path = Path(root)
        root_metadata = root_path.lstat()
        demand(stat.S_ISDIR(root_metadata.st_mode) and not root_path.is_symlink()
               and root_metadata.st_dev == expected_device and root_path.resolve() not in mountpoints,
               "B data tree contains a symlink, nested mount or cross-device directory")
        for name in [*directories, *files]:
            child = root_path / name
            metadata = child.lstat()
            demand(not stat.S_ISLNK(metadata.st_mode) and metadata.st_dev == expected_device
                   and child.resolve() not in mountpoints
                   and (stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode)),
                   "B data tree contains a symlink, device, socket, fifo or cross-device entry")


def _marker_value(values, proof):
    return {
        "version": 1, "project": PROJECT, "mountpoint": values["B_SAME_HOST_MOUNT"],
        "dataRoot": values["B_SAME_HOST_DATA_ROOT"], "volumeId": proof["volumeId"],
        "directories": {name: str(contract.path) for name, contract in
                        directory_contracts(Path(values["B_SAME_HOST_DATA_ROOT"])).items()},
    }


def _validate_marker(root, values, proof):
    marker = root / OWNER_MARKER
    demand(marker.is_file() and not marker.is_symlink(), "Trusted B ownership marker is missing")
    metadata = marker.stat()
    demand(metadata.st_uid == 0 and metadata.st_gid == 0 and stat.S_IMODE(metadata.st_mode) == 0o600,
           "Trusted B ownership marker permissions are invalid")
    demand(json.loads(marker.read_text()) == _marker_value(values, proof),
           "Trusted B ownership marker does not bind this reviewed storage")


def _filesystem_type(mount):
    result = subprocess.run(["findmnt", "-n", "-o", "FSTYPE", "--target", str(mount)],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                            timeout=10, check=False)
    demand(result.returncode == 0 and result.stdout.strip() in ("ext4", "xfs"),
           "Same-host storage requires a mounted ext4/xfs filesystem")
    return result.stdout.strip()


def validate_host(values, require_directories=True, check_docker=True, check_backup=True):
    backup_root = backup_path_plan(values, required=False)
    compose_overlay(values)
    mount = Path(values["B_SAME_HOST_MOUNT"])
    proof_file = Path(values["B_SAME_HOST_STORAGE_PROOF_FILE"])
    protected_file = Path(values["B_SAME_HOST_PROTECTED_A_PATHS_FILE"])
    for path in (mount, proof_file, protected_file):
        _reject_symlink_components(path)
    mount = mount.resolve(strict=True)
    demand(mount.is_dir() and mount.is_mount(), "Same-host storage root must be an actual mount point")
    filesystem = _filesystem_type(mount)
    protected = _protected_paths(protected_file.resolve(strict=True))
    docker_root_result = subprocess.run(["docker", "info", "--format", "{{.DockerRootDir}}"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                        timeout=30, check=False)
    demand(docker_root_result.returncode == 0 and docker_root_result.stdout.strip(),
           "Docker data-root discovery failed")
    docker_root = Path(docker_root_result.stdout.strip()).resolve(strict=True)
    mount_identity = _mount_identity(mount)
    shared_identities = [_mount_identity(path) for path in [Path("/").resolve(), docker_root, *protected]]
    for shared_identity in shared_identities:
        demand(not _same_storage(mount_identity, shared_identity),
               "B same-host mount shares the root, Docker, or protected A storage device")
    b_physical, protected_physical = _block_independence(mount_identity, shared_identities)
    planned = path_plan(values, protected)
    root = Path(values["B_SAME_HOST_DATA_ROOT"])
    proof_metadata = proof_file.stat()
    demand(proof_file.is_file() and not proof_file.is_symlink() and proof_metadata.st_uid == 0
           and not (proof_metadata.st_mode & 0o027),
           "Storage proof must be root-owned and not group/world-writable")
    demand(_sha256(proof_file) == values["B_SAME_HOST_STORAGE_PROOF_SHA256"],
           "Same-host storage proof hash mismatch")
    proof = json.loads(proof_file.read_text())
    try:
        measured_at = datetime.fromisoformat(str(proof.get("measuredAt", "")).replace("Z", "+00:00"))
        demand(measured_at.tzinfo is not None, "Storage proof timestamp requires a timezone")
        age = datetime.now(timezone.utc) - measured_at.astimezone(timezone.utc)
        demand(timedelta(0) <= age <= timedelta(hours=24), "Storage proof is stale or future-dated")
    except (ValueError, TypeError) as error:
        raise ValueError("Storage proof measuredAt is invalid") from error
    usage = shutil.disk_usage(mount)
    minimum_free = int(values["B_SAME_HOST_MIN_FREE_BYTES"])
    demand(proof.get("version") == 1 and proof.get("mountpoint") == str(mount)
           and proof.get("filesystem") == filesystem and proof.get("provisioning") == "thick"
           and isinstance(proof.get("volumeId"), str) and proof["volumeId"].strip()
           and isinstance(proof.get("fsyncP99Ms"), (int, float)) and proof["fsyncP99Ms"] > 0
           and isinstance(proof.get("capacityBytes"), int) and proof["capacityBytes"] == usage.total
           and proof.get("requiredFreeBytes") == minimum_free,
           "Storage proof lacks the reviewed thick/fsync/capacity contract")
    demand(usage.free >= minimum_free, "Same-host storage has less than the reviewed free-space floor")
    validate_attestation(values, proof, proof_file, protected_file, b_physical, protected_physical)
    if not require_directories:
        if root.exists() or root.is_symlink():
            _reject_symlink_components(root)
        if check_docker:
            validate_existing_docker_volumes(values)
        return planned
    root = root.resolve(strict=True)
    root_stat = root.stat()
    demand(stat.S_IMODE(root_stat.st_mode) == 0o710 and root_stat.st_uid == 0 and root_stat.st_gid == 20000
           and root_stat.st_dev == mount.stat().st_dev, "B storage root ownership/mode/device is invalid")
    _validate_marker(root, values, proof)
    for contract in directory_contracts(root).values():
        _reject_symlink_components(contract.path)
        resolved = contract.path.resolve(strict=True)
        metadata = resolved.stat()
        demand(resolved == contract.path and resolved.is_dir() and metadata.st_dev == root_stat.st_dev
               and metadata.st_uid == contract.uid and metadata.st_gid == contract.gid
               and stat.S_IMODE(metadata.st_mode) == contract.mode,
               f"Same-host directory contract failed: {contract.path.name}")
        validate_tree(resolved, root_stat.st_dev)
    if check_docker:
        validate_existing_docker_volumes(values)
    if require_directories and check_backup and backup_root is not None:
        validate_backup_mount(values)
    return planned


def _backup_marker_value(values, proof):
    return {
        "version": 1, "project": PROJECT, "storageRole": "backup",
        "mountpoint": values["B_SAME_HOST_BACKUP_MOUNT"],
        "backupRoot": values["B_SAME_HOST_BACKUP_ROOT"], "volumeId": proof["volumeId"],
        "minimumFreeBytes": int(values["B_SAME_HOST_BACKUP_MIN_FREE_BYTES"]),
        "maximumBackupBytes": int(values["B_SAME_HOST_BACKUP_MAX_BYTES"]),
    }


def _validate_backup_marker(root, values, proof):
    marker = root / BACKUP_OWNER_MARKER
    demand(marker.is_file() and not marker.is_symlink(), "Trusted B backup ownership marker is missing")
    metadata = marker.stat()
    demand(metadata.st_uid == 0 and metadata.st_gid == 0 and stat.S_IMODE(metadata.st_mode) == 0o600,
           "Trusted B backup ownership marker permissions are invalid")
    demand(json.loads(marker.read_text()) == _backup_marker_value(values, proof),
           "Trusted B backup marker does not bind the reviewed storage budget")


def _validate_backup_mount_exclusive(mount, root):
    entries = {entry.name for entry in os.scandir(mount)}
    demand(entries <= {"lost+found", Path(root).name},
           "Backup mount is not exclusively assigned to the fixed B backup root")


def validate_backup_mount(values, require_root=True):
    root = backup_path_plan(values)
    mount = Path(values["B_SAME_HOST_BACKUP_MOUNT"])
    proof_file = Path(values["B_SAME_HOST_BACKUP_STORAGE_PROOF_FILE"])
    protected_file = Path(values["B_SAME_HOST_PROTECTED_A_PATHS_FILE"])
    for path in (mount, proof_file, protected_file):
        _reject_symlink_components(path)
    mount = mount.resolve(strict=True)
    demand(mount.is_dir() and mount in _mountpoints(),
           "Same-host backup storage root must be an actual Linux mount point")
    _validate_backup_mount_exclusive(mount, root)
    filesystem = _filesystem_type(mount)
    protected = _protected_paths(protected_file.resolve(strict=True))
    docker_root_result = subprocess.run(["docker", "info", "--format", "{{.DockerRootDir}}"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                        timeout=30, check=False)
    demand(docker_root_result.returncode == 0 and docker_root_result.stdout.strip(),
           "Docker data-root discovery failed")
    data_mount = Path(values["B_SAME_HOST_MOUNT"]).resolve(strict=True)
    shared_paths = [Path("/").resolve(), Path(docker_root_result.stdout.strip()).resolve(strict=True),
                    *protected, data_mount]
    mount_identity = _mount_identity(mount)
    shared_identities = [_mount_identity(path) for path in shared_paths]
    for shared_identity in shared_identities:
        demand(not _same_storage(mount_identity, shared_identity),
               "Backup mount shares root, Docker, A, or the B data storage device")
    backup_physical, protected_physical = _block_independence(mount_identity, shared_identities)
    proof_metadata = proof_file.stat()
    demand(proof_file.is_file() and not proof_file.is_symlink() and proof_metadata.st_uid == 0
           and not (proof_metadata.st_mode & 0o027),
           "Backup storage proof must be root-owned and not group/world-writable")
    demand(_sha256(proof_file) == values["B_SAME_HOST_BACKUP_STORAGE_PROOF_SHA256"],
           "Backup storage proof hash mismatch")
    proof = json.loads(proof_file.read_text())
    try:
        measured_at = datetime.fromisoformat(str(proof.get("measuredAt", "")).replace("Z", "+00:00"))
        demand(measured_at.tzinfo is not None, "Backup storage proof timestamp requires a timezone")
        age = datetime.now(timezone.utc) - measured_at.astimezone(timezone.utc)
        demand(timedelta(0) <= age <= timedelta(hours=24), "Backup storage proof is stale or future-dated")
    except (ValueError, TypeError) as error:
        raise ValueError("Backup storage proof measuredAt is invalid") from error
    usage = shutil.disk_usage(mount)
    minimum_free = int(values["B_SAME_HOST_BACKUP_MIN_FREE_BYTES"])
    maximum_backup = int(values["B_SAME_HOST_BACKUP_MAX_BYTES"])
    demand(proof.get("version") == 1 and proof.get("storageRole") == "backup"
           and proof.get("mountpoint") == str(mount) and proof.get("filesystem") == filesystem
           and proof.get("provisioning") == "thick"
           and isinstance(proof.get("volumeId"), str) and proof["volumeId"].strip()
           and isinstance(proof.get("fsyncP99Ms"), (int, float)) and proof["fsyncP99Ms"] > 0
           and proof.get("capacityBytes") == usage.total
           and proof.get("requiredFreeBytes") == minimum_free
           and proof.get("maximumBackupBytes") == maximum_backup,
           "Backup proof lacks the reviewed thick/fsync/capacity/budget contract")
    demand(usage.free >= minimum_free + maximum_backup,
           "Backup mount lacks the attested floor plus maximum backup budget")
    validate_backup_attestation(values, proof, proof_file, protected_file,
                                backup_physical, protected_physical)
    evidence = {"storageRole": "backup", "mountpoint": str(mount), "volumeId": proof["volumeId"],
                "proofSha256": _sha256(proof_file), "minimumFreeBytes": minimum_free,
                "maximumBackupBytes": maximum_backup,
                "physicalVolumeIds": sorted(backup_physical),
                "protectedPhysicalVolumeIds": sorted(protected_physical)}
    if not require_root:
        return {"mount": mount, "root": root, "minimumFreeBytes": minimum_free,
                "maximumBackupBytes": maximum_backup, "proof": proof, "evidence": evidence}
    _reject_symlink_components(root)
    resolved_root = root.resolve(strict=True)
    metadata = resolved_root.stat()
    demand(resolved_root == root and resolved_root.parent == mount
           and stat.S_ISDIR(metadata.st_mode) and metadata.st_uid == 0 and metadata.st_gid == 0
           and stat.S_IMODE(metadata.st_mode) == 0o700 and metadata.st_dev == mount.stat().st_dev
           and resolved_root not in _mountpoints(),
           "Same-host backup root ownership, device or mount contract failed")
    _validate_backup_marker(resolved_root, values, proof)
    return {"mount": mount, "root": resolved_root, "minimumFreeBytes": minimum_free,
            "maximumBackupBytes": maximum_backup, "proof": proof, "evidence": evidence,
            "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
            "rootIdentity": (metadata.st_dev, metadata.st_ino)}


def validate_backup_output(values, output):
    if not compose_overlay(values):
        return None
    contract = validate_backup_mount(values)
    output = Path(output)
    demand(output.parent == contract["root"] and not output.exists() and not output.is_symlink(),
           "Same-host backup output must be a new direct child of the reviewed B-only backup root")
    return contract


def validate_journal(values, journal, mountpoints=None):
    journal = Path(journal).resolve(strict=True)
    if not compose_overlay(values):
        demand(journal.is_mount(), "Runtime journal must be the reviewed dedicated mount point")
        return
    mount = Path(values["B_SAME_HOST_MOUNT"]).resolve(strict=True)
    demand(journal == Path(values["B_SAME_HOST_DATA_ROOT"]).resolve(strict=True) / "runtime-journal"
           and journal.stat().st_dev == mount.stat().st_dev and journal.is_relative_to(mount),
           "Same-host journal must be the fixed child of the reviewed independent parent mount")
    validate_tree_with_mounts(journal, mount.stat().st_dev,
                              _mountpoints() if mountpoints is None else mountpoints)


def prepare_backup(values):
    if backup_path_plan(values, required=False) is None:
        return
    contract = validate_backup_mount(values, require_root=False)
    root = contract["root"]
    if root.exists() or root.is_symlink():
        validate_backup_mount(values)
        return
    mount_fd = os.open(contract["mount"], os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0))
    try:
        os.mkdir(root.name, mode=0o700, dir_fd=mount_fd)
        os.chown(root.name, 0, 0, dir_fd=mount_fd, follow_symlinks=False)
        os.chmod(root.name, 0o700, dir_fd=mount_fd, follow_symlinks=False)
        root_fd = os.open(root.name, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0),
                          dir_fd=mount_fd)
        try:
            marker_bytes = (json.dumps(_backup_marker_value(values, contract["proof"]),
                                       sort_keys=True, separators=(",", ":")) + "\n").encode()
            temporary = f".{BACKUP_OWNER_MARKER}.tmp-{os.getpid()}"
            marker_fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                                | getattr(os, "O_NOFOLLOW", 0), 0o600, dir_fd=root_fd)
            try:
                remaining = memoryview(marker_bytes)
                while remaining:
                    remaining = remaining[os.write(marker_fd, remaining):]
                os.fsync(marker_fd)
            finally:
                os.close(marker_fd)
            os.rename(temporary, BACKUP_OWNER_MARKER, src_dir_fd=root_fd, dst_dir_fd=root_fd)
            os.fsync(root_fd)
            os.fsync(mount_fd)
        finally:
            os.close(root_fd)
    finally:
        os.close(mount_fd)
    validate_backup_mount(values)


def prepare(values):
    demand(os.geteuid() == 0, "Same-host directory preparation requires root")
    validate_host(values, require_directories=False)
    mount = Path(values["B_SAME_HOST_MOUNT"])
    root = Path(values["B_SAME_HOST_DATA_ROOT"])
    if root.exists() or root.is_symlink():
        validate_host(values, check_backup=False)
        prepare_backup(values)
        validate_host(values)
        return
    proof = json.loads(Path(values["B_SAME_HOST_STORAGE_PROOF_FILE"]).read_text())
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0)
    mount_fd = os.open(mount, directory_flags)
    try:
        os.mkdir(root.name, mode=0o710, dir_fd=mount_fd)
        os.chown(root.name, 0, 20000, dir_fd=mount_fd, follow_symlinks=False)
        os.chmod(root.name, 0o710, dir_fd=mount_fd, follow_symlinks=False)
        root_fd = os.open(root.name, directory_flags, dir_fd=mount_fd)
        try:
            root_stat = os.fstat(root_fd)
            demand(root_stat.st_uid == 0 and root_stat.st_gid == 20000
                   and stat.S_IMODE(root_stat.st_mode) == 0o710,
                   "Existing B storage root does not match the reviewed contract")
            for contract in directory_contracts(root).values():
                name = contract.path.name
                try:
                    metadata = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                except FileNotFoundError:
                    os.mkdir(name, mode=contract.mode, dir_fd=root_fd)
                    os.chown(name, contract.uid, contract.gid, dir_fd=root_fd, follow_symlinks=False)
                    os.chmod(name, contract.mode, dir_fd=root_fd, follow_symlinks=False)
                    metadata = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                demand(stat.S_ISDIR(metadata.st_mode) and metadata.st_uid == contract.uid
                       and metadata.st_gid == contract.gid and stat.S_IMODE(metadata.st_mode) == contract.mode,
                       f"Existing directory contract failed: {name}")
                demand(not any(os.scandir(contract.path)), f"First-use directory must be strictly empty: {name}")
                child_fd = os.open(name, directory_flags, dir_fd=root_fd)
                try:
                    os.fsync(child_fd)
                finally:
                    os.close(child_fd)
            marker_bytes = (json.dumps(_marker_value(values, proof), sort_keys=True, separators=(",", ":"))
                            + "\n").encode()
            temporary = f".{OWNER_MARKER}.tmp-{os.getpid()}"
            marker_fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
                                0o600, dir_fd=root_fd)
            try:
                remaining = memoryview(marker_bytes)
                while remaining:
                    remaining = remaining[os.write(marker_fd, remaining):]
                os.fsync(marker_fd)
            finally:
                os.close(marker_fd)
            os.rename(temporary, OWNER_MARKER, src_dir_fd=root_fd, dst_dir_fd=root_fd)
            os.fsync(root_fd)
            os.fsync(mount_fd)
        finally:
            os.close(root_fd)
    finally:
        os.close(mount_fd)
    validate_host(values, check_backup=False)
    prepare_backup(values)
    validate_host(values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "prepare"))
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-target")
    args = parser.parse_args()
    demand(args.env_file.is_absolute() and args.env_file.is_file(), "Use an absolute external env file")
    values = env_values(args.env_file)
    if args.action == "prepare":
        demand(args.execute and args.confirm_target == TARGET,
               f"Explicit --execute --confirm-target {TARGET} is required")
        prepare(values)
    else:
        demand(not args.execute, "Check is read-only and does not accept --execute")
        validate_host(values)
    print("B same-host storage contract passed")


if __name__ == "__main__":
    os.umask(0o077)
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"B same-host storage refused/failed: {error}", file=sys.stderr)
        raise SystemExit(1)
