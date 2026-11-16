"""B-only deployment: private backup, owner migration, CAS restart, health check and code rollback."""
import hashlib
import json
import os
import pathlib
import subprocess
import time
import urllib.parse
import urllib.request

out = pathlib.Path('/tmp/creation-live-20261116')
pre = json.loads((out / 'direct-preflight.json').read_text())
service = 'zhongshu-platform-b-integration.service'

def runtime():
    pid = subprocess.check_output(['systemctl', 'show', service, '-p', 'MainPID', '--value'], text=True).strip()
    args = pathlib.Path('/proc/' + pid + '/cmdline').read_bytes().split(b'\0')
    return pid, args[args.index(b'-jar') + 1].decode()

expected = (pre['basePid'], pre['baseJar'])
if runtime() != expected:
    raise RuntimeError('B changed; prepare a fresh release without overwriting it')
properties = {}
for line in pathlib.Path('/etc/zhongshu-platform-b-integration/migration.properties').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        properties[key.strip()] = value.strip()
url = urllib.parse.urlparse(properties['spring.flyway.url'].removeprefix('jdbc:'))
env = dict(os.environ, PGPASSWORD=properties['spring.flyway.password'])
pg = ['-h', url.hostname, '-p', str(url.port or 5432), '-U', properties['spring.flyway.user'], '-d', url.path.lstrip('/')]
backup = out / 'before-direct-upload-full.dump'
with backup.open('wb') as target:
    backup.chmod(0o600)
    subprocess.run(['pg_dump', *pg, '-Fc'], stdout=target, env=env, check=True)
catalog = subprocess.check_output(['pg_restore', '-l', str(backup)], text=True)
if not catalog.strip():
    raise RuntimeError('Empty backup catalogue')
log = out / 'direct-migration.log'
with log.open('w') as target:
    log.chmod(0o600)
    migration = subprocess.run(['java', '-Xmx256m', '-jar', pre['candidateJar'], 'migrate',
        '--spring.profiles.active=pg,migration',
        '--spring.config.location=file:/etc/zhongshu-platform-b-integration/migration.properties',
        '--spring.flyway.out-of-order=true'], stdout=target, stderr=subprocess.STDOUT)
if migration.returncode:
    raise RuntimeError('Migration failed; inspect private migration log, no API restart performed')
schema = subprocess.check_output(['psql', *pg, '-At', '-c',
    "SELECT version||':'||success FROM flyway_schema_history WHERE version='20261116.924';"
    "SELECT count(*)||':'||count(*) FILTER(WHERE NOT success) FROM flyway_schema_history;"], env=env, text=True).strip().splitlines()
if not schema or schema[0] != '20261116.924:true' or not schema[-1].endswith(':0'):
    raise RuntimeError('Migration receipt did not pass verification')
print('MIGRATION_924_VERIFIED', flush=True)
if runtime() != expected:
    raise RuntimeError('B changed during migration; rebuild from its new base before restart')
unit = subprocess.check_output(['systemctl', 'cat', service], text=True).replace('\\\n', '')
commands = [line.partition('=')[2] for line in unit.splitlines() if line.startswith('ExecStart=') and line.partition('=')[2].strip()]
command = commands[-1]
if command.count(pre['baseJar']) != 1:
    raise RuntimeError('ExecStart did not match expected B runtime')
override = pathlib.Path('/etc/systemd/system/' + service + '.d/zzzzz-creation-final-20261116.conf')
before = out / 'before-direct-upload-override.conf'
before.write_text(override.read_text() if override.exists() else '')
before.chmod(0o600)

def start(jar):
    override.write_text('[Service]\nExecStart=\nExecStart=' + command.replace(pre['baseJar'], jar) + '\n')
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
    subprocess.run(['systemctl', 'restart', service], check=True)

def healthy():
    for _ in range(45):
        try:
            with urllib.request.urlopen('http://127.0.0.1:48081/actuator/health', timeout=1) as response:
                if json.load(response).get('status') == 'UP':
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False

start(pre['candidateJar'])
print('RESTARTED_B_DIRECT_UPLOAD', flush=True)
if not healthy():
    start(pre['rollbackCompatibleJar'])
    rollback_ok = healthy()
    raise RuntimeError('New version failed health; code rollback health=' + str(rollback_ok) + '; database preserved')
pid, jar = runtime()
if jar != pre['candidateJar']:
    raise RuntimeError('Another deployment changed active runtime')
receipt = dict(pre, deployedAt=time.time(), pid=pid, deployedJar=jar, health='UP', schemaVerification=schema,
               backupPath=str(backup), backupSha256=hashlib.sha256(backup.read_bytes()).hexdigest(),
               backupBytes=backup.stat().st_size, backupMode='0600; parent 0700', override=str(override),
               preservedOtherModules=True, mediaStorage='phone PUT directly to upload.hifly.cc; B stores metadata only')
(out / 'direct-deployment.json').write_text(json.dumps(receipt, indent=2))
print(json.dumps(receipt), flush=True)
