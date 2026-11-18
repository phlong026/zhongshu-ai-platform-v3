#!/usr/bin/env python3
"""Scoped B operations. Mutations are plans unless --execute is explicitly supplied."""
import argparse
import copy
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import sys
import same_host_storage as same_host

DIRECTORY = Path(__file__).resolve().parent
REPOSITORY = next((parent for parent in DIRECTORY.parents if (parent / ".git").exists()), DIRECTORY.parents[1])
PROJECT = "zhongshu-platform-b"
DATABASE = "zhongshu_platform_b"
TARGET = f"{PROJECT}/{DATABASE}"
SECRET_VARIABLES = (
    "PLATFORM_PG_BOOTSTRAP_PASSWORD_FILE", "PLATFORM_PG_OWNER_PASSWORD_FILE",
    "PLATFORM_PG_APP_PASSWORD_FILE", "PLATFORM_REDIS_APP_PASSWORD_FILE",
    "PLATFORM_REDIS_BACKUP_PASSWORD_FILE", "PLATFORM_CONFIG_FILE",
)
API_ARGUMENTS = {
    "spring.profiles.active": "pg,prod",
    "spring.config.import": "file:/etc/platform/runtime.properties,file:/run/secrets/platform.properties,configtree:/run/secrets/",
    "server.port": "48080", "server.address": "0.0.0.0",
    "spring.datasource.dynamic.datasource.master.url": f"jdbc:postgresql://postgres-b:5432/{DATABASE}",
    "spring.datasource.dynamic.datasource.master.username": "platform_app",
    "spring.data.redis.host": "redis-b", "spring.data.redis.port": "6379",
    "spring.data.redis.username": "platform_app", "spring.data.redis.database": "0",
    "spring.flyway.enabled": "false", "spring.flyway.clean-disabled": "true",
    "spring.sql.init.mode": "never", "spring.jpa.hibernate.ddl-auto": "none",
    "flowable.database-schema-update": "false", "spring.quartz.jdbc.initialize-schema": "never",
}


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def validate_image(image):
    demand(isinstance(image, str) and not any(c.isspace() for c in image), "Invalid image reference")
    demand(re.fullmatch(r"[^@]+@sha256:[0-9a-f]{64}", image), "An audited @sha256 image digest is required")


