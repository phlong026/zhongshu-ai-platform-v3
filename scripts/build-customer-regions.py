"""Build the server's offline five-level region catalog from a pinned source revision."""
import csv
import gzip
import hashlib
import io
import json
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

REPO = 'https://api.github.com/repos/modood/Administrative-divisions-of-China/commits/master'
target = Path(sys.argv[1])
if len(sys.argv)>2:
    revision=sys.argv[2]
elif (target/'source.json').exists():
    revision=json.loads((target/'source.json').read_text(encoding='utf-8'))['revision']
else:
    request = urllib.request.Request(REPO, headers={'User-Agent': 'zhongshu-region-catalog'})
    revision = json.load(urllib.request.urlopen(request, timeout=60))['sha']
target.mkdir(parents=True, exist_ok=True)
groups = defaultdict(lambda: defaultdict(list))
manifest = {'source': 'https://github.com/modood/Administrative-divisions-of-China',
            'revision': revision, 'dataDate': '2023-06-30', 'license': 'WTFPL', 'files': []}
for name, parent in [('provinces', None), ('cities', 'provinceCode'), ('areas', 'cityCode'),
                     ('streets', 'areaCode'), ('villages', 'streetCode')]:
    url = f'https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/{revision}/dist/{name}.csv'
    content = urllib.request.urlopen(url, timeout=120).read()
    rows = list(csv.DictReader(io.StringIO(content.decode('utf-8-sig'))))
    manifest['files'].append({'name': name, 'url': url, 'sha256': hashlib.sha256(content).hexdigest(), 'rows': len(rows)})
    if parent is None:
        (target / 'root.json').write_text(json.dumps([{'code': row['code'], 'name': row['name']} for row in rows], ensure_ascii=False), encoding='utf-8')
    else:
        for row in rows:
            groups[row['provinceCode']][row[parent]].append({'code': row['code'], 'name': row['name']})
    print(name, len(rows), flush=True)
for province, children in groups.items():
    payload = json.dumps(children, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    (target / f'{province}.json.gz').write_bytes(gzip.compress(payload, mtime=0))
(target / 'source.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print('Catalog ready:', len(groups), 'provinces;', sum(p.stat().st_size for p in target.iterdir()), 'bytes', flush=True)
