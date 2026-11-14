#!/usr/bin/env python3
"""Create/verify a frozen, secret-free B backup set. It never restores or starts/stops services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

HERE = Path(__file__).resolve().parent
REPO = next(parent for parent in HERE.parents if (parent / ".git").exists())
PROJECT = "zhongshu-platform-b"


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(command, context, *, stdout=None, timeout=1800, environment=None):
    result = subprocess.run(command, stdout=stdout or subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, check=False, env=environment)
    if result.returncode:
        raise RuntimeError(f"{context}: exit {result.returncode}; inspect scoped B service logs")
    return result.stdout


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


def selected_env(path, ambient=None):
    ambient = os.environ if ambient is None else ambient
    values = env_values(path)
    for key, value in values.items():
        demand(key not in ambient or ambient[key] == value,
               f"Selected env file conflicts with ambient variable: {key}")
    demand(not any(key.startswith("COMPOSE_") for key in ambient), "Ambient COMPOSE_* variables are refused")
    environment = {key: ambient[key] for key in ("PATH", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONTEXT",
                                                  "DOCKER_CONFIG") if key in ambient}
    environment.update(values)
    return values, environment


def reject_sensitive_keys(value):
    if isinstance(value, dict):
        for key, child in value.items():
            demand(not re.search(r"secret|password|credential|token|signature|signed.?url", str(key), re.I),
                   "COS inventory contains a credential-like field")
            reject_sensitive_keys(child)
    elif isinstance(value, list):
        for child in value:
            reject_sensitive_keys(child)


def parse_time(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    demand(parsed.tzinfo is not None, "COS/freeze timestamps must include a timezone")
    return parsed.astimezone(timezone.utc)


def validate_cos_inventory(cos, expected_bucket, freeze_started_at, observed_at):
    demand(cos.get("versioningEnabled") is True and cos.get("bucket") and cos.get("generatedAt"),
           "COS inventory must identify bucket, generation time and enabled versioning")
    demand(cos["bucket"] == expected_bucket, "COS inventory bucket is not the expected B bucket")
    generated = parse_time(cos["generatedAt"])
    demand(parse_time(freeze_started_at) <= generated <= parse_time(observed_at),
           "COS inventory was not generated inside this B freeze window")
    objects = cos.get("objects")
    demand(isinstance(objects, list), "COS inventory must contain an objects list (use [] for an empty bucket)")
    for item in objects:
        demand(isinstance(item, dict) and item.get("key") and item.get("versionId")
               and isinstance(item.get("size"), int) and item["size"] >= 0
               and re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")),
               "Every COS object requires key, versionId, non-negative size and SHA-256")
    reject_sensitive_keys(cos)


def compose(env_file):
    return ["docker", "compose", "--project-name", PROJECT, "--env-file", str(env_file),
            "-f", str(HERE / "compose.yaml"), "-f", str(HERE / "compose.capabilities.yaml")]


def freeze_evidence(rows):
    writers = {"api", "design-provider", "ai-runtime"}
    data_services = {"postgres-b", "redis-b", "postgres-design", "redis-design"}
    migration = "design-provider-migrate"
    expected = writers | data_services | {migration}
    demand(len(rows) == len(expected) and {row.get("Service") for row in rows} == expected,
           "All exact B writer, migration and data containers must exist once")
    by_service = {row["Service"]: row for row in rows}
    for service in writers:
        row = by_service[service]
        demand(row.get("ID") and row.get("State") == "exited" and row.get("FinishedAt"),
               f"{service} must exist and be precisely exited")
    row = by_service[migration]
    demand(row.get("ID") and row.get("State") == "exited" and int(row.get("ExitCode", -1)) == 0,
           "Candidate Provider migration must exist, be exited and have exit code 0")
    for service in data_services:
        row = by_service[service]
        demand(row.get("ID") and row.get("State") == "running" and row.get("Health") == "healthy",
               f"{service} must be running and healthy")
    for service in expected:
        demand(re.fullmatch(r"[^@]+@sha256:[0-9a-f]{64}", by_service[service].get("ImageRef", ""))
               and str(by_service[service].get("ImageID", "")).startswith("sha256:"),
               f"{service} lacks digest-bound actual image evidence")
    demand(by_service[migration]["ImageRef"] == by_service["design-provider"]["ImageRef"]
           and by_service[migration]["ImageID"] == by_service["design-provider"]["ImageID"],
           "Successful migration is not bound to the running Provider candidate")
    freeze_started = max(parse_time(by_service[service]["FinishedAt"]) for service in writers).isoformat()
    evidence = {service: {key: by_service[service].get(key) for key in
                          ("ID", "State", "Health", "ExitCode", "FinishedAt", "ImageRef", "ImageID")}
                for service in sorted(expected)}
    return {"freezeStartedAt": freeze_started, "containers": evidence}


def parse_ps(output):
    if not output.strip():
        return []
    decoded = output.decode()
    try:
        value = json.loads(decoded)
        return value if isinstance(value, list) else [value]
    except json.JSONDecodeError:
        return [json.loads(line) for line in decoded.splitlines() if line.strip()]


def require_frozen(base, environment):
    services = ("api", "design-provider", "ai-runtime", "design-provider-migrate", "postgres-b", "redis-b",
                "postgres-design", "redis-design")
    rows = parse_ps(run(base + ["ps", "--all", "--format", "json", *services], "Inspect full B freeze",
                        environment=environment))
    ids = [row.get("ID") for row in rows if row.get("ID")]
    demand(len(ids) == len(services), "All exact B containers must be present")
    inspected = json.loads(run(["docker", "inspect", *ids], "Inspect B container image/state evidence",
                               environment=environment))
    by_id = {item["Id"]: item for item in inspected}
    for row in rows:
        item = next((value for identifier, value in by_id.items() if identifier.startswith(row["ID"])), None)
        demand(item is not None, "Compose container ID did not match Docker inspect")
        row["State"] = "running" if item["State"]["Running"] else "exited"
        row["ExitCode"] = item["State"].get("ExitCode")
        row["FinishedAt"] = item["State"].get("FinishedAt")
        row["ImageRef"] = item["Config"].get("Image")
        row["ImageID"] = item.get("Image")
    return freeze_evidence(rows)


def pg_dump(base, service, user, database, secret, target, environment):
    script = f'export PGPASSWORD="$(cat /run/secrets/{secret})"; exec pg_dump -h 127.0.0.1 -U {user} -d {database} --format=custom --no-owner --no-privileges'
    with target.open("xb") as stream:
        run(base + ["exec", "-T", service, "sh", "-ec", script], f"Backup {database}", stdout=stream,
            environment=environment)


def redis_dump(base, service, user, secret, target, environment):
    script = (f'export REDISCLI_AUTH="$(cat /run/secrets/{secret})"; '
              'snapshot=$(mktemp /data/.full-backup.XXXXXX); trap \'rm -f "$snapshot"\' EXIT; '
              f'redis-cli --no-auth-warning --user {user} --rdb "$snapshot" >/dev/null; cat "$snapshot"')
    with target.open("xb") as stream:
        run(base + ["exec", "-T", service, "sh", "-ec", script], f"Backup {service}", stdout=stream,
            environment=environment)


def journal_archive(source, target):
    demand(source.is_dir() and source.is_mount(), "Runtime journal must be the reviewed dedicated mount point")
    with tarfile.open(target, "x:gz", format=tarfile.PAX_FORMAT) as archive:
        for path in sorted(source.rglob("*")):
            demand(not path.is_symlink(), "Runtime journal symlinks are refused")
            archive.add(path, arcname=path.relative_to(source), recursive=False)


def backup(args):
    demand(args.confirm_target == f"{PROJECT}/full" and args.writes_frozen, "Explicit frozen B target confirmation required")
    demand(args.output.is_absolute() and not args.output.resolve().is_relative_to(REPO.resolve()),
           "Backup directory must be absolute and outside source/release trees")
    demand(not args.output.exists() and args.output.parent.is_dir(),
           "Backup directory must be a new child of an existing external directory")
    values, environment = selected_env(args.env_file)
    journal = Path(values.get("AI_RUNTIME_JOURNAL_DIR", "")).resolve(strict=True)
    inventory = args.cos_inventory.resolve(strict=True)
    demand(inventory.is_file() and not inventory.is_symlink(), "COS version inventory is required")
    inventory_bytes = inventory.read_bytes()
    cos = json.loads(inventory_bytes)
    demand(values.get("COS_B_BUCKET"), "Expected B COS bucket is required in the selected env file")
    base = compose(args.env_file)
    json.loads(run(base + ["config", "--format", "json"], "Render full B Compose", timeout=60,
                   environment=environment))
    before = require_frozen(base, environment)
    validate_cos_inventory(cos, values["COS_B_BUCKET"], before["freezeStartedAt"],
                           datetime.now(timezone.utc).isoformat())
    args.output.mkdir(mode=0o700)
    pg_dump(base, "postgres-b", "platform_owner", "zhongshu_platform_b", "pg_owner_password",
            args.output / "platform-postgres.dump", environment)
    pg_dump(base, "postgres-design", "design_provider_owner", "zhongshu_design_provider", "design_pg_owner_password",
            args.output / "provider-postgres.dump", environment)
    redis_dump(base, "redis-b", "platform_backup", "redis_backup_password", args.output / "platform-redis.rdb", environment)
    redis_dump(base, "redis-design", "design_provider_backup", "design_redis_backup_password",
               args.output / "provider-redis.rdb", environment)
    journal_archive(journal, args.output / "runtime-journal.tar.gz")
    after = require_frozen(base, environment)
    demand(after == before, "B freeze/container evidence changed during snapshot")
    (args.output / "cos-version-inventory.json").write_bytes(inventory_bytes)
    names = ("platform-postgres.dump", "provider-postgres.dump", "platform-redis.rdb", "provider-redis.rdb",
             "runtime-journal.tar.gz", "cos-version-inventory.json")
    manifest = {"project": PROJECT, "scope": "full", "writesFrozen": True,
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "images": {service: before["containers"][service] for service in
                           ("api", "design-provider", "ai-runtime", "design-provider-migrate")},
                "freezeEvidence": before,
                "files": {name: sha256(args.output / name) for name in names},
                "note": "Secret/config files are intentionally excluded"}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(sha256(args.output / "manifest.json"))


def verify(args):
    manifest = json.loads((args.output / "manifest.json").read_text())
    demand(manifest.get("project") == PROJECT and manifest.get("scope") == "full" and manifest.get("writesFrozen") is True,
           "Not a frozen full B backup")
    demand(args.confirm_sha256 == sha256(args.output / "manifest.json"), "Trusted manifest SHA256 required")
    expected = {"platform-postgres.dump", "provider-postgres.dump", "platform-redis.rdb", "provider-redis.rdb",
                "runtime-journal.tar.gz", "cos-version-inventory.json"}
    demand(set(manifest.get("files", {})) == expected, "Backup set is incomplete")
    for name, digest in manifest["files"].items():
        path = args.output / name
        demand(path.is_file() and not path.is_symlink() and sha256(path) == digest, f"Checksum mismatch: {name}")
    print("Frozen full B backup verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "verify"))
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cos-inventory", type=Path)
    parser.add_argument("--writes-frozen", action="store_true")
    parser.add_argument("--confirm-target")
    parser.add_argument("--confirm-sha256")
    args = parser.parse_args()
    demand(args.env_file.is_absolute() and args.env_file.is_file(), "Use an absolute external deployment env file")
    if args.action == "backup":
        demand(args.cos_inventory is not None, "COS version inventory is required")
        backup(args)
    else:
        verify(args)


if __name__ == "__main__":
    os.umask(0o077)
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"Full B backup refused/failed: {error}", file=sys.stderr)
        raise SystemExit(1)