def validate_model(model, same_host_values=None):
    same_host_values = same_host_values or {}
    same_host.compose_overlay(same_host_values)
    demand(model.get("name") == PROJECT, "Only the fixed B Compose project is allowed")
    services = model.get("services", {})
    demand(set(services) == {"api", "postgres-b", "redis-b"}, "Unexpected Compose services")
    demand(set(model.get("networks", {})) == {"private"}, "Only the B private network is allowed")
    for name, network in model["networks"].items():
        demand(network.get("name") == f"{PROJECT}_{name}" and not network.get("external"), "Shared network refused")
    expected_volumes = {"pgdata", "redisdata", "asset-spool"}
    demand(set(model.get("volumes", {})) == expected_volumes, "Unexpected data volumes")
    for name, volume in model["volumes"].items():
        demand(volume.get("name") == f"{PROJECT}_{name}" and not volume.get("external"), "Shared volume refused")
    spool = model["volumes"]["asset-spool"]
    demand(spool.get("driver", "local") == "local" and not spool.get("driver_opts"), "Only a private local spool volume is allowed")
    same_host.validate_compose_volumes(model, same_host_values, {"pgdata", "redisdata"})
    allowed_binds = {
        "api": {"/etc/platform/runtime.properties": "runtime.properties"},
        "postgres-b": {"/docker-entrypoint-initdb.d/10-platform.sh": "postgres-init.sh", "/etc/platform/pg_hba.conf": "pg_hba.conf"},
        "redis-b": {"/etc/platform/redis-start.sh": "redis-start.sh", "/etc/platform/redis-health.sh": "redis-health.sh"},
    }
    expected_secrets = {
        "api": {"pg_app_password": "spring.datasource.dynamic.datasource.master.password", "redis_app_password": "spring.data.redis.password", "platform_config": "platform.properties"},
        "postgres-b": {"pg_bootstrap_password": "pg_bootstrap_password", "pg_owner_password": "pg_owner_password", "pg_app_password": "pg_app_password"},
        "redis-b": {"redis_app_password": "redis_app_password", "redis_backup_password": "redis_backup_password"},
    }
    for name, service in services.items():
        validate_image(service["image"])
        demand(not service.get("build") and service.get("pull_policy") == "never", "Production build/pull refused")
        demand(service.get("user") == ("10001:10001" if name == "api" else "999:999"), "Unexpected runtime user")
        demand(service.get("read_only") and service.get("cap_drop") == ["ALL"], "Read-only / dropped capabilities required")
        demand("no-new-privileges:true" in service.get("security_opt", []), "Privilege escalation refused")
        demand(not any(service.get(key) for key in ("privileged", "network_mode", "pid", "ipc", "devices", "cap_add", "extra_hosts")), "Host integration refused")
        demand(set(service.get("networks", {})) == {"private"}, "Unexpected service network")
        demand(all(float(service.get(key, 0)) > 0 for key in ("cpus", "mem_limit", "pids_limit")), "CPU/memory/PID limits required")
        log = service.get("logging", {})
        demand(log.get("driver") == "json-file" and log.get("options") == {"max-size": "10m", "max-file": "3"}, "Bounded logs required")
        demand(service.get("healthcheck", {}).get("test") and not service["healthcheck"].get("disable"), "Healthcheck required")
        actual_secrets = {item["source"]: item.get("target", item["source"]).removeprefix("/run/secrets/") for item in service.get("secrets", [])}
        demand(actual_secrets == expected_secrets[name], "Secret mounts violate role separation")
        required_mount = {"api": ("asset-spool", "/var/lib/platform/asset-spool"),
                          "postgres-b": ("pgdata", "/var/lib/postgresql/data"), "redis-b": ("redisdata", "/data")}[name]
        actual_volumes = []
        actual_binds = {}
        for mount in service.get("volumes", []):
            if mount["type"] == "bind":
                demand(mount.get("read_only"), "Writable host bind refused")
                actual_binds[mount["target"]] = Path(mount["source"]).resolve()
            else:
                demand(mount["type"] == "volume" and not mount.get("read_only")
                       and not mount.get("volume", {}).get("subpath"), "Unexpected persistent mount")
                actual_volumes.append((mount["source"], mount["target"]))
        demand(actual_volumes == [required_mount], "Unexpected persistent mount")
        demand(actual_binds == {target: DIRECTORY / file for target, file in allowed_binds[name].items()}, "Unexpected host file mount")
        if name != "api":
            demand(not service.get("ports"), "Database ports must not be published")
    ports = services["api"].get("ports", [])
    demand(len(ports) == 1 and ports[0].get("host_ip") == "127.0.0.1"
           and str(ports[0].get("published")) == "48081" and ports[0].get("target") == 48080, "Only loopback B API port is allowed")
    args = services["api"].get("command", [])
    demand(len(args) == len(API_ARGUMENTS) and set(args) == {f"--{key}={value}" for key, value in API_ARGUMENTS.items()}, "API connection/migration locks changed")
    for dependency in ("postgres-b", "redis-b"):
        demand(services["api"].get("depends_on", {}).get(dependency, {}).get("condition") == "service_healthy", "Healthy dependencies required")
    demand(services["postgres-b"]["image"] ==
           "postgres@sha256:5264710be20f409750ac6dc4e5706dcef929c87e241605463f0f9aefa932a8d9",
           "PostgreSQL digest changed")
    demand(services["redis-b"]["image"] ==
           "redis@sha256:44960b3100c48159a66d28c18b0588c193492e25f7551d60e51dd464fc3eca5b",
           "Redis digest changed")


def validate_secret_files(model, repository=REPOSITORY):
    values = []
    for name, config in model.get("secrets", {}).items():
        path = Path(config["file"]).resolve(strict=True)
        demand(not path.is_relative_to(repository.resolve()), f"Secret {name} must be outside the repository/release tree")
        metadata = path.stat()
        demand(path.is_file() and not (metadata.st_mode & 0o007), f"Secret {name} must not be world-accessible")
        if sys.platform.startswith("linux"):
            demand(metadata.st_gid == 20000 and metadata.st_mode & 0o040,
                   f"Secret {name} must be group-readable by the dedicated host GID 20000")
        value = path.read_bytes()
        demand(bool(value.strip()), f"Secret {name} is empty")
        if name != "platform_config":
            demand(32 <= len(value) <= 256 and all(33 <= c <= 126 for c in value) and len(set(value)) > 3,
                   f"Secret {name} must contain 32..256 printable ASCII bytes, no whitespace/newline or repeated filler")
            values.append(value)
    demand(len(values) == 5 and len(set(values)) == 5, "Database and Redis credentials must all be distinct")


