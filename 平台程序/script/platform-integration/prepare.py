#!/usr/bin/env python3
"""本地双应用联验准备；不构建、不启动应用、不连接 Docker 或数据库。"""
import argparse
import base64
import hashlib
import json
import logging
import os
from pathlib import Path
import secrets
import struct
import subprocess
import zipfile
import zlib

LOG = logging.getLogger("platform-integration")
OPERATIONS = (
    "CREATE_PROJECT LIST_PROJECTS GET_PROJECT GET_AI_JOB CANCEL_AI_JOB NOTE_POLISH "
    "LIST_RESULT_VERSIONS CREATE_FLAT_JOB CREATE_ELEVATION_JOB CONFIRM_FLAT_CANDIDATE "
    "SELECT_ELEVATION_CANDIDATE CREATE_REVISION_REQUEST VALIDATE_SCHEME_REF LIST_CASES "
    "LIST_CASE_STYLES GET_CASE_DETAIL FAVORITE_CASE UNFAVORITE_CASE LIST_FAVORITES "
    "BUDGET_RULE_INFO CREATE_BUDGET_ESTIMATE CREATE_UPLOAD_TICKET COMPLETE_UPLOAD "
    "CREATE_DOWNLOAD_URL QUERY_RECEIPT GET_SETTLEMENT"
).split()


