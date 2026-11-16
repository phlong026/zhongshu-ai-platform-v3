"""Apply exactly five pricing class files, preserving every other release entry."""
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

PREFIX = 'cn/iocoder/yudao/module/platform/wallet/orgpoint/'
ALLOWED = {PREFIX + name + '.class' for name in (
    'BillingPriceCatalog', 'BillingPriceCatalog$Subject', 'BillingPriceCatalog$Unit',
    'OrgPointPricingController', 'OrgPointPricingController$History')}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def rewrite(source, updates):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as old, zipfile.ZipFile(result, 'w') as new:
        existing = set(old.namelist())
        assert len(existing) == len(old.namelist()), 'Duplicate entries in baseline'
        assert not any(n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA'))
                       and (n != 'META-INF/BOOT.SF' or old.read(n).strip()) for n in existing)
        for entry in old.infolist():
            new.writestr(entry, updates.get(entry.filename, old.read(entry.filename)))
        for name in sorted(set(updates) - existing):
            entry = zipfile.ZipInfo(name, date_time=(2026, 11, 16, 0, 0, 0))
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            new.writestr(entry, updates[name])
    with zipfile.ZipFile(io.BytesIO(source)) as old, zipfile.ZipFile(io.BytesIO(result.getvalue())) as new:
        assert new.testzip() is None
        assert set(new.namelist()) - set(old.namelist()) == set(updates) - set(old.namelist())
        for name in old.namelist():
            if name not in updates:
                assert old.read(name) == new.read(name), name
    return result.getvalue()


def patch(baseline, payload, output):
    with zipfile.ZipFile(payload) as package:
        assert set(package.namelist()) == ALLOWED
        classes = {name: package.read(name) for name in ALLOWED}
    source = Path(baseline).read_bytes()
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        modules = [n for n in archive.namelist() if n.startswith('BOOT-INF/lib/platform-wallet-') and n.endswith('.jar')]
        assert len(modules) == 1, modules
        module = modules[0]
        assert archive.getinfo(module).compress_type == zipfile.ZIP_STORED
        updates = {module: rewrite(archive.read(module), classes)}
        # Preserve the precedence of any class overlays made by the preceding release.
        for name, data in classes.items():
            if 'BOOT-INF/classes/' + name in archive.namelist():
                updates['BOOT-INF/classes/' + name] = data
    candidate = rewrite(source, updates)
    Path(output).write_bytes(candidate)
    return {'baselineSha256': digest(source), 'candidateSha256': digest(candidate),
            'outerEntriesChanged': sorted(updates), 'classEntriesChanged': sorted(classes),
            'allOtherEntriesIdentical': True}


if __name__ == '__main__':
    print(json.dumps(patch(*sys.argv[1:]), ensure_ascii=False))