def require_confirmation(action, *, execute, target, frozen):
    if execute:
        demand(target == TARGET, f"{action}: explicit --confirm-target {TARGET} is required")
        demand(frozen, f"{action}: --writes-frozen requires verified B writers/callbacks/workers are frozen")


def validate_container(container, service, model):
    labels = container.get("Config", {}).get("Labels", {})
    demand(labels.get("com.docker.compose.project") == PROJECT and labels.get("com.docker.compose.service") == service,
           "Container does not belong to the expected B project/service")
    networks = container.get("NetworkSettings", {}).get("Networks", {})
    demand(set(networks) == {f"{PROJECT}_private"}, "Container has unexpected networks")
    config = container.get("Config", {})
    expected = model["services"][service]
    demand(config.get("User") == expected["user"], "Container runtime identity differs from reviewed configuration")
    demand(not container.get("HostConfig", {}).get("Privileged"), "Privileged container refused")
    expected_binds = {mount["target"]: str(Path(mount["source"]).resolve())
                      for mount in expected.get("volumes", []) if mount["type"] == "bind"}
    for secret in expected.get("secrets", []):
        target = secret.get("target", secret["source"])
        if not target.startswith("/"):
            target = "/run/secrets/" + target
        expected_binds[target] = str(Path(model["secrets"][secret["source"]]["file"]).resolve())
    expected_volumes = {mount["target"]: model["volumes"][mount["source"]]["name"]
                        for mount in expected.get("volumes", []) if mount["type"] == "volume"}
    tmpfs_targets = {item.split(":", 1)[0] for item in expected.get("tmpfs", [])}
    actual_binds, actual_volumes = {}, {}
    for mount in container.get("Mounts", []):
        destination = mount.get("Destination")
        if mount["Type"] == "volume":
            actual_volumes[destination] = mount.get("Name")
        elif mount["Type"] == "bind":
            demand(not mount.get("RW"), "Writable host mount refused")
            actual_binds[destination] = str(Path(mount["Source"]).resolve())
        elif mount["Type"] == "tmpfs":
            demand(destination in tmpfs_targets, "Unreviewed tmpfs mount refused")
        else:
            raise ValueError("Unknown container mount type")
    demand(actual_binds == expected_binds and actual_volumes == expected_volumes,
           "Runtime mounts differ from reviewed B config; A or shared mounts are forbidden")


def run(command, *, context, timeout=60, input_data=None, stdout=None, stdin=None):
    try:
        result = subprocess.run(command, input=input_data, stdin=stdin, stdout=stdout if stdout is not None else subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f"{context}: {type(error).__name__}") from error
    if result.returncode:
        # Avoid echoing subprocess arguments/output, which can contain configuration secrets.
        raise RuntimeError(f"{context}: exit {result.returncode}; inspect only scoped B service logs")
    return result.stdout


def compose_base(env_file, values=None):
    values = same_host.env_values(env_file) if values is None else values
    return ["docker", "compose", "--project-name", PROJECT, "--env-file", str(env_file),
            "-f", str(DIRECTORY / "compose.yaml"), *same_host.compose_overlay(values)]


def load_model(env_file, values=None):
    values = same_host.env_values(env_file) if values is None else values
    # Ambient Compose options and secret variables cannot silently replace the selected env file.
    for name in list(os.environ):
        if name.startswith("COMPOSE_") or name in SECRET_VARIABLES or name == "PLATFORM_API_IMAGE" \
                or name in same_host.VARIABLES:
            del os.environ[name]
    enabled = bool(same_host.compose_overlay(values))
    if enabled:
        same_host.validate_host(values)
    model = json.loads(run(compose_base(env_file, values) + ["config", "--format", "json"],
                           context="Parse B Compose", timeout=30))
    validate_model(model, values if enabled else {})
    validate_secret_files(model)
    return model