def private_write(path, text):
    with open(path, "x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        stream.write(text)


def checked(command):
    try:
        subprocess.run(command, check=True, capture_output=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as error:
        # Tool output can contain secrets, so expose only the executable and error class.
        raise RuntimeError(f"{command[0]} failed ({type(error).__name__})") from None


def prepare(directory, proxy_port):
    if not 1024 <= proxy_port <= 65535:
        raise ValueError("proxy port must be between 1024 and 65535")
    directory = directory.resolve()
    if directory.exists():
        raise ValueError("output directory must be new; existing artifacts are never overwritten")
    directory.mkdir(mode=0o700, parents=True)
    os.umask(0o077)
    private_write(directory / "NON_PRODUCTION_FIXTURE", "synthetic local integration only\n")
    private_write(directory / "truststore-password", secrets.token_urlsafe(24))
    private_write(directory / "admin-username", "it" + secrets.token_hex(6))
    private_write(directory / "admin-password", "Az9!" + secrets.token_hex(6))
    private_write(directory / "runtime-secret", secrets.token_hex(32))
    secret = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    private_write(directory / "capability-secret", secret)
    ext = directory / "server.ext"
    private_write(ext, "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                  "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,DNS:host.docker.internal,IP:127.0.0.1\n")
    ca_config = directory / "ca.conf"
    private_write(ca_config, "[req]\ndistinguished_name=dn\nx509_extensions=v3_ca\n[dn]\n"
                  "[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n")
    checked(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-sha256", "-config", str(ca_config), "-subj", "/CN=Local integration CA - NON PRODUCTION",
             "-keyout", str(directory / "ca.key"), "-out", str(directory / "ca.pem")])
    checked(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256",
             "-subj", "/CN=localhost", "-keyout", str(directory / "server.key"),
             "-out", str(directory / "server.csr")])
    checked(["openssl", "x509", "-req", "-in", str(directory / "server.csr"),
             "-CA", str(directory / "ca.pem"), "-CAkey", str(directory / "ca.key"),
             "-CAcreateserial", "-days", "2", "-sha256", "-extfile", str(ext),
             "-out", str(directory / "server.pem")])
    checked(["openssl", "verify", "-CAfile", str(directory / "ca.pem"), str(directory / "server.pem")])
    checked(["keytool", "-importcert", "-noprompt", "-alias", "local-integration-ca",
             "-file", str(directory / "ca.pem"), "-keystore", str(directory / "truststore.p12"),
             "-storetype", "PKCS12", "-storepass:file", str(directory / "truststore-password")])
    password = (directory / "truststore-password").read_text(encoding="utf-8")
    private_write(directory / "java-tls.args",
                  json.dumps("-Djavax.net.ssl.trustStore=" + str(directory / "truststore.p12"), ensure_ascii=False)
                  + "\n-Djavax.net.ssl.trustStoreType=PKCS12\n-Djavax.net.ssl.trustStorePassword=" + password + "\n")
    private_write(directory / "b-capability.properties",
                  "# NON PRODUCTION; append to isolated B runtime properties\n"
                  "zhongshu.design.remote.enabled=true\n"
                  f"zhongshu.design.remote.origin=https://127.0.0.1:{proxy_port}\n"
                  "zhongshu.design.remote.caller-app-id=local-integration-b\n"
                  "zhongshu.design.remote.active-key-id=local-it\n"
                  f"zhongshu.design.remote.keys.local-it={secret}\n"
                  "zhongshu.design.remote.request-timeout=10s\n"
                  "zhongshu.design.remote.recovery-delay-ms=60000\n")
    private_write(directory / "a-capability.properties",
                  "# NON PRODUCTION; confirm against frozen A before use\n"
                  "zhongshu.design.capability.enabled=true\n"
                  "zhongshu.design.capability.keys.local-it.caller-app-id=local-integration-b\n"
                  f"zhongshu.design.capability.keys.local-it.secret-base64={secret}\n"
                  "zhongshu.design.capability.keys.local-it.operations=" + ",".join(OPERATIONS) + "\n")
    LOG.warning("prepared local-only certificate chain and private fixture credentials; no services started")


def a_fixture(directory, pg_port, redis_port, api_port, runtime_port):
    """Generate private configuration and SQL only; never connect to a database."""
    directory = directory.resolve()
    if not (directory / "NON_PRODUCTION_FIXTURE").is_file():
        raise ValueError("fixture marker required")
    ports = [pg_port, redis_port, api_port, runtime_port]
    if len(set(ports)) != 4 or any(not 1024 <= port <= 65535 for port in ports):
        raise ValueError("four distinct unprivileged local ports required")
    os.umask(0o077)
    consumer_id = 9100000 + secrets.randbelow(1000000)
    code = "e2e-" + secrets.token_hex(20)
    appid = "local-integration-consumer"
    digest = hashlib.sha256((appid + "|" + code).encode()).hexdigest()
    pg_password, redis_password = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    private_write(directory / "consumer-code", code)
    private_write(directory / "a-pg-password", pg_password)
    private_write(directory / "a.pgpass", f"127.0.0.1:{pg_port}:zhongshu_design_it:design_it:{pg_password}\n")
    private_write(directory / "a-redis-password", redis_password)
    private_write(directory / "consumer-fixture.json", json.dumps({"accountId": consumer_id, "appid": appid, "caseId": consumer_id}))
    storage = directory / "a-assets"
    storage.mkdir(mode=0o700)
    def png_chunk(kind, payload):
        return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload))
    image = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!IIBBBBB', 16, 16, 8, 2, 0, 0, 0))
             + png_chunk(b'IDAT', zlib.compress((b'\x00' + b'\xe5\xe7\xeb' * 16) * 16)) + png_chunk(b'IEND', b''))
    (storage / "fixture.png").write_bytes(image)
    image_hash = hashlib.sha256(image).hexdigest()
    private_write(directory / "a-overrides.properties", f"""# NON PRODUCTION overlay for the source-matched external a-base.yaml.
spring.profiles.active=pg,local
spring.datasource.dynamic.primary=master
spring.datasource.dynamic.strict=true
spring.datasource.dynamic.datasource.master.name=zhongshu_design_it
spring.datasource.druid.stat-view-servlet.enabled=false
spring.datasource.druid.web-stat-filter.enabled=false
spring.boot.admin.client.enabled=false
spring.boot.admin.context-path=/disabled-admin
spring.sql.init.mode=never
flowable.database-schema-update=false
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
knife4j.enable=false
yudao.codegen.db-schemas=public
yudao.web.admin-ui.url=http://127.0.0.1
yudao.xss.exclude-urls[0]=/actuator/**
yudao.security.permit-all-urls[0]=/actuator/health/readiness
yudao.security.permit-all-urls[1]=/actuator/health/liveness
yudao.tenant.ignore-urls[0]=/actuator/health/readiness
yudao.tenant.ignore-urls[1]=/actuator/health/liveness
yudao.captcha.enable=false
wx.mp.app-id=local-integration-mp
wx.mp.secret={secrets.token_hex(24)}
wx.mp.config-storage.type=RedisTemplate
wx.mp.config-storage.key-prefix=local-integration-a:mp
wx.mp.config-storage.http-client-type=HttpComponents
wx.miniapp.appid={appid}
wx.miniapp.secret={secrets.token_hex(24)}
wx.miniapp.config-storage.type=RedisTemplate
wx.miniapp.config-storage.key-prefix=local-integration-a:miniapp
wx.miniapp.config-storage.http-client-type=HttpComponents
management.endpoints.web.exposure.include=health,info
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=db,redis,readinessState
management.endpoint.health.group.liveness.include=livenessState
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
logging.level.root=WARN
logging.level.cn.iocoder.yudao=WARN
server.address=127.0.0.1
server.port={api_port}
spring.main.lazy-initialization=false
spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:{pg_port}/zhongshu_design_it
spring.datasource.dynamic.datasource.master.username=design_it
spring.datasource.dynamic.datasource.master.password={pg_password}
spring.datasource.dynamic.datasource.master.driver-class-name=org.postgresql.Driver
spring.datasource.dynamic.druid.validation-query=SELECT 1
spring.datasource.dynamic.druid.validation-query-timeout=5
spring.datasource.dynamic.druid.max-wait=10000
spring.datasource.dynamic.druid.pool-prepared-statements=false
spring.data.redis.host=127.0.0.1
spring.data.redis.port={redis_port}
spring.data.redis.username=default
spring.data.redis.password={redis_password}
spring.data.redis.database=0
spring.data.redis.connect-timeout=5s
spring.data.redis.timeout=5s
spring.quartz.auto-startup=false
spring.quartz.job-store-type=jdbc
spring.quartz.jdbc.initialize-schema=never
spring.flyway.enabled=true
spring.flyway.url=jdbc:postgresql://127.0.0.1:{pg_port}/zhongshu_design_it
spring.flyway.user=design_it
spring.flyway.password={pg_password}
spring.flyway.locations=classpath:db/migration/platform,classpath:db/migration/identity,classpath:db/migration/design,classpath:db/migration/commerce,classpath:db/migration/ai-orchestration,classpath:db/migration/design-capability
spring.flyway.clean-disabled=true
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=20260905.000
spring.flyway.out-of-order=true
zhongshu.identity.wechat.provider=stub
zhongshu.identity.wechat-appid={appid}
zhongshu.identity.wechat.stub-fixed-openid=
zhongshu.identity.access-code-pepper={secrets.token_hex(32)}
zhongshu.identity.access-code-artifact-key={base64.b64encode(secrets.token_bytes(32)).decode()}
zhongshu.commerce.payment.provider=stub
zhongshu.commerce.payment.stub-auto-settle=false
zhongshu.commerce.recharge.enabled=false
zhongshu.design.seed-dev-data=false
zhongshu.design.driver-enabled=true
zhongshu.design.asset.storage.provider=local
zhongshu.design.asset.storage-root={storage}
zhongshu.design.asset.moderation.provider=stub
zhongshu.ai.internal-secret={(directory / 'runtime-secret').read_text().strip()}
zhongshu.ai.note-polish.runtime-url=http://127.0.0.1:{runtime_port}
yudao.security.mock-enable=false
management.endpoint.env.show-values=NEVER
management.endpoint.configprops.show-values=NEVER
""")
    private_write(directory / "runtime-fixture.json", json.dumps({
        "fixture": "NON_PRODUCTION", "core": f"http://127.0.0.1:{api_port}", "listenPort": runtime_port,
        "secretFile": str(directory / "runtime-secret"), "storageRoot": str(storage),
        "journalPath": str(directory / "runtime-journal"), "seconds": 600}))
    private_write(directory / "a-fixture.sql", f"""\\set ON_ERROR_STOP on
-- NON_PRODUCTION. Apply once to the freshly migrated, isolated A database; no payment occurs.
BEGIN;
SET LOCAL statement_timeout='10s';
SET LOCAL lock_timeout='5s';
DO $$ BEGIN
 IF current_database()<>'zhongshu_design_it' OR current_user<>'design_it' THEN
  RAISE EXCEPTION 'Wrong isolated A database or role';
 END IF;
 IF EXISTS (SELECT 1 FROM account) OR EXISTS (SELECT 1 FROM platform_cost_price)
    OR EXISTS (SELECT 1 FROM generation_price_rule) OR EXISTS (SELECT 1 FROM service_usage_price_rule)
    OR EXISTS (SELECT 1 FROM budget_rule_version) OR EXISTS (SELECT 1 FROM design_case)
    OR EXISTS (SELECT 1 FROM asset) THEN
  RAISE EXCEPTION 'Fixture requires empty business tables; refusing overwrite';
 END IF;
END $$;
INSERT INTO account(id,nickname,creator) VALUES({consumer_id},'NON_PRODUCTION consumer','local-fixture');
INSERT INTO wechat_identity(id,appid,openid,unionid,account_id,creator)
 VALUES({consumer_id},'{appid}','stub-openid-{digest[:24]}','stub-unionid-{digest[24:40]}',{consumer_id},'local-fixture');
INSERT INTO design_access_grant(id,account_id,creator) VALUES({consumer_id},{consumer_id},'local-fixture');
INSERT INTO design_point_account(id,user_id,available_points,version,creator)
 VALUES({consumer_id},{consumer_id},1000,1,'local-fixture');
INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after,biz_type,biz_id,idempotency_key,reason,creator)
 VALUES({consumer_id},{consumer_id},'MANUAL_CREDIT',1000,1000,0,'NON_PRODUCTION','fixture',
 'local-fixture-credit-{consumer_id}','NON_PRODUCTION synthetic opening balance','local-fixture');
INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros)
 SELECT product,1,'MICRO_CNY',103,107,101 FROM unnest(ARRAY[
 'FLAT:2K','FLAT:4K','ELEVATION:2K','ELEVATION:4K','BUDGET_ESTIMATE','NOTE_POLISH']) AS p(product);
INSERT INTO generation_price_rule(id,stage,resolution,unit_point_cost,min_count,max_count,effective_at,creator)
 VALUES({consumer_id},'FLAT','2K',7,1,4,now()-interval '1 minute','local-fixture');
INSERT INTO service_usage_price_rule(id,product,point_cost,effective_at)
 VALUES({consumer_id},'AI_PROMPT',3,now()-interval '1 minute'),
 ({consumer_id + 1},'BUDGET_ESTIMATE',13,now()-interval '1 minute');
INSERT INTO budget_rule_version(id,region_code,structure_type,material_grade,low_cents_per_sqm,high_cents_per_sqm,effective_at,creator)
 VALUES({consumer_id},'LOCAL_IT','FRAME','STANDARD',100000,150000,now()-interval '1 minute','local-fixture');
-- Synthetic published catalog data; this does not test the administration publish workflow.
INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes,
 stored_sha256,stored_size,width,height,upload_status,security_scan_status,moderation_status,creator)
 VALUES({consumer_id},'fixture.png',{consumer_id},'IMAGE','USER_UPLOAD','{image_hash}','image/png',{len(image)},
 '{image_hash}',{len(image)},16,16,'ACCEPTED','PASSED','PASSED','local-fixture');
INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at,creator)
 VALUES({consumer_id},{consumer_id},{consumer_id},'NON_PRODUCTION fixture','PUBLIC_DISPLAY',now()-interval '1 minute','local-fixture');
INSERT INTO design_case(id,source_type,creator_user_id,current_version_id,publication_status,uploader_name,uploader_company,creator)
 VALUES({consumer_id},'COMPANY',{consumer_id},{consumer_id},'PUBLISHED','测试案例作者','NON_PRODUCTION fixture','local-fixture');
INSERT INTO design_case_version(id,case_id,version,title,description,style_code,floor_count,building_area,face_width,depth,creator)
 VALUES({consumer_id},{consumer_id},1,'NON_PRODUCTION 联验案例','仅供接口联验的合成案例','MODERN',1,120,12,10,'local-fixture');
INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no,creator)
 VALUES({consumer_id},{consumer_id},{consumer_id},'COVER',NULL,'local-fixture'),
 ({consumer_id + 1},{consumer_id},{consumer_id},'FLOOR_PLAN',1,'local-fixture');
INSERT INTO case_publication(id,case_id,action,published_version_id,operator_id,reason,creator)
 VALUES({consumer_id},{consumer_id},'PUBLISH',{consumer_id},'local-fixture','NON_PRODUCTION catalog fixture','local-fixture');
COMMIT;
""")
    LOG.warning("prepared isolated A overrides, consumer/cost/budget SQL and Runtime configuration; SQL not executed")


