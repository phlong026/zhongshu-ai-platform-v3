"""Prepare an isolated loopback B runtime. Never imports a deployed .env or prints credentials."""
import base64
import json
import re
import secrets
import socket
from pathlib import Path

BACK = Path('E:/众墅之家AI赋能平台V3版-后端/平台程序')
PRIVATE = Path('C:/Users/Administrator/.codex/artifacts/business-system-audit-20261116/live-b')
PRIVATE.mkdir(exist_ok=False)
for port in [56389,18086]:
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',port))
creds = {'owner':secrets.token_urlsafe(28),'app':secrets.token_urlsafe(28),'redis':secrets.token_urlsafe(28),
         'adminUser':'audit'+secrets.token_hex(4),'adminPassword':'Aa1!'+secrets.token_hex(6)}
(PRIVATE/'secrets.json').write_text(json.dumps(creds),encoding='utf-8')
(PRIVATE/'admin-username').write_text(creds['adminUser'],encoding='utf-8')
(PRIVATE/'admin-password').write_text(creds['adminPassword'],encoding='utf-8')
(PRIVATE/'setup.sql').write_text("CREATE ROLE platform_owner LOGIN PASSWORD '"+creds['owner']+"';\n"
    "CREATE ROLE platform_app LOGIN PASSWORD '"+creds['app']+"';\n"
    "CREATE DATABASE zhongshu_platform_b OWNER platform_owner;\n",encoding='utf-8')
(PRIVATE/'grant.sql').write_text('GRANT CONNECT ON DATABASE zhongshu_platform_b TO platform_app;\n'
    'GRANT USAGE ON SCHEMA public TO platform_app;\n'
    'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO platform_app;\n'
    'GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO platform_app;\n',encoding='utf-8')


def properties(names):
    result={}
    for name in names:
        for line in (BACK/'yudao-platform-server/src/main/resources'/name).read_text(encoding='utf-8').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key,value=line.split('=',1);result[key.strip()]=value.strip()
    result.pop('spring.config.import',None)
    return result


def save_config(name, values):
    for _ in range(20):
        changed=False
        for key,value in list(values.items()):
            def replace(match):
                token=match.group(1);reference,separator,default=token.partition(':')
                return values.get(reference,default if separator else '')
            updated=re.sub(r'\$\{([^{}]+)\}',replace,value)
            if value!=updated:values[key]=updated;changed=True
        if not changed:break
    assert not any('${' in value for value in values.values())
    (PRIVATE/name).write_text('\n'.join(key+'='+value for key,value in values.items())+'\n',encoding='utf-8')


maintenance=properties(['application.properties','application-pg.properties','application-migration.properties'])
maintenance.update({'spring.profiles.active':'pg,migration','spring.flyway.url':'jdbc:postgresql://127.0.0.1:55439/zhongshu_platform_b',
    'spring.flyway.user':'platform_owner','spring.flyway.password':creds['owner'],
    'platform.bootstrap.admin-username-file':(PRIVATE/'admin-username').as_posix(),
    'platform.bootstrap.admin-password-file':(PRIVATE/'admin-password').as_posix()})
save_config('b-maintenance.properties',maintenance)
runtime=properties(['application.properties','application-pg.properties'])
runtime.update({'spring.profiles.active':'pg,local','server.address':'127.0.0.1','server.port':'18086',
    'spring.datasource.dynamic.datasource.master.url':'jdbc:postgresql://127.0.0.1:55439/zhongshu_platform_b',
    'spring.datasource.dynamic.datasource.master.username':'platform_app',
    'spring.datasource.dynamic.datasource.master.password':creds['app'],
    'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':'56389',
    'spring.data.redis.username':'platform_app','spring.data.redis.password':creds['redis'],
    'platform.identity.wechat.provider':'stub','platform.identity.sms.provider':'stub',
    'platform.identity.wechat.apps[0].appid':'touristappid','platform.identity.wechat.apps[0].secret':secrets.token_urlsafe(24),
    'yudao.security.mock-enable':'false','yudao.captcha.enable':'false',
    'zhongshu.org-point.headquarters-tenant-id':'1','zhongshu.design.remote.enabled':'true',
    'zhongshu.design.remote.origin':'https://design.example.test','zhongshu.design.public-cases.origin':'https://public-cases.example.test',
    'zhongshu.design.remote.caller-app-id':'local-audit-b','zhongshu.design.remote.active-key-id':'local-audit-key',
    'zhongshu.design.remote.keys.local-audit-key':base64.b64encode(secrets.token_bytes(32)).decode(),
    'zhongshu.franchise.asset.storage.provider':'local','zhongshu.franchise.asset.storage-root':(PRIVATE/'assets').as_posix(),
    'zhongshu.content.provider':'real','zhongshu.content.task-sweep-enabled':'false'})
save_config('b-runtime.properties',runtime)
(PRIVATE/'redis.conf').write_text('bind 127.0.0.1\nport 56389\nprotected-mode yes\nsave ""\nappendonly no\n'
    'user default off\nuser platform_app on >'+creds['redis']+' ~* &* +@all\n'
    'dir .\n',encoding='utf-8')
print(json.dumps({'prepared':True,'privateDirectory':str(PRIVATE),'api':'http://127.0.0.1:18086',
                  'externalWechatAndSms':'local stubs only','businessSecurityMock':False,'paidAiCalls':False}))
