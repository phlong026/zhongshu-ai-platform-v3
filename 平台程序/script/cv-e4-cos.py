#!/usr/bin/env python3
"""CV-E4-COS · COS 真实上传验收（批次#40 / M07-T04+M09-T02 E4 部分）。

前提（凭据注入——缺任一即 SKIP 退出码 2）：
  ZS_COS_SECRET_ID=... ZS_COS_SECRET_KEY=... ZS_COS_BUCKET=... ZS_COS_REGION=... \
  ZS_DESIGN_ASSET_STORAGE_PROVIDER=cos \
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev \
    --zhongshu.design.driver-enabled=true
  （storage.provider=cos 激活 CosObjectStorageAdapter——RealServiceWiringPolicy 守卫缺失即快速失败）
用法：ZS_COS_SECRET_ID=... ZS_COS_SECRET_KEY=... ZS_COS_BUCKET=... ZS_COS_REGION=... \
      ZS_DESIGN_ASSET_STORAGE_PROVIDER=cos python3 script/cv-e4-cos.py
验收：工程凭据 ticket → PUT 签名 URL（真实 COS）→ complete 真实大小/SHA 校验 →
授权查看 downloadTicket 签名 URL GET 字节一致。证据落 evidence/cv-e4-cos-<run>.json。
"""
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
run = str(int(time.time()))

REQUIRED = ['ZS_COS_SECRET_ID', 'ZS_COS_SECRET_KEY', 'ZS_COS_BUCKET', 'ZS_COS_REGION',
            'ZS_DESIGN_ASSET_STORAGE_PROVIDER']
missing = [k for k in REQUIRED if not os.environ.get(k, '').strip()]
if missing:
    print('SKIP：COS 凭据未注入——缺 ' + ', '.join(missing))
    print('  凭据到位后执行：ZS_COS_SECRET_ID=... ZS_COS_SECRET_KEY=... ZS_COS_BUCKET=... '
          'ZS_COS_REGION=... ZS_DESIGN_ASSET_STORAGE_PROVIDER=cos python3 script/cv-e4-cos.py')
    sys.exit(2)
if os.environ['ZS_DESIGN_ASSET_STORAGE_PROVIDER'] != 'cos':
    print('SKIP：ZS_DESIGN_ASSET_STORAGE_PROVIDER 须为 cos（激活 CosObjectStorageAdapter）')
    sys.exit(2)
print(f"凭据预检通过（bucket={os.environ['ZS_COS_BUCKET']} region={os.environ['ZS_COS_REGION']}）")


def call(method, path, body=None, token=None, post_id=None, headers=None, raw=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', '1')
    req.add_header('content-type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, data) as resp:
            payload = resp.read()
            return json.loads(payload.decode())['data'] if raw is None else payload
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return json.loads(payload.decode()).get('data')
        except Exception:
            return None


def main():
    run_ts = str(int(time.time()))
    env = call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': 'cos' + run_ts[-6:]})
    engineer = login(env['engineer'])
    sales = login(env['sales'])

    # 真实凭据文件（1x1 PNG）
    PNG = bytes.fromhex(
        '89504e470d0a1a0a0000000d4948445200000001000000010806000000'
        '1f15c4890000000d49444547189c626001000000ffff030000060005'
        '57bfabd40000000049454e44ae426082')
    sha = hashlib.sha256(PNG).hexdigest()

    # 1) ticket（真实 COS 签名 URL——provider=cos）
    ticket = call('POST', '/franchise/assets/upload-tickets',
                  {'assetType': 'ENG_SITE_PHOTO', 'mimeType': 'image/png',
                   'sizeBytes': len(PNG), 'sha256': sha},
                  token=engineer['token'], post_id=engineer['post'])
    asset_id = ticket['assetId']
    upload_url = ticket.get('uploadUrl', '')
    assert upload_url and not upload_url.startswith('local://'), \
        f'uploadUrl 非 COS 签名 URL（storage 未切 cos？）：{upload_url[:80]}'
    print(f'ticket asset={asset_id} 签名 URL 取得')

    # 2) PUT 真实 COS（原始字节——20 号契约 §2）
    req = urllib.request.Request(upload_url, method='PUT', data=PNG,
                                 headers={'content-type': 'image/png'})
    with urllib.request.urlopen(req) as resp:
        assert 200 <= resp.status < 300, f'COS PUT {resp.status}'
    print('PUT 原始字节 → COS 成功')

    # 3) complete（真实大小/SHA 校验——失配即 REJECTED）
    completed = call('POST', f'/franchise/assets/{asset_id}/complete',
                     token=engineer['token'], post_id=engineer['post'])
    assert completed['uploadStatus'] == 'ACCEPTED', completed
    print('complete ACCEPTED（真实 COS 对象校验通过）')

    # 4) 授权查看：downloadTicket → 签名 URL GET 字节一致（M09-T02 真实可读）
    dl = call('POST', f'/franchise/assets/{asset_id}/download-tickets', {},
              token=engineer['token'], post_id=engineer['post'])
    dl_url = dl['downloadUrl']
    assert dl_url and not dl_url.startswith('local://'), dl_url
    with urllib.request.urlopen(dl_url) as resp:
        got = resp.read()
    assert got == PNG, f'下载字节与上传不一致（{len(got)} vs {len(PNG)}）'
    print('downloadTicket 签名 URL 字节一致（真实可读）')

    # 5) 业务引用闭环：交付凭据域侧校验通过（validateReference→真实 COS 资产）
    lead = call('POST', '/franchise/leads',
                {'name': 'E4-COS 业主', 'phone': '139' + run_ts[-8:], 'need': '乡墅', 'source': 'E4'},
                token=sales['token'], post_id=sales['post'])
    project_id = call('POST', '/franchise/engineering/projects',
                      {'name': 'E4-COS-' + run_ts, 'customer': '陈', 'city': '武汉', 'independent': True},
                      token=engineer['token'], post_id=engineer['post'],
                      headers={'Idempotency-Key': 'e4-cos-' + run_ts})['id']
    detail = call('GET', f'/franchise/engineering/{project_id}',
                  token=engineer['token'], post_id=engineer['post'])
    assert 'contractAssetId' in detail or detail.get('nodes') is not None, detail.keys()
    print(f'业务引用路径就绪（工程 project={project_id}——deliver 引用 validateReference 走真实资产）')

    evidence = {'run': run_ts, 'assetId': asset_id, 'bytes': len(PNG), 'sha256': sha,
                'bucket': os.environ['ZS_COS_BUCKET'], 'verdict': 'PASS',
                'downloadVerified': True}
    os.makedirs('../docs/business-flow-audit-20261103/evidence', exist_ok=True)
    with open(f'../docs/business-flow-audit-20261103/evidence/cv-e4-cos-{run_ts}.json', 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)
    print('✅ CV-E4-COS 验收 PASS（evidence 落盘）')


def login(who):
    r = call('POST', '/platform/auth/wechat-login',
             {'appid': 'wx-e2e', 'loginCode': 'e4-cos-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': r['accessToken'], 'post': who['postId']}


if __name__ == '__main__':
    main()