def a_scopes(directory):
    if not (directory / "NON_PRODUCTION_FIXTURE").is_file():
        raise ValueError("fixture marker required")
    scopes = json.loads((directory / "fixture-scopes.json").read_text())
    if (len(scopes) != 2 or not all(type(row["orgId"]) is int and row["orgId"] > 0 for row in scopes)
            or scopes[0]["orgId"] == scopes[1]["orgId"]):
        raise ValueError("two onboarded organization IDs required")
    values = ",\n".join(f"('local-integration-b',{row['orgId']},1,true)" for row in scopes)
    private_write(directory / "a-scopes.sql", "\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL statement_timeout='10s';\n"
                  "DO $$ BEGIN IF current_database()<>'zhongshu_design_it' OR current_user<>'design_it' "
                  "THEN RAISE EXCEPTION 'Wrong isolated A database or role'; END IF; END $$;\n"
                  "INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES\n"
                  + values + ";\nCOMMIT;\n")
    LOG.warning("prepared A service namespace mapping SQL; no account/session/wallet rows created; SQL not executed")


def a_config(directory, source_root):
    """Copy only the true A base configuration and build a guarded local bootstrap script."""
    directory = directory.resolve()
    if not (directory / "a-overrides.properties").is_file():
        raise ValueError("a-fixture must run first")
    source_root = source_root.resolve(strict=True)
    source_yaml = source_root / "yudao-server/src/main/resources/application.yaml"
    source_sql = source_root / "sql/postgresql/ruoyi-vue-pro.sql"
    private_write(directory / "a-base.yaml", source_yaml.read_text(encoding="utf-8"))
    imports = ",".join((directory / name).as_uri() for name in (
        "a-base.yaml", "a-overrides.properties", "a-capability.properties"))
    private_write(directory / "a-runtime.properties", "# NON_PRODUCTION; no classpath local configuration or .env\n"
                  + "spring.config.import=" + imports + "\n")
    lines = []
    for line in source_sql.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if text.upper() in ("BEGIN;", "COMMIT;"):
            continue
        if text.upper().startswith(("DROP TABLE", "DROP SEQUENCE")):
            if not text.endswith(";"):
                raise ValueError("unexpected multiline bootstrap drop statement")
            continue
        lines.append(line)
    guarded = """\\set ON_ERROR_STOP on
-- NON_PRODUCTION. One transaction, empty isolated A only. No source DROP statements are retained.
BEGIN;
SET LOCAL statement_timeout='30s';
SET LOCAL lock_timeout='5s';
DO $$ BEGIN
 IF current_database()<>'zhongshu_design_it' OR current_user<>'design_it' THEN
  RAISE EXCEPTION 'Wrong isolated A bootstrap database or role';
 END IF;
 IF EXISTS (SELECT 1 FROM pg_namespace n WHERE left(n.nspname,3)<>'pg_' AND n.nspname<>'information_schema'
   AND (n.nspname<>'public' OR EXISTS(SELECT 1 FROM pg_class c WHERE c.relnamespace=n.oid)
   OR EXISTS(SELECT 1 FROM pg_proc p WHERE p.pronamespace=n.oid)
   OR EXISTS(SELECT 1 FROM pg_type t WHERE t.typnamespace=n.oid))) THEN
  RAISE EXCEPTION 'Bootstrap requires an empty target; refusing existing objects';
 END IF;
END $$;
"""
    # A consumer fixtures use the separate account/session system; no imported management password is usable.
    guarded += "\n".join(lines) + "\nUPDATE system_users SET status=1,password='" + secrets.token_hex(32) + "';\nCOMMIT;\n"
    private_write(directory / "a-bootstrap.sql", guarded)
    private_write(directory / "a-config-sources.json", json.dumps({
        str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in (source_yaml, source_sql)}, indent=2))
    LOG.warning("prepared complete external A configuration and guarded bootstrap; database not contacted")


