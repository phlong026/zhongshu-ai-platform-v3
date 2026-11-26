#!/usr/bin/env python3
"""Offline P3 inventory and synthetic evidence checks; no database or execution path."""
import argparse
import hashlib
import json
import logging
from pathlib import Path
import re
import sys

DIRECTORY = Path(__file__).resolve().parent
MANIFEST_FORMAT = "zhongshu-platform-ownership/v1"
FIXTURE_FORMAT = "zhongshu-platform-synthetic/v1"
STANDALONE_MODULES = ("yudao-platform-server", "yudao-module-infra", "yudao-platform-operations")
LOCATIONS = (
    "platform-bootstrap", "platform", "pf", "franchise", "franchise-system",
    "platform-wallet", "franchise-asset", "franchise-design-remote",
)
CREATE_TABLE = re.compile(r"\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z0-9_]+)", re.I)
CREATE_SEQUENCE = re.compile(r"\bCREATE\s+SEQUENCE\s+([A-Za-z0-9_]+)", re.I)
IMPLICIT_SEQUENCE_COLUMN = re.compile(
    r"[(,]\s*([A-Za-z_][A-Za-z0-9_]*)\s+"
    r"(?:SMALLSERIAL|BIGSERIAL|SERIAL(?:2|4|8)?|"
    r"(?:SMALLINT|INTEGER|BIGINT|INT(?:2|4|8)?)\s+GENERATED\s+(?:ALWAYS|BY\s+DEFAULT)\s+AS\s+IDENTITY)\b",
    re.I,
)
STRATEGIES = {"filtered-copy", "rebuild", "retain-source", "transform", "subset-review"}
WRITERS = {
    "platform-http", "franchise-http", "legacy-session", "identity-provision",
    "system-mutations", "system-events", "commerce-wallet", "commerce-payment",
    "design-driver", "ai-cancel-runtime", "content-jobs", "outbox-export-privacy", "remote-recovery",
}


class EvidenceError(ValueError):
    """An offline check failed; callers must not treat missing evidence as zero."""


def demand(condition, code):
    if not condition:
        raise EvidenceError(code)


def read_json(path):
    demand(path.suffix == ".json" and path.stat().st_size <= 16 * 1024 * 1024, "EVIDENCE_FILE_INVALID")
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def migration_paths(root):
    """Only source modules shipped in B; yudao-server's same-name location is excluded."""
    modules = [root / name for name in STANDALONE_MODULES]
    modules += list((root / "platform").glob("*"))
    modules += list((root / "domains/franchise").glob("*"))
    paths = []
    for module in modules:
        for location in LOCATIONS:
            paths.extend((module / "src/main/resources/db/migration" / location).glob("V*.sql"))
    return sorted(paths)


def valid_migration_path(value, location):
    demand(isinstance(value, str), "MIGRATION_PATH_INVALID")
    path = Path(value)
    parts = path.parts
    demand(not path.is_absolute() and ".." not in parts and path.suffix == ".sql", "MIGRATION_PATH_INVALID")
    demand("/src/main/resources/db/migration/" in value and path.parent.name == location,
           "MIGRATION_PATH_INVALID")
    demand(parts[0] in STANDALONE_MODULES or value.startswith(("platform/", "domains/franchise/")),
           "MIGRATION_PATH_INVALID")


