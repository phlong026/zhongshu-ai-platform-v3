"""Build and exercise a paired release on a disposable GitHub-hosted Linux runner."""
from pathlib import Path
import base64
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
import time
from offline_images import write_offline_images

if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
    raise SystemExit("Requires a disposable GitHub-hosted runner")
repo = Path.cwd()
scratch = Path(os.environ["RUNNER_TEMP"])
old = scratch / "previous" / "平台程序"
mini = scratch / "mini"
current = repo / "平台程序"
delivery = scratch / "delivery"
delivery.mkdir()
lab = scratch / "paired-runtime"
lab.mkdir()
deploy = lab / "release" / "deploy" / "platform"
keys = scratch / "rehearsal-keys"
keys.mkdir()
evidence = {}
target = "zhongshu-platform-b/zhongshu_platform_b"


def run(*args, capture=True, data=None):
    result = subprocess.run([str(v) for v in args], input=data, check=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.decode().strip() if capture else None


def record(name, value):
    evidence[name] = value
    print(name, value, flush=True)


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def compose(env, *args):
    return run("docker", "compose", "--env-file", env, "-f", deploy / "compose.yaml", *args)


def ops(env, action, *args):
    run(sys.executable, deploy / "ops.py", action, "--env-file", env, "--execute", "--writes-frozen",
        "--confirm-target", target, *args, capture=False)


def query(sql, bootstrap=False):
    if bootstrap:
        return run("docker","exec","-i","zhongshu-platform-b-postgres-b-1","psql","-XAt",
                   "-U","postgres","-d","postgres","-v","ON_ERROR_STOP=1",data=sql.encode())
    return run("docker","exec","-i","zhongshu-platform-b-postgres-b-1","sh","-ec",
               'export PGPASSWORD="$(cat /run/secrets/pg_owner_password)"; exec psql -XAt -h 127.0.0.1 -U platform_owner -d zhongshu_platform_b -v ON_ERROR_STOP=1',
               data=sql.encode())


def redis(*args):
    return run("docker","exec","zhongshu-platform-b-redis-b-1","sh","-ec",
               'export REDISCLI_AUTH="$(cat /run/secrets/redis_app_password)"; exec redis-cli --user platform_app "$@"',
               "redis-cli", *args)


def image(source, tag):
    jar = source / "yudao-platform-server/target/yudao-platform-server.jar"
    with tempfile.TemporaryDirectory() as folder:
        context = Path(folder)
        shutil.copytree(source / "deploy/platform",context / "deploy/platform")
        dst = context / "yudao-platform-server/target/yudao-platform-server.jar"
        dst.parent.mkdir(parents=True)
        shutil.copyfile(jar, dst)
        run("docker","build","-t",tag,"-f",context / "deploy/platform/Dockerfile",context,capture=False)
    run("docker","push",tag)
    return run("docker","image","inspect","--format","{{index .RepoDigests 0}}",tag)


def confirm_running(ref):
    expected = run("docker","image","inspect","--format","{{.Id}}",ref)
    assert run("docker","inspect","--format","{{.Image}} {{.State.Health.Status}}","zhongshu-platform-b-api-1") == expected+" healthy"
    with urllib.request.urlopen("http://127.0.0.1:48081/actuator/health/readiness",timeout=10) as response:
        assert json.load(response) == {"status":"UP"}


def expect_migration_failure(env, prev):
    try:
        ops(env,"migrate","--previous-api-image",prev)
    except subprocess.CalledProcessError:
        assert query("SELECT count(*) FROM flyway_schema_history WHERE version='20261118.990' AND success;") == "1"
        assert query("SELECT count(*) FROM flyway_schema_history WHERE version='20261118.991' AND success;") == "0"
        assert query("SELECT balance FROM org_point_account WHERE org_id=118;") == "205"
    else:
        raise AssertionError("Financial reconciliation gate accepted the fixture")


def fixture_env(path, digest, variables):
    path.write_text("PLATFORM_API_IMAGE="+digest+"\nPF_DESIGN_PUBLIC_CASES_ORIGIN=https://cases.example.invalid\n"
                    +"".join(f"{name}={file}\n" for name,file in variables.items()))
    path.chmod(0o600)


assert not run("docker","ps","-aq","--filter","label=com.docker.compose.project=zhongshu-platform-b")
assert not any(x.startswith("zhongshu-platform-b_") for x in run("docker","volume","ls","-q").splitlines())
run("docker","run","-d","--name","rehearsal-registry","-p","127.0.0.1:5000:5000","registry:2")
oldref = image(old, "127.0.0.1:5000/paired:old")
tag = "127.0.0.1:5000/paired:candidate"
ref = image(current,tag)
package = scratch / "package"
package.mkdir()
run("docker","save","-o",package/"api-image.tar",tag)
shutil.copyfile(current/"yudao-platform-server/target/yudao-platform-server.jar",package/"platform.jar")
shutil.copytree(current/"admin-ui/dist",package/"web/admin")
shutil.copytree(current/"deploy/platform",package/"deploy/platform")
run("git","-C",mini,"archive","-o",scratch/"mini.tar","HEAD","--","miniprogram","project.config.json")
with tarfile.open(scratch/"mini.tar") as archive:
    archive.extractall(package/"wechat-project",filter="data")
fixtures = package / "acceptance" / "wechat"
(fixtures / "scripts").mkdir(parents=True)
for name in ("verify-finance-journey.cjs", "role-page-runtime.cjs", "verify-performance-pages.cjs"):
    shutil.copyfile(mini / "scripts" / name, fixtures / "scripts" / name)
subprocess.run(["npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=mini, check=True)
shutil.copytree(mini / "node_modules" / "sax", fixtures / "node_modules" / "sax")
shutil.copyfile(Path(__file__).with_name("WalletProbe.java"), package / "acceptance" / "WalletProbe.java")
compose_model = json.loads(run("docker","compose","-f",current/"deploy/platform/compose.yaml",
                               "config","--no-interpolate","--format","json"))
offline_refs = { "library/postgres":compose_model["services"]["postgres-b"]["image"],
                 "library/redis":compose_model["services"]["redis-b"]["image"],"paired":ref }
write_offline_images(package/"offline-images",offline_refs)
manifest = {"images":offline_refs,"frontendCommit":run("git","-C",mini,"rev-parse","HEAD"),"backendCommit":run("git","rev-parse","HEAD"),
            "priorSourceCommit":run("git","-C",old,"rev-parse","HEAD"),
            "imageId":run("docker","image","inspect","--format","{{.Id}}",ref),
            "files":{p.relative_to(package).as_posix():sha(p) for p in sorted(package.rglob("*")) if p.is_file()}}
(package/"manifest.json").write_text(json.dumps(manifest,indent=1,ensure_ascii=False))
bundle = delivery / "zhongshu-paired-release.tar.gz"
with tarfile.open(bundle,"w:gz",compresslevel=1) as archive:
    for p in sorted(package.rglob("*")):
        if p.is_file(): archive.add(p,arcname=p.relative_to(package).as_posix())
unpacked = scratch / "accepted"
with tarfile.open(bundle) as archive:
    archive.extractall(unpacked,filter="data")
assert all(sha(unpacked/name)==digest for name,digest in manifest["files"].items())
record("packageSha256",sha(bundle))
(delivery/"SHA256SUMS").write_text(sha(bundle)+"  "+bundle.name+"\n")
run("docker","load","-i",unpacked/"api-image.tar")
assert run("docker","run","--rm","--network","none","--entrypoint","sha256sum",ref,"/opt/platform/app.jar").split()[0]==sha(unpacked/"platform.jar")
fixtures = unpacked / "acceptance" / "wechat"
run("docker","run","--rm","--network","none",
    "--volume",str(unpacked/"wechat-project/miniprogram")+":/work/miniprogram:ro",
    "--volume",str(fixtures/"scripts")+":/work/scripts:ro",
    "--volume",str(fixtures/"node_modules")+":/work/node_modules:ro",
    "--workdir","/work","node:24-alpine","node","--test","scripts/verify-finance-journey.cjs",
    "scripts/verify-performance-pages.cjs",capture=False)
record("paymentRecoveryPage","passed")
record("commissionRoleStates","passed")

shutil.copytree(old/"deploy/platform",deploy)
variables = {}
for name in ("PLATFORM_PG_BOOTSTRAP_PASSWORD_FILE","PLATFORM_PG_OWNER_PASSWORD_FILE","PLATFORM_PG_APP_PASSWORD_FILE",
             "PLATFORM_REDIS_APP_PASSWORD_FILE","PLATFORM_REDIS_BACKUP_PASSWORD_FILE","PLATFORM_CONFIG_FILE"):
    f = keys/name
    f.write_text(secrets.token_hex(32))
    variables[name]=f
settings={}
for name in ("platform-identity-content.properties.example","platform-cos.properties.example"):
    for line in (deploy/name).read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k,v=line.split("=",1)
            settings[k]=secrets.token_hex(16) if "REPLACE" in v else v
settings.update({"zhongshu.org-point.headquarters-tenant-id":"1",
                 "platform.identity.wechat.provider":"real","platform.identity.wechat.apps[0].appid":"wx0000000000000118",
                 "platform.identity.sms.provider":"real","platform.identity.sms.sdk-app-id":"1400000000","platform.identity.sms.template-id":"123456",
                 "zhongshu.content.provider":"real","zhongshu.content.task-sweep-enabled":"true","zhongshu.franchise.asset.storage.provider":"cos",
                 "zhongshu.franchise.asset.storage.cos.endpoint":"https://assets.example.invalid","zhongshu.franchise.asset.storage.cos.bucket":"rehearsal-1234567890",
                 "wx.miniapp.appid":"wx0000000000000118","wx.miniapp.secret":secrets.token_hex(16),"mybatis-plus.encryptor.password":secrets.token_hex(8),
                 "zhongshu.design.remote.origin":"https://design.example.invalid","zhongshu.design.public-cases.origin":"https://cases.example.invalid",
                 "zhongshu.design.remote.caller-app-id":"fixture","zhongshu.design.remote.active-key-id":"fixture",
                 "zhongshu.design.remote.keys.fixture":base64.b64encode(secrets.token_bytes(32)).decode(),
                 "zhongshu.design.remote.reference-upload-hosts":"assets.example.invalid"})
variables["PLATFORM_CONFIG_FILE"].write_text("".join(f"{k}={v}\n" for k,v in settings.items()))
for f in variables.values():
    run("sudo","chown",f"{os.getuid()}:20000",f)
    f.chmod(0o640)
oldenv=lab/"old.env"
newenv=lab/"new.env"
fixture_env(oldenv,oldref,variables);fixture_env(newenv,ref,variables)
model=json.loads(compose(oldenv,"config","--format","json"))
for service in ("postgres-b","redis-b"):
    run("docker","pull",model["services"][service]["image"])
compose(oldenv,"up","-d","--wait","postgres-b","redis-b")
ops(oldenv,"migrate")
compose(oldenv,"up","-d","--no-deps","--wait","--wait-timeout","180","api")
confirm_running(oldref)
original=run("docker","inspect","--format","{{.Id}}","zhongshu-platform-b-api-1")
assert "asset-spool" not in run("docker","inspect","--format","{{json .Mounts}}","zhongshu-platform-b-api-1")
query("""INSERT INTO org_point_account(id,org_id,balance) VALUES(118,118,205);
INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta,operation_id,price_snapshot)
VALUES (810,118,'package-sim:1:118:8:9:legacy','SIMULATED_PACKAGE',120,NULL,'{"priceFen":990}'),
       (811,118,'fixture-reserve','RESERVE',-15,851,NULL),(812,118,'fixture-formal','RECHARGE',100,NULL,NULL);""")
assert redis("SET","r4:marker","118")=="OK"
compose(oldenv,"stop","api")
shutil.copytree(unpacked/"deploy/platform",deploy,dirs_exist_ok=True)
backup = scratch/"freeze-backup"
ops(newenv,"backup","--backup-dir",backup,"--previous-api-image",oldref)
backup_hash=sha(backup/"manifest.json");record("frozenBackup",backup_hash)
expect_migration_failure(newenv,oldref)
ops(newenv,"rollback","--image",oldref,"--previous-api-image",oldref,"--schema-compatible")
confirm_running(oldref)
assert original==run("docker","inspect","--format","{{.Id}}","zhongshu-platform-b-api-1")
assert redis("GET","r4:marker")=="118"
record("failedMigrationRestartedOriginal",True)
compose(oldenv,"stop","api")
# Preserve the failed database. Restore the trusted dump to a fresh target on this runner.
query("ALTER DATABASE zhongshu_platform_b RENAME TO paired_failed;", True)
query("REVOKE CONNECT ON DATABASE paired_failed FROM platform_app, PUBLIC;",True)
query("CREATE DATABASE zhongshu_platform_b OWNER platform_owner;", True)
query("REVOKE ALL ON DATABASE zhongshu_platform_b FROM PUBLIC; GRANT CONNECT ON DATABASE zhongshu_platform_b TO platform_app;",True)
query("REVOKE ALL ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO platform_owner; GRANT USAGE ON SCHEMA public TO platform_app; "
      "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_app; "
      "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO platform_app;")
ops(newenv,"restore","--backup-dir",backup,"--confirm-sha256",backup_hash,"--previous-api-image",oldref)
assert query("SELECT count(*) FROM flyway_schema_history WHERE version='20261118.990';")=="0"
ops(newenv,"rollback","--image",oldref,"--previous-api-image",oldref,"--schema-compatible")
confirm_running(oldref)
assert query("SELECT balance FROM org_point_account WHERE org_id=118;")=="205" and redis("GET","r4:marker")=="118"
record("restoredOldDataAndAPI",True)
compose(oldenv,"stop","api")
expect_migration_failure(newenv,oldref)
query("INSERT INTO org_point_simulation_review VALUES(810,105,3,205,'fixture finance','rehearsal authorization',now());")
expect_migration_failure(newenv,oldref)
query("INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta,operation_id) VALUES(813,118,'fixture-settle','SETTLE',0,851);")
expect_migration_failure(newenv,oldref)
query("UPDATE org_point_simulation_review SET reviewed_ledger_count=4 WHERE source_ledger_id=810;")
ops(newenv,"migrate","--previous-api-image",oldref)
ops(newenv,"start","--previous-api-image",oldref)
confirm_running(ref)
assert query("SELECT balance FROM org_point_account WHERE org_id=118;")=="100"
assert query("SELECT test_points FROM org_point_simulated_purchase WHERE org_id=118;")=="105"
record("reviewedLegacy",{"issued":120,"reservedThenSpent":15,"formalRecharge":100,"formalRemaining":100,"testRemaining":105})
probe=unpacked/"acceptance/WalletProbe.java"
libs=lab/"libs";libs.mkdir()
with zipfile.ZipFile(unpacked/"platform.jar") as app:
    for name in app.namelist():
        if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):(libs/Path(name).name).write_bytes(app.read(name))
app_user_file=variables["PLATFORM_PG_APP_PASSWORD_FILE"]
run("javac","-encoding","UTF-8","-cp",str(libs/"*"),"-d",libs,probe)
run("docker","run","--rm","--network","zhongshu-platform-b_private","--user",str(os.getuid())+":"+str(os.getgid()),
    "--volume",str(libs)+":/probe:ro","--volume",str(app_user_file)+":/fixture-pass:ro","--entrypoint","java",ref,
    "-cp","/probe/*:/probe","cn.iocoder.yudao.module.platform.wallet.orgpoint.WalletProbe",capture=False)
record("formalTaskCannotUseTestPoints",True)
compose(newenv,"stop","api")
ops(newenv,"rollback","--image",oldref,"--schema-compatible")
confirm_running(oldref)
assert redis("GET","r4:marker")=="118" and query("SELECT balance FROM org_point_account WHERE org_id=118;")=="100"
record("postUpgradeRollback",True)
# New daemon with no source registry, external network or inherited Docker store.
# Only fixture credentials and unpacked delivery files are mounted into it.
offline=lab/"offline"
shutil.copytree(unpacked/"deploy/platform",offline)
daemon="paired-empty-engine"
run("docker","pull","docker:28-dind")
run("docker","pull","python:3.12-alpine")
run("docker","run","-d","--name",daemon,"--privileged","--network","none",
    "-e","DOCKER_TLS_CERTDIR=","--volume",str(offline)+":"+str(offline)+":ro",
    "--volume",str(keys)+":"+str(keys)+":ro","--volume",str(newenv)+":"+str(newenv)+":ro",
    "docker:28-dind","dockerd","--host=unix:///var/run/docker.sock","--registry-mirror=http://127.0.0.1:5000")
def fresh(*args):
    return run("docker","exec",daemon,"docker",*args)
for attempt in range(60):
    try:
        fresh("info","--format","{{.ServerVersion}}")
        break
    except subprocess.CalledProcessError:
        time.sleep(1)
assert not fresh("image","ls","-aq")
assert not fresh("ps","-aq")
run("docker","run","-d","--name","paired-offline-mirror","--network","container:"+daemon,
    "--volume",str(unpacked)+":/delivery:ro","python:3.12-alpine","python3",
    "/delivery/deploy/platform/offline-registry.py","/delivery/offline-images")
for attempt in range(20):
    if subprocess.run(["docker","exec",daemon,"wget","-q","-O","/dev/null","http://127.0.0.1:5000/v2/"]).returncode == 0:
        break
    time.sleep(1)
else:
    raise RuntimeError("Offline registry failed to start")
for pinned in offline_refs.values():
    fresh("pull","--platform","linux/amd64",pinned)
    fresh("image","inspect",pinned)
base=("compose","--env-file",str(newenv),"-f",str(offline/"compose.yaml"))
fresh(*base,"up","-d","--pull","never","--wait","postgres-b","redis-b")
fresh(*base,"-f",str(offline/"compose.migrate.yaml"),"run","--rm","--no-deps","--pull","never","api")
run("docker","exec","-i",daemon,"docker","exec","-i","zhongshu-platform-b-postgres-b-1",
    "sh","-ec",'export PGPASSWORD="$(cat /run/secrets/pg_owner_password)"; exec psql -X -h 127.0.0.1 -U platform_owner -d zhongshu_platform_b -v ON_ERROR_STOP=1',
    data=(offline/"postgres-policy.sql").read_bytes())
fresh(*base,"up","-d","--pull","never","--no-deps","--wait","--wait-timeout","180","api")
assert fresh("inspect","--format","{{.Config.Image}} {{.State.Health.Status}}","zhongshu-platform-b-api-1")==ref+" healthy"
assert fresh("exec","zhongshu-platform-b-api-1","sha256sum","/opt/platform/app.jar").split()[0]==sha(unpacked/"platform.jar")
record("offlineEmptyEngine",{"initialImages":0,"externalNetwork":"none","digests":offline_refs,"api":"healthy"})
(delivery/"acceptance.json").write_text(json.dumps(evidence,indent=2)+"\n")