def b_fixture(directory):
    if not (directory / "NON_PRODUCTION_FIXTURE").is_file():
        raise ValueError("fixture marker required")
    scopes = json.loads((directory / "fixture-scopes.json").read_text())
    if len(scopes) != 2:
        raise ValueError("two scopes required")
    for row in scopes:
        for field in ("orgId", "userId", "postId", "walletId"):
            if type(row[field]) is not int or row[field] <= 0:
                raise ValueError("fixture scope must contain positive integer identifiers")
    if any(scopes[0][field] == scopes[1][field] for field in ("orgId", "walletId")):
        raise ValueError("distinct organization wallets required")
    values = ",".join(f"({row['orgId']},{row['userId']},{row['walletId']})" for row in scopes)
    sql = f"""\\set ON_ERROR_STOP on
-- NON_PRODUCTION. Explicit synthetic opening balances, not a payment or commercial price.
BEGIN;
SET LOCAL statement_timeout='10s';
SET LOCAL lock_timeout='5s';
DO $$ DECLARE r record; BEGIN
 IF current_database()<>'zhongshu_platform_b' OR current_user<>'platform_owner' THEN
  RAISE EXCEPTION 'Wrong isolated B database or role';
 END IF;
 IF EXISTS(SELECT 1 FROM fr_design_price WHERE tenant_id=1) THEN RAISE EXCEPTION 'B prices already exist'; END IF;
 FOR r IN SELECT * FROM (VALUES {values}) AS s(org_id,user_id,wallet_id) LOOP
  IF NOT EXISTS(SELECT 1 FROM pf_wallet w JOIN pf_point_account a ON a.wallet_id=w.id AND a.tenant_id=w.tenant_id
    WHERE w.id=r.wallet_id AND w.org_id=r.org_id AND w.tenant_id=1 AND w.status='ACTIVE'
    AND NOT w.spending_frozen AND a.available_points=0 AND a.reserved_points=0)
    OR EXISTS(SELECT 1 FROM pf_point_ledger WHERE tenant_id=1 AND wallet_id=r.wallet_id) THEN
   RAISE EXCEPTION 'Expected empty newly onboarded wallet; refusing overwrite';
  END IF;
 END LOOP;
END $$;
"""
    for row in scopes:
        identity = secrets.randbelow(1 << 55) + 1
        request_key = "fixture-credit-" + secrets.token_hex(12)
        request_hash = hashlib.sha256(request_key.encode()).hexdigest()
        sql += f"""UPDATE pf_point_account SET available_points=1000,version=version+1,update_time=now()
 WHERE tenant_id=1 AND wallet_id={row['walletId']};
INSERT INTO pf_point_ledger(id,tenant_id,wallet_id,actor_id,type,delta,available_after,reserved_after,biz_type,biz_id,idempotency_key,request_hash,reason)
 VALUES({identity},1,{row['walletId']},{row['userId']},'MANUAL_CREDIT',1000,1000,0,'NON_PRODUCTION','fixture',
 '{request_key}','{request_hash}','NON_PRODUCTION synthetic opening balance');
"""
    sql += """INSERT INTO fr_design_price(tenant_id,id,version,operation,resolution,unit,prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units)
 VALUES(1,910001,1,'CREATE_BUDGET_ESTIMATE','','B_WALLET_POINTS',0,0,11,0,0,1),
 (1,910002,1,'CREATE_FLAT_JOB','2K','B_WALLET_POINTS',2,5,0,1,4,0);
COMMIT;
"""
    private_write(directory / "b-fixture.sql", sql)
    LOG.warning("prepared B synthetic wallet/ledger/retail price SQL; database not contacted")