def validate_manifest(manifest, source_root=None):
    demand(manifest.get("format") == MANIFEST_FORMAT, "MANIFEST_FORMAT_INVALID")
    locations = manifest.get("locations", [])
    demand(len(locations) == len(LOCATIONS) and set(locations) == set(LOCATIONS), "MIGRATION_LOCATIONS_INVALID")
    expected, versions = {}, set()
    for item in manifest["migrations"]:
        location = item["location"]
        demand(location in LOCATIONS, "MIGRATION_LOCATION_INVALID")
        valid_migration_path(item["path"], location)
        demand(item["path"] not in expected and item["version"] not in versions, "DUPLICATE_MIGRATION")
        demand(re.fullmatch(r"[0-9a-f]{64}", item["source_sha256"]) is not None, "MIGRATION_DIGEST_INVALID")
        expected[item["path"]] = item["source_sha256"]
        versions.add(item["version"])
    demand(bool(expected), "MIGRATIONS_MISSING")
    tables = set()
    for table in manifest["tables"]:
        demand(table["owner"] in {"A", "B", "SHARED"}, "TABLE_OWNER_INVALID")
        demand(table["strategy"] in STRATEGIES and bool(table["selector"]), "TABLE_POLICY_INVALID")
        demand(table["name"] not in tables, "DUPLICATE_TABLE")
        demand(bool(table["source_migrations"]) and set(table["source_migrations"]) <= expected.keys(),
               "TABLE_MIGRATION_MISSING")
        tables.add(table["name"])
    demand(bool(tables), "TABLES_MISSING")
    sequence_names = set()
    for sequence in manifest["sequences"]:
        demand(sequence["name"] not in sequence_names, "DUPLICATE_SEQUENCE")
        demand(sequence["source_migration"] in expected, "SEQUENCE_MIGRATION_MISSING")
        demand(sequence["catalog_verified"] is False, "STATIC_SEQUENCE_CANNOT_CLAIM_CATALOG")
        sequence_names.add(sequence["name"])
    if source_root is not None:
        root = source_root.resolve(strict=True)
        actual = {path.relative_to(root).as_posix(): path for path in migration_paths(root)}
        demand(actual.keys() == expected.keys(), "MIGRATION_SET_CHANGED")
        found_tables, found_sequences = set(), set()
        for relative, path in actual.items():
            demand(path.resolve().is_relative_to(root), "MIGRATION_PATH_INVALID")
            data = path.read_bytes()
            demand(hashlib.sha256(data).hexdigest() == expected[relative],
                   "MIGRATION_HASH_CHANGED")
            text = re.sub(r"--[^\n]*", "", data.decode("utf-8"))
            text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
            table_matches = list(CREATE_TABLE.finditer(text))
            found_tables.update(match.group(1).lower() for match in table_matches)
            found_sequences.update(name.lower() for name in CREATE_SEQUENCE.findall(text))
            for index, match in enumerate(table_matches):
                end = table_matches[index + 1].start() if index + 1 < len(table_matches) else len(text)
                for column in IMPLICIT_SEQUENCE_COLUMN.findall(text[match.end():end]):
                    found_sequences.add(f"{match.group(1)}_{column}_seq".lower())
        demand(tables == found_tables, "TABLE_COVERAGE_CHANGED")
        demand(sequence_names == found_sequences, "SEQUENCE_COVERAGE_CHANGED")


def indexed(records, key, code):
    result = {}
    for row in records:
        value = row[key]
        demand(isinstance(value, str) and bool(value), code)
        demand(value not in result, code)
        result[value] = row
    return result


def validate_fixture(fixture):
    demand(fixture.get("format") == FIXTURE_FORMAT and fixture.get("synthetic") is True, "SYNTHETIC_ONLY")
    orgs = set(fixture["b_orgs"])
    demand(bool(orgs) and "0" not in orgs, "ORG_SCOPE_INVALID")
    accounts = indexed(fixture["accounts"], "id", "DUPLICATE_ACCOUNT")
    for account in accounts.values():
        demand(account["app"] in {"A", "B"}, "UNKNOWN_ACCOUNT_OWNER")
        if account["app"] == "B":
            demand(account["org_id"] in orgs, "ACCOUNT_ORG_OUTSIDE_SCOPE")
    balances = indexed(fixture["balances"], "account_id", "DUPLICATE_BALANCE")
    for account_id, balance in balances.items():
        demand(account_id in accounts, "UNKNOWN_ACCOUNT_OWNER")
        demand(all(type(balance[key]) is int and balance[key] >= 0 for key in ("available", "reserved")),
               "BALANCE_INVALID")
        if accounts[account_id]["app"] == "B":
            demand(balance["available"] == 0 and balance["reserved"] == 0, "NONZERO_B_FUNDS")
    demand(all(account_id in balances for account_id, account in accounts.items() if account["app"] == "B"),
           "MISSING_B_BALANCE")
    obligations = indexed(fixture["obligations"], "id", "DUPLICATE_OBLIGATION")
    for obligation in obligations.values():
        demand(obligation["account_id"] in accounts, "UNKNOWN_ACCOUNT_OWNER")
        demand(obligation.get("executor") in {"A", "B_OLD", "B_NEW"} and bool(obligation.get("watermark")),
               "OBLIGATION_OWNER_WATERMARK")
        demand(accounts[obligation["account_id"]]["app"] == "A", "B_OBLIGATION_REQUIRES_REVIEW")
        demand(obligation["executor"] == "A", "OBLIGATION_OWNER_MISMATCH")
    bindings = indexed(fixture["bindings"], "id", "DUPLICATE_BINDING")
    users = indexed(fixture["users"], "id", "DUPLICATE_USER")
    events = indexed(fixture["binding_events"], "id", "DUPLICATE_BINDING_EVENT")
    for event in events.values():
        demand(event["org_id"] in orgs, "BINDING_ORG_OUTSIDE_SCOPE")
        demand(event["user_id"] in users and event["org_id"] in users[event["user_id"]]["historical_orgs"],
               "BINDING_USER_ORG_MISMATCH")
        for field in ("binding_id", "previous_binding_id"):
            if event.get(field) is not None:
                demand(event[field] in bindings, "BINDING_REFERENCE_MISSING")
                demand(bindings[event[field]]["user_id"] == event["user_id"], "BINDING_USER_MISMATCH")
    assets = indexed(fixture["assets"], "id", "DUPLICATE_ASSET")
    for asset in assets.values():
        demand(asset["app"] in {"A", "B"}, "UNKNOWN_ASSET_OWNER")
    for reference in fixture["references"]:
        demand(reference["asset_id"] in assets, "ASSET_REFERENCE_MISSING")
        asset = assets[reference["asset_id"]]
        demand(asset["app"] == reference["app"] and asset["org_id"] == reference["org_id"],
               "ASSET_OWNER_MISMATCH")
        demand(asset["status"] == "ACCEPTED", "ASSET_NOT_ACCEPTED")
        demand(reference["kind"] in {"avatar", "business"}, "ASSET_REFERENCE_KIND_UNKNOWN")
        if reference["app"] == "B":
            demand(reference["org_id"] in orgs, "ASSET_ORG_OUTSIDE_SCOPE")
            demand(reference["kind"] == "business", "B_AVATAR_TRANSFORM_UNDEFINED")
    sequences = indexed(fixture["sequences"], "name", "DUPLICATE_SEQUENCE")
    for sequence in sequences.values():
        demand(all(type(sequence[key]) is int for key in ("last_value", "increment", "max_id"))
               and type(sequence["is_called"]) is bool and sequence["increment"] > 0, "SEQUENCE_STATE_INVALID")
        next_value = sequence["last_value"] + (sequence["increment"] if sequence["is_called"] else 0)
        demand(next_value > sequence["max_id"], "SEQUENCE_COLLISION")


