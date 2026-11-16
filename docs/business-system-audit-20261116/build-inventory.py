"""Read-only source inventory. Candidate states are never marked as live passes."""
import csv
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent
OUT.mkdir(parents=True, exist_ok=True)
MINI = ROOT / "miniprogram"


def read(path):
    return path.read_text(encoding="utf-8-sig") if path.exists() else ""


def templates(path, visited=None):
    visited = set() if visited is None else visited
    path = path.resolve()
    if path in visited or not path.is_file() or not path.is_relative_to(MINI):
        return {}
    visited.add(path)
    source = read(path)
    result = {path: source}
    for target in re.findall(r'<(?:import|include)\s+[^>]*src=["\x27]([^"\x27]+)', source):
        child = MINI / target.lstrip("/") if target.startswith("/") else path.parent / target
        result.update(templates(child, visited))
    return result


def revision(root):
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()


def main():
    config = json.loads(read(MINI / "app.json"))
    routes = [(page, "main") for page in config["pages"]]
    for package in config.get("subPackages", []):
        routes.extend((package["root"] + "/" + page, package.get("name", package["root"])) for page in package["pages"])
    pages, fingerprints = [], {}
    for route, package in routes:
        page_js = MINI / (route + ".js")
        page_wxml = MINI / (route + ".wxml")
        sources = templates(page_wxml)
        source = "\n".join(sources.values())
        views = sorted(set(re.findall(r'\bview\s*={2,3}\s*["\x27]([\w-]+)', source + "\n" + read(page_js))))
        handlers = sorted(set(re.findall(r'(?:bind|catch)(?::?[\w-]+)\s*=\s*["\x27]([^"\x27]+)', source)))
        for path in [page_js, *sources]:
            if path.exists():
                fingerprints[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        pages.append({"route": route, "package": package, "templateExists": page_wxml.exists(),
                      "templateFiles": [p.relative_to(ROOT).as_posix() for p in sources],
                      "literalViewCandidates": views, "handlers": handlers, "runtimeStatus": "NOT_RUN"})
    digest = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()
    result = {"phase": "source snapshot; not runtime verification", "frontendCommit": revision(ROOT),
              "candidateSourceDigest": digest, "routeCount": len(pages), "routes": pages,
              "limits": "Literal view candidates include legacy/unreachable views. Imports/includes are followed; dynamic components and server states require live enumeration."}
    (OUT / "preliminary-page-inventory.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    with (OUT / "逐页面执行清单.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["route", "view_candidate", "status", "normal_entry", "screenshot", "api_evidence", "notes"])
        for page in pages:
            for view in ["", *page["literalViewCandidates"]]:
                writer.writerow([page["route"], view, "NOT_RUN", "", "", "", "候选视图；实际可见性及流程须现场确认"])
    print(json.dumps({"registeredRoutes": len(pages), "templateFiles": len({p for page in pages for p in page["templateFiles"]}),
                      "literalViewCandidates": sum(len(page["literalViewCandidates"]) for page in pages),
                      "runtimePasses": 0, "candidateSourceDigest": digest}, ensure_ascii=False))


if __name__ == "__main__":
    main()