def verify(manifest_path):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("fixture") != "NON_PRODUCTION":
        raise ValueError("manifest must explicitly declare NON_PRODUCTION")
    roots = []
    for role, launcher in [("A", "cn.iocoder.yudao.server.YudaoServerApplication"),
                           ("B", "cn.iocoder.yudao.platformserver.YudaoPlatformServerApplication")]:
        item = manifest[role]
        root = Path(item["sourceRoot"]).resolve(strict=True)
        roots.append(root)
        if not item.get("sourceRevision") or not item.get("sourceManifestSha256"):
            raise ValueError(f"{role}: source revision and frozen source manifest digest required")
        jar = Path(item["jar"]).resolve(strict=True)
        digest = hashlib.sha256()
        with jar.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != item["sha256"]:
            raise ValueError(f"{role}: jar digest mismatch")
        with zipfile.ZipFile(jar) as archive:
            text = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
            if f"Start-Class: {launcher}" not in text:
                raise ValueError(f"{role}: incorrect launcher")
        LOG.warning("verified role=%s launcher and jar SHA-256; source provenance still requires coordinator manifest", role)
    if roots[0] == roots[1]:
        raise ValueError("A and B must have independent source roots")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    tls = commands.add_parser("tls")
    tls.add_argument("--out", type=Path, required=True)
    tls.add_argument("--proxy-port", type=int, required=True)
    fixture = commands.add_parser("a-fixture")
    fixture.add_argument("--directory", type=Path, required=True)
    for name in ("pg-port", "redis-port", "api-port", "runtime-port"):
        fixture.add_argument("--" + name, type=int, required=True)
    scopes = commands.add_parser("a-scopes")
    scopes.add_argument("--directory", type=Path, required=True)
    a_configuration = commands.add_parser("a-config")
    a_configuration.add_argument("--directory", type=Path, required=True)
    a_configuration.add_argument("--source-root", type=Path, required=True)
    b_data = commands.add_parser("b-fixture")
    b_data.add_argument("--directory", type=Path, required=True)
    frozen = commands.add_parser("verify-artifacts")
    frozen.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    try:
        if args.command == "tls":
            prepare(args.out, args.proxy_port)
        elif args.command == "a-fixture":
            a_fixture(args.directory, args.pg_port, args.redis_port, args.api_port, args.runtime_port)
        elif args.command == "a-scopes":
            a_scopes(args.directory)
        elif args.command == "a-config":
            a_config(args.directory, args.source_root)
        elif args.command == "b-fixture":
            b_fixture(args.directory)
        else:
            verify(args.manifest)
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        LOG.error("preparation failed (%s); inspect private inputs, no secret values logged", type(error).__name__)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