def validate_coverage(coverage, source_root=None):
    """Validate the G1 candidate registry, never attest live writer coverage."""
    demand(coverage.get("format") == "zhongshu-legacy-b-writer-coverage/v1", "COVERAGE_FORMAT_INVALID")
    demand(coverage.get("scope") == "G1_STATIC_CANDIDATES_ONLY", "STATIC_COVERAGE_ONLY")
    for field in ("freeze_ready", "migration_ready", "production_verified", "ownership_universe_verified",
                  "durable_work_reviewed", "callback_receiver_verified", "unique_consumers_verified"):
        demand(coverage.get(field) is False, "STATIC_COVERAGE_ONLY")
    demand(coverage.get("instance_acknowledgements") == {} and coverage.get("watermarks") == {},
           "STATIC_COVERAGE_ONLY")
    writers = indexed(coverage["writers"], "id", "DUPLICATE_WRITER")
    demand(writers.keys() == WRITERS, "WRITER_SET_CHANGED")
    root = source_root.resolve(strict=True) if source_root is not None else None
    for writer in writers.values():
        # The registry tracks source wiring. Runtime readiness is checked separately above.
        demand(writer.get("status") in ("NOT_WIRED", "WIRED"), "WRITER_STATUS_INVALID")
        demand(bool(writer["candidate_paths"]) and bool(writer["required_evidence"]), "WRITER_EVIDENCE_MISSING")
        for value in writer["candidate_paths"]:
            path = Path(value)
            demand(not path.is_absolute() and ".." not in path.parts and path.suffix == ".java",
                   "WRITER_PATH_INVALID")
            if root is not None:
                resolved = (root / path).resolve(strict=True)
                demand(resolved.is_relative_to(root) and resolved.is_file(), "WRITER_PATH_INVALID")
    if root is not None:
        config = root / "yudao-server/src/main/java/cn/iocoder/yudao/server/split/LegacyPlatformWriteConfiguration.java"
        declaration = re.search(r"REQUIRED_WRITERS = Set[.]of\(([^;]+)\);", config.read_text(encoding="utf-8"))
        demand(declaration is not None, "GATE_WRITER_DECLARATION_MISSING")
        demand(set(re.findall(r'"([a-z-]+)"', declaration.group(1))) == WRITERS, "GATE_WRITER_SET_CHANGED")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("plan", "check"), default="plan")
    parser.add_argument("--manifest", type=Path, default=DIRECTORY / "manifest.json")
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--coverage", type=Path, default=DIRECTORY / "writer-coverage.json")
    args = parser.parse_args(argv)
    try:
        demand(args.action == "check" or (args.source_root is None and args.fixture is None),
               "PLAN_ACCEPTS_MANIFEST_ONLY")
        manifest = read_json(args.manifest)
        validate_coverage(read_json(args.coverage), args.source_root if args.action == "check" else None)
        validate_manifest(manifest, args.source_root if args.action == "check" else None)
        if args.fixture is not None:
            validate_fixture(read_json(args.fixture))
        result = {"status": "STATIC_INVENTORY_ONLY" if args.action == "plan" else "OFFLINE_CHECKS_PASSED",
                  "tables": len(manifest["tables"]), "migrations": len(manifest["migrations"]),
                  "sequences": len(manifest["sequences"]), "source_checked": args.source_root is not None,
                  "synthetic_fixture_checked": args.fixture is not None,
                  "writer_candidates": len(WRITERS), "freeze_ready": False,
                  "database_connected": False, "migration_ready": False, "production_verified": False}
        sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as failure:
        code = str(failure) if isinstance(failure, EvidenceError) else type(failure).__name__
        logging.error("P3 offline check failed: %s", code)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
