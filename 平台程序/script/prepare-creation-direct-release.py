"""Run on B host as root after uploading the tested content module; never changes the running service."""
import copy
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import sys
import zipfile

out = pathlib.Path('/tmp/creation-live-20261116')
out.mkdir(mode=0o700, exist_ok=True)
out.chmod(0o700)
service = 'zhongshu-platform-b-integration.service'
pid = subprocess.check_output(['systemctl', 'show', service, '-p', 'MainPID', '--value'], text=True).strip()
args = pathlib.Path('/proc/' + pid + '/cmdline').read_bytes().split(b'\0')
base = pathlib.Path(args[args.index(b'-jar') + 1].decode())
nested = 'BOOT-INF/lib/yudao-domain-franchise-content-2026.08-SNAPSHOT.jar'
with zipfile.ZipFile(base) as boot:
    old_bytes = boot.read(nested)

with zipfile.ZipFile(io.BytesIO(old_bytes)) as old, zipfile.ZipFile(sys.argv[1]) as new:
    changed = [n for n in new.namelist() if n in old.namelist() and new.read(n) != old.read(n)]
    added = sorted(set(new.namelist()) - set(old.namelist()))
    removed = sorted(set(old.namelist()) - set(new.namelist()))
    allowed = (
        'cn/iocoder/yudao/module/franchise/content/content/ContentClone',
        'cn/iocoder/yudao/module/franchise/content/provider/HiflyCloneClient',
        'cn/iocoder/yudao/module/franchise/content/controller/admin/ContentCloneController',
        'db/migration/franchise/V20261116.924__',
        'db/migration-undo/franchise/V20261116.924__',
    )
    if removed or any(not n.startswith(allowed) for n in changed + added if not n.endswith('/')):
        raise RuntimeError('Unexpected content diff: ' + repr(changed + added + removed))

    def content_bytes(rollback=False):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as target:
            for entry in old.infolist():
                data = new.read(entry.filename) if not rollback and entry.filename in changed else old.read(entry.filename)
                target.writestr(copy.copy(entry), data)
            for name in added:
                if not rollback or name.startswith('db/'):
                    target.writestr(copy.copy(new.getinfo(name)), new.read(name))
        return stream.getvalue()

    module = content_bytes()
    rollback_module = content_bytes(rollback=True)

def boot_file(destination, data):
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(destination, 'w') as target:
        for entry in source.infolist():
            target.writestr(copy.copy(entry), data if entry.filename == nested else source.read(entry.filename))
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(destination) as target:
        assert source.namelist() == target.namelist()
        assert [n for n in source.namelist() if source.read(n) != target.read(n)] == [nested]

candidate = out / 'direct-upload-candidate.jar'
boot_file(candidate, module)
sha = hashlib.sha256(candidate.read_bytes()).hexdigest()
release = base.parent / ('platform-creation-direct-20261116-' + sha[:12] + '.jar')
shutil.copyfile(candidate, release)
release.chmod(0o644)
rollback = base.parent / 'platform-creation-direct-rollback-compatible-20261116.jar'
boot_file(rollback, rollback_module)
rollback.chmod(0o644)
receipt = dict(basePid=pid, baseJar=str(base), baseSha256=hashlib.sha256(base.read_bytes()).hexdigest(),
               candidateJar=str(release), candidateSha256=sha, changedContentEntries=changed,
               addedContentEntries=added, removedEntries=removed, rollbackCompatibleJar=str(rollback),
               newMigration='20261116.924', changedBootEntries=[nested])
(out / 'direct-preflight.json').write_text(json.dumps(receipt, indent=2))
print(json.dumps(receipt))