def inspect_scope(base, model, require_frozen=False):
    ids = run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}"], context="Inspect B project").decode().split()
    demand(ids, "No B containers found")
    containers = json.loads(run(["docker", "inspect", *ids], context="Inspect B containers"))
    seen = set()
    for container in containers:
        service = container.get("Config", {}).get("Labels", {}).get("com.docker.compose.service")
        demand(service in model["services"] and service not in seen, "Unexpected or duplicate B service container")
        seen.add(service)
        validate_container(container, service, model)
        if require_frozen and service == "api":
            demand(not container.get("State", {}).get("Running"), "Stop only B API before this operation")
        if service in ("postgres-b", "redis-b"):
            demand(container.get("State", {}).get("Health", {}).get("Status") == "healthy", f"{service} is not healthy")
    demand({"postgres-b", "redis-b"} <= seen, "B database containers are missing")
    volume_items = []
    for kind, names in (("volume", tuple(model["volumes"])), ("network", ("private",))):
        for name in names:
            item = json.loads(run(["docker", kind, "inspect", f"{PROJECT}_{name}"], context=f"Inspect B {kind}"))[0]
            labels = item.get("Labels") or {}
            demand(labels.get("com.docker.compose.project") == PROJECT
                   and labels.get(f"com.docker.compose.{kind}") == name, f"{kind} is not owned by B Compose")
            if kind == "volume":
                volume_items.append(item)
    same_host.validate_actual_volumes_against_model(volume_items, model)


def pg(base, arguments, **kwargs):
    script = 'export PGPASSWORD="$(cat /run/secrets/pg_owner_password)"; export PGCONNECT_TIMEOUT=5; exec "$@"'
    return run(base + ["exec", "-T", "postgres-b", "sh", "-ec", script, "platform-pg", *arguments],
               context="B PostgreSQL owner operation", timeout=1800, **kwargs)


def psql(base, sql):
    return pg(base, ["psql", "-X", "-h", "127.0.0.1", "-U", "platform_owner", "-d", DATABASE,
                     "-v", "ON_ERROR_STOP=1", "-At"], input_data=sql.encode())


def enforce_database_policy(base):
    psql(base, (DIRECTORY / "postgres-policy.sql").read_text())


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def backup(base, directory):
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    # A failure leaves an incomplete directory without manifest; it cannot pass restore validation.
    with (directory / "postgres.dump").open("xb") as stream:
        pg(base, ["pg_dump", "-h", "127.0.0.1", "-U", "platform_owner", "-d", DATABASE,
                  "--format=custom", "--no-owner", "--no-privileges"], stdout=stream)
    redis_script = ('export REDISCLI_AUTH="$(cat /run/secrets/redis_backup_password)"; '
                    'exec redis-cli --no-auth-warning --user platform_backup --rdb /dev/stdout')
    with (directory / "redis.rdb").open("xb") as stream:
        run(base + ["exec", "-T", "redis-b", "sh", "-ec", redis_script],
            context="B Redis snapshot", timeout=1800, stdout=stream)
    manifest = {"project": PROJECT, "database": DATABASE, "writes_frozen": True,
                "files": {name: sha256(directory / name) for name in ("postgres.dump", "redis.rdb")}}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    logging.warning("B backup completed; manifest SHA256=%s; off-host copy and restore rehearsal still required", sha256(directory / "manifest.json"))


def verify_backup(directory, confirmation):
    manifest_path = directory / "manifest.json"
    demand(confirmation and sha256(manifest_path) == confirmation, "Explicit trusted backup manifest SHA256 required")
    manifest = json.loads(manifest_path.read_text())
    demand(manifest.get("project") == PROJECT and manifest.get("database") == DATABASE and manifest.get("writes_frozen") is True,
           "Backup does not identify the frozen B database")
    demand(set(manifest.get("files", {})) == {"postgres.dump", "redis.rdb"}, "Unexpected backup file manifest")
    for name, digest in manifest["files"].items():
        demand(not (directory / name).is_symlink() and sha256(directory / name) == digest, "Backup checksum mismatch")


def validate_backup_location(action, values, directory):
    if action == "backup" and same_host.compose_overlay(values):
        raise ValueError("Same-host complete deployment requires full-backup.py; partial ops backup is refused")


def restore(base, directory):
    # Restore only to a fresh B database: never DROP, TRUNCATE or pg_restore --clean.
    count = psql(base, """SELECT count(*) FROM pg_namespace n
        WHERE left(n.nspname, 3) <> 'pg_' AND n.nspname <> 'information_schema'
          AND (n.nspname <> 'public'
               OR EXISTS (SELECT 1 FROM pg_class c WHERE c.relnamespace = n.oid)
               OR EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = n.oid)
               OR EXISTS (SELECT 1 FROM pg_type t WHERE t.typnamespace = n.oid));""").decode().strip()
    demand(count == "0", "Restore refused: B target database contains objects")
    with (directory / "postgres.dump").open("rb") as stream:
        pg(base, ["pg_restore", "-h", "127.0.0.1", "-U", "platform_owner", "-d", DATABASE,
                  "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges"], stdin=stream)
    enforce_database_policy(base)
    logging.warning("B PostgreSQL restored; API stays stopped. Redis recovery and ledger/channel checks remain mandatory")


