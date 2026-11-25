"""Select reviewed pair inputs and record the coverage of the fixed executor.

This file is loaded from the executor checkout, never from the selected business
backend. PR descriptions are data from GITHUB_EVENT_PATH, not shell commands.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


SHA = re.compile(r"[0-9a-f]{40}")
TEST_CLASS = "cn.iocoder.yudao.module.franchise.asset.ExternalHandoverPgIntegrationTest"
TEST_METHOD = "pairedMiniProgramRegistersExternalResultsAndReceivesOneEngineeringProject"
ASSET_MODULE = Path("平台程序/domains/franchise/franchise-asset")
TEST_SOURCE = ASSET_MODULE / "src/test/java" / Path(*TEST_CLASS.split(".")).with_suffix(".java")
TEST_REPORT = ASSET_MODULE / "target/surefire-reports" / f"TEST-{TEST_CLASS}.xml"
CLIENT = Path("scripts/verify-paired-external-handover.cjs")


def sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("Expected one full lowercase 40-character commit SHA")
    return value


def ref(value, branch):
    return value if value == branch else sha(value)


def boolean(value):
    if value in (False, "false", "", None):
        return False
    if value in (True, "true"):
        return True
    raise ValueError("Expected true or false for require_external_handover")


def marker(body, name):
    if body is None:
        return None
    if not isinstance(body, str):
        raise ValueError("PR body must be text")
    # Misspelled casing still declares an attempted marker and must not silently
    # downgrade a candidate to the legacy default pair.
    mentions = [line.strip() for line in body.splitlines() if name.lower() in line.lower()]
    if not mentions:
        return None
    if len(mentions) != 1:
        raise ValueError(f"PR body must contain at most one {name} marker")
    match = re.fullmatch(re.escape(name) + r":\s*([0-9a-f]{40})", mentions[0])
    if not match:
        raise ValueError(f"Malformed {name}: use a single line with a full lowercase SHA")
    return match.group(1)


def select(event, event_name, github_sha, frontend_input="", backend_input="",
           require_external=False, executor_input=""):
    reusable = bool(executor_input)
    if reusable:
        sha(executor_input)
    pr = event.get("pull_request") if event_name == "pull_request" else None
    selected_marker = marker(pr.get("body"), "Paired-Backend-SHA" if reusable else "Paired-Frontend-SHA") if pr else None
    own_sha = sha(pr["head"]["sha"] if pr else github_sha)
    required = boolean(require_external) or selected_marker is not None

    if reusable:
        # The caller already resolved its own frontend SHA. An ordinary frontend
        # PR always supplies it; that alone must not require the new capability.
        frontend_ref = ref(frontend_input, "main")
        if pr and frontend_ref != own_sha:
            raise ValueError("Frontend input must match the actual caller PR head")
        backend_ref = ref(backend_input or selected_marker or "backend-main", "backend-main")
        required = required or bool(backend_input and SHA.fullmatch(backend_input))
        source = "explicit_inputs" if backend_input else "pull_request_marker" if selected_marker else "default_branches"
    else:
        frontend_ref = ref(frontend_input or selected_marker or "main", "main")
        backend_ref = ref(backend_input or own_sha, "backend-main")
        if pr and backend_ref != own_sha:
            raise ValueError("Backend input must match the actual backend PR head")
        required = required or bool(frontend_input and SHA.fullmatch(frontend_input)) or bool(backend_input and SHA.fullmatch(backend_input))
        source = "explicit_inputs" if frontend_input or backend_input else "pull_request_marker" if selected_marker else "default_branches"
    return {"frontendRef": frontend_ref, "backendRef": backend_ref,
            "source": source, "markerPresent": selected_marker is not None,
            "requireExternalHandover": required}


def capabilities(backend, frontend, required):
    client = frontend / CLIENT
    source = backend / TEST_SOURCE
    has_client = client.is_file() and client.stat().st_size > 0
    has_test = source.is_file() and re.search(r"\bvoid\s+" + TEST_METHOD + r"\s*\(", source.read_text(encoding="utf-8")) is not None
    if required and not (has_client and has_test):
        raise ValueError("Explicit candidate pair requires both the external frontend driver and the real backend test")
    return {"status": "pending" if has_client and has_test else "not_run_legacy_pair",
            "frontendHasDriver": has_client, "backendHasTest": has_test,
            "testClass": TEST_CLASS, "testMethod": TEST_METHOD}


def require_passed_report(report):
    if not report.is_file():
        raise ValueError("External handover test report is missing")
    root = ET.parse(report).getroot()
    cases = [case for case in root.iter("testcase")
             if case.get("classname") == TEST_CLASS and case.get("name") == TEST_METHOD]
    if len(cases) != 1 or any(cases[0].find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise ValueError("External handover test must execute exactly once and pass without skipping")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def output(**values):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("select", "capabilities", "summarize"))
    args = parser.parse_args()
    scratch = Path(os.environ["RUNNER_TEMP"])
    selection_file = scratch / "release-pair-selection.json"
    capability_file = scratch / "external-handover-evidence.json"
    if args.command == "select":
        event = read_json(Path(os.environ["GITHUB_EVENT_PATH"]))
        selection = select(event, os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_SHA"],
                           os.environ.get("INPUT_FRONTEND_SHA", ""), os.environ.get("INPUT_BACKEND_SHA", ""),
                           os.environ.get("INPUT_REQUIRE_EXTERNAL", "false"), os.environ.get("INPUT_EXECUTOR_SHA", ""))
        executor = subprocess.check_output(["git", "-C", str(Path(__file__).resolve().parents[3]), "rev-parse", "HEAD"], text=True).strip()
        if executor != sha(os.environ["EXPECTED_EXECUTOR_SHA"]):
            raise ValueError("Executor checkout differs from the selected immutable workflow source")
        selection["executorCommit"] = executor
        write_json(selection_file, selection)
        output(frontend_ref=selection["frontendRef"], backend_ref=selection["backendRef"])
    elif args.command == "capabilities":
        selection = read_json(selection_file)
        coverage = capabilities(Path.cwd(), scratch / "mini", selection["requireExternalHandover"])
        write_json(capability_file, coverage)
        # A report from any earlier step must never stand in for this execution.
        TEST_REPORT.unlink(missing_ok=True)
        output(run_external="true" if coverage["status"] == "pending" else "false")
    else:
        selection, coverage = read_json(selection_file), read_json(capability_file)
        if coverage["status"] == "pending":
            require_passed_report(TEST_REPORT)
            coverage["status"] = "passed"
        elif coverage["status"] != "not_run_legacy_pair" or selection["requireExternalHandover"]:
            raise ValueError("Required external handover evidence cannot be omitted")
        delivery = scratch / "delivery"
        evidence = read_json(delivery / "acceptance.json")
        if evidence["frontendCommit"] != os.environ["FRONTEND_REV"] or evidence["backendCommit"] != os.environ["BACKEND_REV"]:
            raise ValueError("Acceptance evidence differs from the frozen source pair")
        evidence["pairSelection"], evidence["externalHandover"] = selection, coverage
        write_json(delivery / "acceptance.json", evidence)
        write_json(delivery / "release-pair-selection.json", selection)
        write_json(delivery / "external-handover-evidence.json", coverage)
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"\n执行器：`{selection['executorCommit']}`；配对来源：`{selection['source']}`。\n")
            summary.write("\n固定双方提交的原有握手、不可变包、迁移恢复和回滚门禁通过。\n")
            summary.write("\n新增外部委托→工程移交真实 PG/Node 配对测试已执行并通过。\n" if coverage["status"] == "passed" else
                          "\n`externalHandover: not_run_legacy_pair`：该历史组合缺少新版两侧能力，本次未验收新增八步外部整链。\n")


if __name__ == "__main__":
    main()
