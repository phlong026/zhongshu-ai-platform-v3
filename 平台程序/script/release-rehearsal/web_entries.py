from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit


class EntryAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = set()
        self.modules = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("src"):
            url = attrs["src"]
            if attrs.get("type") == "module":
                self.modules.add(url)
        elif tag == "link" and attrs.get("rel") in {"stylesheet", "modulepreload", "icon"}:
            url = attrs["href"]
        else:
            return
        self.urls.add(url)


def verify_web_entries(web_root):
    counts = {}
    for name in ("index.html", "clone-upload.html"):
        assets = EntryAssets()
        assets.feed((web_root / "admin" / name).read_text(encoding="utf-8"))
        local = set()
        for url in assets.urls:
            target = urlsplit(urljoin("https://platform.zszhj.com/admin/" + name, url))
            if target.netloc != "platform.zszhj.com":
                continue
            if not target.path.startswith("/admin/") or not (web_root / target.path.lstrip("/")).is_file():
                raise ValueError(f"{name} references an unavailable production asset: {url}")
            local.add(url)
        if not assets.modules or not assets.modules <= local:
            raise ValueError(f"{name} must load its module from the delivered admin directory")
        counts[name] = len(local)
    return counts
