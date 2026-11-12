#!/usr/bin/env python3
"""双应用真实 HTTP 场景。仅使用本机端点；不建容器、不写数据库、不请求支付。"""
import argparse
import http.client
import json
import logging
import os
from pathlib import Path
import secrets
import time
from urllib.parse import urlencode, urlsplit

LOG = logging.getLogger("platform-integration-http")
UNKNOWN = 1_112_100_002
PROJECT = {"sourceType": "SELF_UPLOAD", "line": "FLAT",
           "requirementInputs": {"faceWidthM": "12", "depthM": "10", "floor": "两层", "note": "非生产联验数据"}}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_private(path):
    require(not path.stat().st_mode & 0o077, "private file permissions must be 0600")
    return json.loads(path.read_text(encoding="utf-8"))


def save_private(path, payload):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(payload, stream, ensure_ascii=False)


class API:
    def __init__(self, origin, prefix, token=None, post=None):
        parsed = urlsplit(origin)
        require(parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and parsed.port
                and not any([parsed.path, parsed.query, parsed.fragment, parsed.username, parsed.password]),
                "only explicit http://127.0.0.1:<port> is accepted")
        self.port, self.prefix, self.token, self.post = parsed.port, prefix, token, post

    def call(self, method, path, body=None, key=None, codes=(0,)):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        headers = {"tenant-id": "1", "Content-Type": "application/json", "Connection": "close"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if self.post:
            headers["X-Post-Id"] = str(self.post)
        if key:
            headers["Idempotency-Key"] = key
        try:
            connection.request(method, self.prefix + path,
                               json.dumps(body).encode() if body is not None else None, headers)
            response = connection.getresponse()
            raw = response.read(2 * 1024 * 1024 + 1)
            require(len(raw) <= 2 * 1024 * 1024, "response too large")
            require(200 <= response.status < 300 or response.status in (400, 401, 403, 409, 422),
                    f"unexpected HTTP status {response.status}")
            payload = json.loads(raw)
            require(payload.get("code") in codes, f"unexpected business code {payload.get('code')}")
            return payload
        except (OSError, http.client.HTTPException, ValueError, TypeError) as error:
            LOG.error("HTTP step failed method=%s path=%s errorType=%s; bodies and credentials withheld",
                      method, path.split("?")[0], type(error).__name__)
            raise RuntimeError("HTTP scenario failed") from None
        finally:
            connection.close()

    def data(self, method, path, body=None, key=None):
        return self.call(method, path, body, key)["data"]


def wallet(api):
    data = api.data("GET", "/platform/wallet/account")
    return {key: int(data[key]) for key in ("walletId", "orgId", "availablePoints", "reservedPoints", "version")}


def money(data):
    return data["availablePoints"], data["reservedPoints"]


def onboard(config, directory):
    require(not (directory / "sessions.json").exists(), "sessions already exist; use a new fixture directory")
    api = API(config["bOrigin"], "/admin-api")
    login = api.data("POST", "/system/auth/login", {
        "username": (directory / "admin-username").read_text().strip(),
        "password": (directory / "admin-password").read_text().strip()})
    api.token = login["accessToken"]
    api.data("GET", "/system/auth/get-permission-info")
    actors = []
    batch = secrets.token_hex(6)
    for index in range(2):
        mobile = "139" + "".join(secrets.choice("0123456789") for _ in range(8))
        org = api.data("POST", "/platform/org/onboard", {
            "orgType": "store", "orgName": f"非生产联验-{batch}-{index}",
            "adminName": "联验负责人", "adminMobile": mobile})
        login = api.data("POST", "/platform/auth/wechat-login", {
            "appid": config["staffAppId"], "loginCode": secrets.token_urlsafe(24), "phoneCode": mobile})
        staff = API(config["bOrigin"], "/admin-api", login["accessToken"], org["postId"])
        posts = staff.data("GET", "/platform/posts/mine")
        require(any(str(row["postId"]) == str(org["postId"]) for row in posts), "new post missing")
        account = wallet(staff)
        require(str(account["orgId"]) == str(org["orgId"]), "wrong wallet organization")
        # The existing permission matrix gives design use to sales, while boss owns wallet queries.
        design_post = staff.data("POST", "/platform/org/posts", {
            "orgId": int(org["orgId"]), "userId": int(org["adminUserId"]), "roleCode": "sales"})
        actors.append({"orgId": int(org["orgId"]), "userId": int(org["adminUserId"]), "postId": int(org["postId"]),
                       "designPostId": int(design_post["postId"]),
                       "token": login["accessToken"], "walletId": account["walletId"]})
    save_private(directory / "sessions.json", actors)
    # No account names, mobiles, tokens, or session keys in the public handoff.
    save_private(directory / "fixture-scopes.json", [{key: row[key] for key in ("orgId", "userId", "postId", "walletId")}
                                                   for row in actors])
    LOG.warning("onboard HTTP steps passed; two staff ADMIN sessions and distinct organization wallets created")


def exercise(config, directory):
    for field in ("caseId", "budget", "expectedBudgetPoints", "expectedFlatPoints", "consumerCodeFile"):
        require(field in config, "missing explicit nonproduction fixture field")
    require(config["expectedBudgetPoints"] > 0 and config["expectedFlatPoints"] > 0, "paid fixtures must have positive prices")
    actors = load_private(directory / "sessions.json")
    owner_wallet, other_wallet = [API(config["bOrigin"], "/admin-api", row["token"], row["postId"]) for row in actors]
    owner, other = [API(config["bOrigin"], "/admin-api", row["token"], row["designPostId"]) for row in actors]
    consumer = API(config["aOrigin"], "/app-api/design/v1")
    login = consumer.data("POST", "/auth/wechat-login", {"code": Path(config["consumerCodeFile"]).read_text().strip()})
    require(login.get("restricted") is False, "consumer fixture must be admitted before testing")
    consumer.token = login["accessToken"]
    consumer_before = consumer.data("GET", "/point-account")
    consumer.data("POST", "/design-projects", PROJECT, "consumer-" + secrets.token_hex(12))
    consumer.data("GET", "/design-projects?limit=10")
    require(consumer.data("GET", "/point-account") == consumer_before, "consumer project creation changed wallet")
    foreign_before = wallet(other_wallet)
    opening = wallet(owner_wallet)
    require(opening["availablePoints"] >= config["expectedBudgetPoints"] * 2 + config["expectedFlatPoints"],
            "explicit fixture wallet funding required")

    base = "/franchise/design"
    owner.data("GET", base + "/cases?limit=10")
    case_id = config["caseId"]
    owner.data("GET", base + f"/cases/{case_id}")
    owner.data("PUT", base + f"/cases/{case_id}/favorite", key="favorite-" + secrets.token_hex(12))
    favorites = owner.data("GET", base + "/favorites?limit=20")
    require(any(str(row.get("caseId", row.get("id"))) == str(case_id) for row in favorites["list"]), "favorite missing")
    other_favorites = other.data("GET", base + "/favorites?limit=20")
    require(not any(str(row.get("caseId", row.get("id"))) == str(case_id) for row in other_favorites["list"]),
            "favorites crossed organization scope")

    project_key = "project-" + secrets.token_hex(12)
    project = owner.data("POST", base + "/projects", PROJECT, project_key)
    project_id = project["projectId"]
    replay = owner.data("POST", base + "/projects", PROJECT, project_key)
    require(replay["projectId"] == project_id and replay["replayed"] is True, "project replay mismatch")
    owner.call("POST", base + "/projects", {**PROJECT, "requirementInputs": {"note": "不同内容"}}, project_key, codes=(409,))
    other.call("GET", base + f"/projects/{project_id}", codes=(403,))

    budget = config["budget"]
    rule_query = urlencode({key: budget[key] for key in ("regionCode", "structureType", "materialGrade")})
    rule = owner.data("GET", base + "/budget/rule-info?" + rule_query)
    require(rule is not None, "fixture budget rule missing")
    budget_path = base + f"/projects/{project_id}/budget-estimates"
    budget_key = "budget-" + secrets.token_hex(12)
    estimate = owner.data("POST", budget_path, budget, budget_key)
    require(int(estimate["totalLowCents"]) == int(rule["lowCentsPerSqm"]) * budget["buildingArea"]
            and int(estimate["totalHighCents"]) == int(rule["highCentsPerSqm"]) * budget["buildingArea"],
            "budget totals do not match fixture rule")
    charged = wallet(owner_wallet)
    require(money(charged) == (opening["availablePoints"] - config["expectedBudgetPoints"], opening["reservedPoints"]),
            "budget wallet charge mismatch")
    replay = owner.data("POST", budget_path, budget, budget_key)
    require(replay["estimateId"] == estimate["estimateId"] and replay["replayed"] is True, "budget replay mismatch")
    require(wallet(owner_wallet) == charged, "budget replay changed wallet")
    other.call("POST", budget_path, budget, "scope-" + secrets.token_hex(12), codes=(403,))
    require(money(wallet(other_wallet)) == money(foreign_before), "rejected scope changed foreign wallet")

    lost_key = "lost-" + secrets.token_hex(12)
    save_private(directory / "drop-once.json", {"businessKey": lost_key, "action": "create-budget-estimate",
                                               "callerAppId": "local-integration-b", "orgId": actors[0]["orgId"]})
    owner.call("POST", budget_path, budget, lost_key, codes=(UNKNOWN,))
    require(not (directory / "drop-once.json").exists(), "proxy did not discard an A success receipt")
    unknown_wallet = wallet(owner_wallet)
    save_private(directory / "unknown-observed.json", {"code": UNKNOWN, "businessKey": lost_key,
                                                      "orgId": actors[0]["orgId"], "wallet": unknown_wallet})
    # Recovery uses the original stable key. A's durable receipt must be queried before any resend.
    recovered = None
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        payload = owner.call("POST", budget_path, budget, lost_key, codes=(0, UNKNOWN))
        if payload["code"] == 0:
            recovered = payload["data"]
            break
        time.sleep(1)
    require(recovered is not None and recovered["replayed"] is True, "UNKNOWN did not recover by receipt")
    after_recovery = wallet(owner_wallet)
    require(money(after_recovery) == (charged["availablePoints"] - config["expectedBudgetPoints"], charged["reservedPoints"]),
            "lost response charged incorrect points")
    owner.data("POST", budget_path, budget, lost_key)
    require(wallet(owner_wallet) == after_recovery, "recovered command charged twice")

    job_key = "flat-" + secrets.token_hex(12)
    job_path = base + f"/projects/{project_id}/flat-jobs"
    job_body = {"count": 1, "resolution": "2K", "orientation": "LANDSCAPE"}
    job = owner.data("POST", job_path, job_body, job_key)
    job_id = job["jobId"]
    replay = owner.data("POST", job_path, job_body, job_key)
    require(replay["jobId"] == job_id and replay["replayed"] is True, "flat job replay mismatch")
    other.call("GET", base + f"/ai-jobs/{job_id}", codes=(403,))
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        status = owner.data("GET", base + f"/ai-jobs/{job_id}")
        if status["status"] in ("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED"):
            break
        time.sleep(1)
    require(status["status"] == "SUCCEEDED" and status["acceptedCount"] == 1, "real runtime pipeline did not succeed")
    closing = wallet(owner_wallet)
    require(money(closing) == (after_recovery["availablePoints"] - config["expectedFlatPoints"], opening["reservedPoints"]),
            "flat job wallet charge mismatch")
    require(money(wallet(other_wallet)) == money(foreign_before), "other organization wallet changed")
    require(consumer.data("GET", "/point-account") == consumer_before, "B activity changed A consumer wallet")
    save_private(directory / "http-evidence.json", {
        "fixture": "NON_PRODUCTION", "httpScenariosPassed": True, "fullAcceptance": False,
        "pending": ["A/B database receipt, cost, ledger and isolation oracle", "A consumer paid design regression"],
        "provider": "deterministic-test-only", "projectId": project_id, "jobId": job_id,
        "budgetKey": budget_key, "lostKey": lost_key, "jobKey": job_key,
        "consumerBefore": consumer_before, "walletOpening": opening, "walletUnknown": unknown_wallet,
        "walletClosing": closing})
    LOG.warning("HTTP scenarios passed with deterministic provider; SQL cost/receipt oracle and consumer paid regression still required")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("onboard", "design"))
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    os.umask(0o077)
    try:
        config = load_private(args.config)
        require(config.get("fixture") == "NON_PRODUCTION", "fixture marker required")
        require((args.directory / "NON_PRODUCTION_FIXTURE").is_file(), "private test directory marker required")
        (onboard if args.command == "onboard" else exercise)(config, args.directory)
    except (OSError, KeyError, ValueError, TypeError, RuntimeError) as error:
        LOG.error("scenario stopped (%s); not a passing integration result", type(error).__name__)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
