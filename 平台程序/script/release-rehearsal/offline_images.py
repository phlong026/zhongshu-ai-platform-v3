"""Save the pinned Linux/amd64 registry objects needed by the delivered Compose."""
import hashlib
import json
from pathlib import Path
import shutil
import time
import urllib.error
import urllib.request

MEDIA = ", ".join(("application/vnd.oci.image.index.v1+json","application/vnd.oci.image.manifest.v1+json",
                   "application/vnd.docker.distribution.manifest.list.v2+json","application/vnd.docker.distribution.manifest.v2+json"))


def write_offline_images(directory, images):
    directory.mkdir()
    catalog = {}
    for name, ref in images.items():
        digest = ref.split("@", 1)[1]
        local = name == "paired"
        base = ("http://127.0.0.1:5000" if local else "https://registry-1.docker.io") + "/v2/" + name + "/"
        headers = {"Accept": MEDIA}
        if not local:
            auth = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:"+name+":pull"
            with urllib.request.urlopen(auth,timeout=60) as response:
                headers["Authorization"]="Bearer "+json.load(response)["token"]
        entries = catalog[name] = {}

        def save(kind, oid):
            if oid in entries:
                return
            req=urllib.request.Request(base+kind+"/"+oid,headers={"Accept":MEDIA})
            if "Authorization" in headers:
                req.add_unredirected_header("Authorization", headers["Authorization"])
            path=directory/oid.split(":")[1]
            for attempt in range(4):
                try:
                    with urllib.request.urlopen(req,timeout=120) as response:
                        mime=response.headers.get_content_type()
                        if not path.exists():
                            partial=path.with_suffix(".part")
                            with partial.open("wb") as f: shutil.copyfileobj(response,f)
                            partial.replace(path)
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                    code=getattr(error,"code",None)
                    if attempt==3 or code in (401,403,404):
                        raise RuntimeError(f"Unable to fetch {name}/{kind}/{oid} (HTTP {code})") from error
                    time.sleep(2**attempt)
            with path.open("rb") as f:
                if "sha256:"+hashlib.file_digest(f,"sha256").hexdigest()!=oid:
                    raise ValueError("Registry object digest mismatch")
            entries[oid]=[kind,mime]
            if kind=="manifests":
                obj=json.loads(path.read_bytes())
                if "manifests" in obj:
                    arch=[m for m in obj["manifests"] if m.get("platform",{}).get("os")=="linux" and m["platform"].get("architecture")=="amd64"]
                    if len(arch)!=1:raise ValueError("Missing or ambiguous linux/amd64 manifest")
                    save("manifests",arch[0]["digest"])
                else:
                    for m in [obj["config"],*obj["layers"]]:save("blobs",m["digest"])
        save("manifests",digest)
    (directory/"catalog.json").write_text(json.dumps(catalog,sort_keys=True))