def validate_migration_model(migration, model):
    expected = copy.deepcopy(model)
    api = expected["services"]["api"]
    api.pop("ports", None)
    api.pop("depends_on", None)
    api["restart"] = "no"
    api["healthcheck"]["disable"] = True
    api["secrets"] = [{"source": "pg_owner_password", "target": "spring.flyway.password"},
                      {"source": "platform_config", "target": "platform.properties"}]
    api["command"] = [
        "migrate", "--spring.profiles.active=pg,migration",
        "--spring.config.import=" + API_ARGUMENTS["spring.config.import"],
        f"--spring.flyway.url=jdbc:postgresql://postgres-b:5432/{DATABASE}",
        "--spring.flyway.user=platform_owner", "--spring.flyway.clean-disabled=true",
        "--spring.flyway.validate-on-migrate=true", "--spring.flyway.baseline-on-migrate=false",
    ]
    demand(migration == expected, "Migration override changed unreviewed connection, identity or isolation settings")


def migration_command(base, model):
    scoped = base + ["-f", str(DIRECTORY / "compose.migrate.yaml")]
    migration = json.loads(run(scoped + ["config", "--format", "json"], context="Validate B migration Compose", timeout=30))
    validate_migration_model(migration, model)
    run(scoped + ["run", "--rm", "--no-deps", "--pull", "never", "api"], context="B owner-only Flyway migration", timeout=1800)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("config", "policy", "backup", "restore", "migrate", "rollback"))
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-target")
    parser.add_argument("--writes-frozen", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--confirm-sha256")
    parser.add_argument("--image")
    parser.add_argument("--schema-compatible", action="store_true")
    args = parser.parse_args()
    require_confirmation(args.action, execute=args.execute, target=args.confirm_target, frozen=args.writes_frozen)
    demand(args.env_file.is_absolute() and args.env_file.is_file(), "Use an absolute external env file")
    demand(not args.env_file.resolve().is_relative_to(REPOSITORY), "env file must be external")
    demand(not (args.env_file.stat().st_mode & 0o007), "env file must not be world-accessible")
    values = same_host.env_values(args.env_file)
    model = load_model(args.env_file, values)
    base = compose_base(args.env_file, values)
    if args.action == "config":
        logging.warning("B Compose and external-file policy passed; no daemon or runtime test performed")
        return
    if args.action in ("backup", "restore"):
        demand(args.backup_dir is not None and args.backup_dir.is_absolute(), "Absolute --backup-dir is required")
        demand(not args.backup_dir.resolve().is_relative_to(REPOSITORY), "Backups must be outside the release tree")
    if args.action in ("backup", "restore"):
        validate_backup_location(args.action, values, args.backup_dir)
    if args.action == "restore":
        verify_backup(args.backup_dir, args.confirm_sha256)
    if args.action == "rollback":
        validate_image(args.image)
        demand(args.schema_compatible, "Confirm previous image is compatible with current B schema/data using --schema-compatible")
    if not args.execute:
        logging.warning("PLAN ONLY: %s for %s; no Docker daemon call or mutation", args.action, TARGET)
        return
    inspect_scope(base, model, require_frozen=True)
    logging.warning("Executing explicitly confirmed B operation: %s", args.action)
    if args.action == "backup":
        backup(base, args.backup_dir)
    elif args.action == "restore":
        restore(base, args.backup_dir)
    elif args.action == "policy":
        enforce_database_policy(base)
    elif args.action == "migrate":
        migration_command(base, model)
        enforce_database_policy(base)
    elif args.action == "rollback":
        run(["docker", "image", "inspect", args.image], context="Verify preloaded rollback image")
        os.environ["PLATFORM_API_IMAGE"] = args.image
        run(base + ["up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "api"],
            context="Replace only B API image", timeout=210)
        logging.warning("B API image replaced; data, traffic, payment and A checks still require release sign-off")


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    os.umask(0o077)
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        logging.error("B operation refused/failed: %s", error)
        sys.exit(1)
