#!/usr/bin/env python3
"""Verify the actual Boot JAR, including nested libraries, and emit a migration manifest."""
import argparse
import hashlib
import io
import json
import logging
import re
import sys
import zipfile
from pathlib import Path

FORBIDDEN_ARTIFACTS = (
    "yudao-server", "yudao-module-identity", "yudao-module-design",
    "yudao-module-commerce", "yudao-module-ai-orchestration",
)
FORBIDDEN_PACKAGES = (
    "cn/iocoder/yudao/server/", "cn/iocoder/yudao/module/identity/",
    "cn/iocoder/yudao/module/design/", "cn/iocoder/yudao/module/commerce/",
    "cn/iocoder/yudao/module/aiorchestration/",
)
REQUIRED_ARTIFACTS = {
    "yudao-module-system", "yudao-module-infra", "yudao-platform-identity",
    "platform-wallet", "yudao-domain-franchise-content", "yudao-domain-franchise-asset",
}
LOCATIONS = {
    "platform-bootstrap", "platform", "pf", "franchise", "franchise-system",
    "platform-wallet", "franchise-asset", "franchise-design-remote",
}
START_CLASS = "cn.iocoder.yudao.platformserver.YudaoPlatformServerApplication"


def verify(path):
    migrations = []
    versions = {}
    locations_found = set()
    libraries = set()

    def inspect(archive, origin, prefix=""):
        for name in archive.namelist():
            if name.endswith("/") or not name.startswith(prefix):
                continue
            resource = name[len(prefix):]
            if any(resource.startswith(package) for package in FORBIDDEN_PACKAGES):
                raise ValueError(f"Forbidden implementation in {origin}: {resource}")
            if resource == "META-INF/spring.factories":
                factories = archive.read(name).decode("utf-8")
                if "cn.iocoder.yudao.server." in factories:
                    raise ValueError(f"Fusion environment processor in {origin}")
            for migration_root in ("db/migration/", "db/migration-undo/"):
                if not resource.startswith(migration_root) or not resource.endswith((".sql", ".class")):
                    continue
                # Inner classes belong to their enclosing Java migration, not another Flyway version.
                if resource.endswith(".class") and "$" in resource.rsplit("/", 1)[1]:
                    continue
                location = resource[len(migration_root):].split("/", 1)[0]
                if location not in LOCATIONS:
                    raise ValueError(f"Forbidden migration in {origin}: {resource}")
                if migration_root != "db/migration/":
                    continue
                locations_found.add(location)
                filename = resource.rsplit("/", 1)[1]
                if not filename.startswith("V") or "__" not in filename:
                    raise ValueError(f"Unversioned migration in {origin}: {resource}")
                version = filename[1:].split("__", 1)[0]
                normalized = tuple(int(part) for part in re.split(r"[._]", version))
                if normalized in versions:
                    raise ValueError(f"Duplicate Flyway version {version}: {versions[normalized]} and {origin}!/{resource}")
                versions[normalized] = f"{origin}!/{resource}"
                migrations.append({"version": version, "source": origin, "resource": resource,
                                   "sha256": hashlib.sha256(archive.read(name)).hexdigest()})

    with zipfile.ZipFile(path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
        if f"Start-Class: {START_CLASS}\n" not in manifest + "\n":
            raise ValueError("Not the B executable Start-Class")
        if "BOOT-INF/classes/" + START_CLASS.replace(".", "/") + ".class" not in archive.namelist():
            raise ValueError("Missing B application class")
        # Boot's repackager preserves META-INF at the executable archive root.
        factory_paths = ("META-INF/spring.factories", "BOOT-INF/classes/META-INF/spring.factories")
        factories = "\n".join(archive.read(name).decode("utf-8") for name in factory_paths
                              if name in archive.namelist())
        if "cn.iocoder.yudao.platformserver.wiring.PlatformEnvironmentPostProcessor" not in factories:
            raise ValueError("Missing B environment guard registration")
        config = archive.read("BOOT-INF/classes/application-pg.properties").decode("utf-8")
        configured = next((line.partition("=")[2].strip() for line in config.splitlines()
                           if line.startswith("spring.flyway.locations=")), "")
        expected = {"classpath:db/migration/" + item for item in LOCATIONS}
        if set(configured.split(",")) != expected:
            raise ValueError("Packaged Flyway locations differ from B allowlist")
        inspect(archive, path.name)
        inspect(archive, path.name, "BOOT-INF/classes/")
        for name in archive.namelist():
            if not name.startswith("BOOT-INF/lib/") or not name.endswith(".jar"):
                continue
            library = name.rsplit("/", 1)[1]
            if any(library.startswith(artifact + "-") for artifact in FORBIDDEN_ARTIFACTS):
                raise ValueError(f"Forbidden runtime dependency: {library}")
            libraries.update(item for item in REQUIRED_ARTIFACTS if library.startswith(item + "-"))
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                inspect(nested, library)
    if libraries != REQUIRED_ARTIFACTS:
        raise ValueError(f"Missing B runtime dependencies: {sorted(REQUIRED_ARTIFACTS - libraries)}")
    if locations_found != LOCATIONS:
        raise ValueError(f"Missing B migration locations: {sorted(LOCATIONS - locations_found)}")
    return {"artifact": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "migrations": sorted(migrations, key=lambda item: item["resource"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.jar)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        logging.error("B artifact verification failed: %s", error)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
