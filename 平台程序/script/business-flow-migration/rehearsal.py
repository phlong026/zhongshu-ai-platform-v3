#!/usr/bin/env python3
"""R21 explicit company maintenance via existing authorized APIs; plan is read-only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


class MaintenanceError(RuntimeError):
    pass


def positive_id(value):
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]{0,18}", str(value)):
        raise MaintenanceError("Company/tenant IDs must be explicit positive integers")
    if int(value) > 2**63 - 1:
        raise MaintenanceError("ID exceeds signed 64-bit range")
    return str(value)


def manifest(raw):
    if set(raw) != {"tenantId", "reason", "companies"}:
        raise MaintenanceError("Manifest requires tenantId, reason and companies")
    tenant = positive_id(raw["tenantId"])
    reason = raw["reason"]
    if not isinstance(reason, str) or not 1 <= len(reason.strip()) <= 400:
        raise MaintenanceError("Provide a reviewable reason of 1–400 characters")
    rows = raw["companies"]
    if not isinstance(rows, list) or not 1 <= len(rows) <= 100:
        raise MaintenanceError("Supply 1–100 exact company IDs; names/patterns cannot select targets")
    normalized = []
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"companyId", "confirmedName", "action"}:
            raise MaintenanceError("Each target requires companyId, confirmedName and action")
        name = row["confirmedName"]
        if not isinstance(name, str) or not 1 <= len(name) <= 128:
            raise MaintenanceError("An exact organization name is required")
        if row["action"] not in {"close", "provision"}:
            raise MaintenanceError("Only reversible close or zero-balance provision is supported")
        normalized.append({"companyId": positive_id(row["companyId"]), "confirmedName": name, "action": row["action"]})
    if len({row["companyId"] for row in normalized}) != len(normalized):
        raise MaintenanceError("A company may appear only once")
    return {"tenantId": tenant, "reason": reason.strip(), "companies": normalized}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def save(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf8", opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise MaintenanceError("Authenticated maintenance requests do not follow redirects")


class Client:
    def __init__(self, origin, tenant, token):
        url = urllib.parse.urlsplit(origin)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment:
            raise MaintenanceError("base-url must be an HTTPS origin without credentials or a path")
        if not token:
            raise MaintenanceError("Set ZSH_MAINTENANCE_TOKEN for an authorized platform administrator")
        self.origin, self.tenant, self.token = origin.rstrip("/"), positive_id(tenant), token
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, method, path, body=None):
        req = urllib.request.Request(self.origin + "/admin-api" + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), method=method,
            headers={"Authorization": "Bearer " + self.token, "tenant-id": self.tenant, "Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=30) as response:
                data = json.load(response)
        except (urllib.error.URLError, TimeoutError) as error:
            # No token, request body or raw response is logged. SUBMITTING journal remains retryable.
            raise MaintenanceError("HTTP outcome unavailable; resume with the same journal and request key") from error
        if not isinstance(data, dict) or data.get("code") != 0:
            raise MaintenanceError("Server rejected operation (code " + str(data.get("code") if isinstance(data, dict) else "invalid") + ")")
        return data.get("data")


def snapshot(client, company):
    base = "/platform/org/directory/" + company
    org = client.call("GET", base)["organization"]
    if positive_id(org["orgId"]) != company:
        raise MaintenanceError("Server returned a different organization")
    audits = client.call("GET", base + "/audits?pageNo=1&pageSize=1")
    latest = (audits.get("list") or [None])[0]
    return {"organization": {key: org.get(key) for key in ("orgId", "orgName", "orgType", "status")},
            "points": client.call("GET", base + "/points"),
            "ledgerCount": client.call("GET", base + "/ledger?pageNo=1&pageSize=1")["total"],
            "latestAudit": None if latest is None else {key: latest.get(key) for key in ("id", "action", "reason", "request_key")}}


def build_plan(client, raw):
    source = manifest(raw)
    plan = {"version": 1, "batchId": str(uuid.uuid4()), "manifest": source, "manifestHash": digest(source), "targets": []}
    for row in source["companies"]:
        before = snapshot(client, row["companyId"])
        org = before["organization"]
        if org["orgName"] != row["confirmedName"] or org["status"] != "ACTIVE":
            raise MaintenanceError("Target name/status differs from the explicitly approved active organization")
        if row["action"] == "close" and org["orgType"] != "store":
            raise MaintenanceError("Test cleanup only closes explicitly selected stores, never headquarters")
        if row["action"] == "close" and int(before["points"].get("in_flight") or 0) != 0:
            raise MaintenanceError("Unsettled generation reservations block closing this company")
        key = "r21:" + plan["batchId"] + ":" + row["companyId"]
        body = {"reason": source["reason"], "requestKey": key}
        endpoint = "/platform/org-points/" + row["companyId"] + "/provision"
        if row["action"] == "close":
            body.update(action="close", confirmedName=row["confirmedName"], expectedAuditId=(before["latestAudit"] or {}).get("id", "NONE"))
            endpoint = "/platform/org/directory/" + row["companyId"] + "/lifecycle"
        plan["targets"].append({"target": row, "before": before, "endpoint": endpoint, "body": body, "phase": "PLANNED"})
    return plan


def validate_plan(plan):
    if plan.get("version") != 1 or digest(manifest(plan["manifest"])) != plan.get("manifestHash"):
        raise MaintenanceError("Invalid or changed manifest")
    try:
        batch = str(uuid.UUID(plan["batchId"]))
    except (ValueError, KeyError) as error:
        raise MaintenanceError("Invalid batch ID") from error
    if [row.get("target") for row in plan.get("targets", [])] != plan["manifest"]["companies"]:
        raise MaintenanceError("Journal targets differ from the approved manifest")
    for item in plan["targets"]:
        row = item["target"]
        expected = {"reason": plan["manifest"]["reason"], "requestKey": "r21:" + batch + ":" + row["companyId"]}
        path = "/platform/org-points/" + row["companyId"] + "/provision"
        if row["action"] == "close":
            expected.update(action="close", confirmedName=row["confirmedName"], expectedAuditId=(item["before"]["latestAudit"] or {}).get("id", "NONE"))
            path = "/platform/org/directory/" + row["companyId"] + "/lifecycle"
        if item["body"] != expected or item["endpoint"] != path or item["phase"] not in {"PLANNED", "SUBMITTING", "APPLIED", "RESTORING", "RESTORED"}:
            raise MaintenanceError("Journal request was changed")


def apply(client, plan, checkpoint):
    validate_plan(plan)
    for item in plan["targets"]:
        if item["phase"] in {"APPLIED", "RESTORING", "RESTORED"}:
            continue
        if item["phase"] == "PLANNED":
            if snapshot(client, item["target"]["companyId"]) != item["before"]:
                raise MaintenanceError("Target changed after dry run; generate and review a fresh plan")
            item["phase"] = "SUBMITTING"
            checkpoint(plan)  # Persist the exact request BEFORE sending it, including uncertain failures.
        client.call("POST", item["endpoint"], item["body"])
        item["after"] = snapshot(client, item["target"]["companyId"])
        expected_status = "DISABLED" if item["target"]["action"] == "close" else "ACTIVE"
        if item["after"]["organization"]["status"] != expected_status:
            raise MaintenanceError("Operation response needs manual verification; journal retained")
        item["phase"] = "APPLIED"
        checkpoint(plan)


def restore(client, plan, checkpoint):
    validate_plan(plan)
    for item in reversed(plan["targets"]):
        if item["target"]["action"] != "close" or item["phase"] in {"PLANNED", "RESTORED"}:
            continue
        if item["phase"] == "SUBMITTING":
            raise MaintenanceError("Resolve the uncertain close with apply before restoring")
        if item["phase"] == "APPLIED":
            fresh = snapshot(client, item["target"]["companyId"])
            audit = fresh["latestAudit"] or {}
            if fresh["organization"]["status"] != "DISABLED" or audit.get("request_key") != item["body"]["requestKey"]:
                raise MaintenanceError("Organization lifecycle changed since this batch; do not overwrite later actions")
            item["phase"] = "RESTORING"
            checkpoint(plan)
        body = {**item["body"], "action": "reopen", "requestKey": item["body"]["requestKey"] + ":restore",
                "expectedAuditId": item["after"]["latestAudit"]["id"]}
        client.call("POST", item["endpoint"], body)
        item["restored"] = snapshot(client, item["target"]["companyId"])
        if item["restored"]["organization"]["status"] != "ACTIVE":
            raise MaintenanceError("Restore response needs manual verification; journal retained")
        item["phase"] = "RESTORED"
        checkpoint(plan)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["plan", "apply", "restore"])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--journal", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "plan":
        if not args.manifest or args.journal.exists():
            raise MaintenanceError("plan requires a manifest and a new journal path")
        raw = manifest(json.loads(args.manifest.read_text()))
        client = Client(args.base_url, raw["tenantId"], os.environ.get("ZSH_MAINTENANCE_TOKEN"))
        plan = build_plan(client, raw)
        plan["origin"] = client.origin
        save(args.journal, plan)
    else:
        plan = json.loads(args.journal.read_text())
        validate_plan(plan)
        client = Client(args.base_url, plan["manifest"]["tenantId"], os.environ.get("ZSH_MAINTENANCE_TOKEN"))
        if client.origin != plan.get("origin"):
            raise MaintenanceError("Journal belongs to a different environment")
        (apply if args.command == "apply" else restore)(client, plan, lambda value: save(args.journal, value))
    print(json.dumps({"batchId": plan["batchId"], "targets": [{"companyId": i["target"]["companyId"], "phase": i["phase"]} for i in plan["targets"]]}))


if __name__ == "__main__":
    try:
        main()
    except (MaintenanceError, KeyError, ValueError, OSError) as error:
        print("Maintenance stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
