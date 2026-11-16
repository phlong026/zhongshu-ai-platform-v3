"""Add the operations module and replace only ContentTaskStore in the running B artifact."""
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

MODULE = 'BOOT-INF/lib/yudao-platform-operations-2026.08-SNAPSHOT.jar'
STORE = 'cn/iocoder/yudao/module/franchise/content/content/ContentTaskStore'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def rewrite(source, updates):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as old, zipfile.ZipFile(output, 'w') as new:
        names = set(old.namelist())
        assert len(names) == len(old.namelist()), 'Duplicate archive entries'
        assert not any(name.startswith('META-INF/') and name.endswith(('.SF','.RSA','.DSA'))
                       and (name != 'META-INF/BOOT.SF' or old.read(name).strip()) for name in names)
        for entry in old.infolist():
            new.writestr(entry, updates.get(entry.filename, old.read(entry.filename)))
        for name in sorted(set(updates) - names):
            entry = zipfile.ZipInfo(name, date_time=(2026,11,16,0,0,0))
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_STORED if name.startswith('BOOT-INF/lib/') else zipfile.ZIP_DEFLATED
            new.writestr(entry, updates[name])
    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(source)) as old, zipfile.ZipFile(io.BytesIO(result)) as new:
        assert new.testzip() is None
        assert set(new.namelist()) == set(old.namelist()) | set(updates)
        for name in old.namelist():
            if name not in updates:
                assert old.read(name) == new.read(name), name
    return result

def patch(baseline, module, store_classes, destination):
    with zipfile.ZipFile(store_classes) as archive:
        assert archive.namelist()
        assert all(name.endswith('.class') and (name == STORE+'.class' or name.startswith(STORE+'$')) for name in archive.namelist())
        classes = {name:archive.read(name) for name in archive.namelist()}
    source = Path(baseline).read_bytes()
    module_bytes = Path(module).read_bytes()
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        matches = []
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                    if STORE+'.class' in nested.namelist():
                        matches.append(name)
        assert len(matches) == 1, matches
        content_module = matches[0]
        assert archive.getinfo(content_module).compress_type == zipfile.ZIP_STORED
        updates = {content_module:rewrite(archive.read(content_module),classes),MODULE:module_bytes}
        for name,data in classes.items():
            if 'BOOT-INF/classes/'+name in archive.namelist():
                updates['BOOT-INF/classes/'+name] = data
        index = archive.read('BOOT-INF/classpath.idx')
        if MODULE.encode() not in index:
            updates['BOOT-INF/classpath.idx'] = index.rstrip(b'\r\n')+b'\n- "'+MODULE.encode()+b'"\n'
    candidate = rewrite(source,updates)
    Path(destination).write_bytes(candidate)
    return {'baselineSha256':sha(source),'candidateSha256':sha(candidate),
            'moduleSha256':sha(module_bytes),'outerEntriesChanged':sorted(updates),
            'storeClassesChanged':sorted(classes),'allOtherEntriesIdentical':True,
            'newDatabaseMigrations':['20261116.730']}

if __name__ == '__main__':
    print(json.dumps(patch(*sys.argv[1:])))
