"""Resolve the two release sources once; subsequent steps use only their commit IDs."""
import json
import os
from pathlib import Path
import re
import subprocess


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def validate_ref(value, branch):
    if value != branch and not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError(f"Expected {branch} or a full lowercase commit SHA")


def freeze(repo, mini, frontend_ref, backend_ref):
    validate_ref(frontend_ref, "main")
    validate_ref(backend_ref, "backend-main")
    backend_sha = git(repo, "rev-parse", "HEAD^{commit}")
    if backend_ref != "backend-main" and backend_sha != backend_ref:
        raise ValueError("Backend checkout differs from the selected commit")
    git(repo, "fetch", "--no-tags", "origin", frontend_ref)
    frontend_sha = git(repo, "rev-parse", "FETCH_HEAD^{commit}")
    if frontend_ref != "main" and frontend_sha != frontend_ref:
        raise ValueError("Frontend fetch differs from the selected commit")
    git(repo, "worktree", "add", "--detach", str(mini), frontend_sha)
    if git(mini, "rev-parse", "HEAD") != frontend_sha:
        raise ValueError("Frontend worktree differs from the frozen commit")
    return {"frontendCommit": frontend_sha, "backendCommit": backend_sha}


def main():
    scratch = Path(os.environ["RUNNER_TEMP"])
    pair = freeze(Path.cwd(), scratch / "mini", os.environ["FRONTEND_REF"], os.environ["BACKEND_REF"])
    (scratch / "release-pair.json").write_text(json.dumps(pair, indent=2) + "\n")
    with open(os.environ["GITHUB_ENV"], "a") as output:
        output.write(f"FRONTEND_REV={pair['frontendCommit']}\nBACKEND_REV={pair['backendCommit']}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write("## 本次待验收组合\n\n")
        summary.write(f"- 小程序：`{pair['frontendCommit']}`\n- 后端和管理台：`{pair['backendCommit']}`\n\n")
        summary.write("只有后续握手、打包、迁移恢复和回滚步骤全部成功，才视为该组合通过。\n")
    print(json.dumps(pair))


if __name__ == "__main__":
    main()
