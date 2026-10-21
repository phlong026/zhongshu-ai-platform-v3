from pathlib import Path
import hashlib, json, zipfile, datetime

project = Path.cwd()
out = project / 'qa' / 'role-complete-20261018'
sha = lambda data: hashlib.sha256(data).hexdigest()
baseline = {}
with zipfile.ZipFile(out / 'before-changes.zip') as archive:
    for entry in archive.infolist():
        if not entry.is_dir():
            baseline[entry.filename.replace('\\', '/')] = archive.read(entry)
files = sorted((project / 'miniprogram').rglob('*'))
files = [p for p in files if p.is_file()] + [project / 'package.json', project / 'README.md']
scripts = [p for p in (project / 'scripts').iterdir() if p.is_file() and p.suffix in ['.cjs', '.js', '.py']]
delivery, changes = [], []
for file in sorted(set(files + scripts)):
    name = file.relative_to(project).as_posix()
    data = file.read_bytes()
    row = {'path': name, 'bytes': len(data), 'sha256': sha(data)}
    delivery.append(row)
    if name in baseline and baseline[name] != data:
        changes.append({**row, 'type': 'changed', 'beforeBytes': len(baseline[name]), 'beforeSha256': sha(baseline[name])})
    elif name not in baseline and file in files:
        changes.append({**row, 'type': 'added'})
for name, data in baseline.items():
    if not (project / name).exists():
        changes.append({'path': name, 'type': 'removed', 'beforeSha256': sha(data)})
at = datetime.datetime.now(datetime.timezone.utc).isoformat()
(out / 'delivery-manifest.json').write_text(json.dumps({'at': at, 'root': str(project), 'files': delivery}, ensure_ascii=False, indent=2), encoding='utf-8')
(out / 'change-manifest.json').write_text(json.dumps({'at': at, 'baseline': 'before-changes.zip', 'note': '主包脚本包括去除无语义缩进和空行的体积调整；功能修复按报告分组说明。验证脚本单列在交付哈希清单中。', 'files': changes}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'deliveryFiles': len(delivery), 'changedSourceFiles': len(changes)}, ensure_ascii=False))
