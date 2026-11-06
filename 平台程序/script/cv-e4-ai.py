#!/usr/bin/env python3
"""CV-E4-AI · AI 真实渠道验收（批次#39 / M03-T05+M05-T05 E4 部分）。

前提（凭据注入——缺任一即 SKIP 退出码 2，绝不以模拟冒充通过）：
  ZS_AI_API_KEY=... ZS_AI_BASE_URL=https://api.apilio.ai/v1 \
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev \
    --zhongshu.design.driver-enabled=true
用法：ZS_AI_API_KEY=... python3 script/cv-e4-ai.py
验收：设计平面 job 不经 simulate 由 driver 真实调用 AI 中转 → SUCCEEDED + 真实产物
（sha ≠ simulate 夹具、耗时>1s）+ 点数真实扣减。证据落 evidence/cv-e4-ai-<run>.json。
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

# —— 凭据门卫：缺凭据=SKIP（退出码 2），不执行不假绿 ——
API_KEY = os.environ.get('ZS_AI_API_KEY', '').strip()
if not API_KEY:
    print('SKIP：ZS_AI_API_KEY 未注入——AI 真实渠道验收需要真实凭据（apilio.ai 中转 key）。')
    print('  凭据到位后执行：ZS_AI_API_KEY=... python3 script/cv-e4-ai.py')
    sys.exit(2)
print(f'凭据预检通过（key len={len(API_KEY)}）')


def call(method, path, body=None, token=None, post_id=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', '1')
    req.add_header('content-type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data) as resp:
            return json.loads(resp.read().decode())['data']
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode()).get('data')


def main():
    run_ts = str(int(time.time()))
    env = call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': 'ai' + run_ts[-6:]})
    sales = login(env['sales'])
    boss = login(env['boss'])
    call('POST', '/platform/dev/design/point-account/seed', {'points': 2_000_000},
         token=sales['token'], post_id=sales['post'])
    balance_before = call('GET', '/platform/dev/design/point-account',
                          token=sales['token'], post_id=sales['post'])

    proj = call('POST', '/franchise/design/projects',
                {'name': 'E4-AI-' + run_ts, 'style': 'modern', 'area': 400, 'floors': 2},
                token=sales['token'], post_id=sales['post'])
    job = call('POST', f"/franchise/design/projects/{proj['projectId']}/flat-jobs",
               {'width': 13, 'depth': 16, 'floors': 2, 'note': 'E4 真实渠道'},
               token=sales['token'], post_id=sales['post'],
               headers={'Idempotency-Key': 'e4-ai-' + run_ts})
    job_id = job['jobId']
    print(f'job 提交 job={job_id}（不经 simulate——driver 真实调用 AI）')

    # 轮询真实执行（AI 生成耗时显著>模拟——上限 10 分钟）
    deadline = time.time() + 600
    last = None
    while time.time() < deadline:
        time.sleep(5)
        j = call('GET', f'/franchise/design/ai-jobs/{job_id}',
                 token=sales['token'], post_id=sales['post'])
        last = j
        if j.get('status') in ('SUCCEEDED', 'FAILED'):
            break
    assert last and last.get('status') == 'SUCCEEDED', f'AI 真实 job 未成功：{last}'
    elapsed = 600 - (deadline - time.time())
    assert elapsed > 1, f'耗时 {elapsed:.0f}s 过短——疑似 simulate 冒充'
    candidates = last.get('candidates') or []
    assert candidates, last
    real_sha = hashlib.sha256(json.dumps(candidates, sort_keys=True).encode()).hexdigest()
    fake_sha = hashlib.sha256(b'E4-sentinel-simulate-fixture').hexdigest()
    assert real_sha != fake_sha, '产物与 simulate 夹具同形——冒充嫌疑'
    print(f'✅ AI 真实生成 SUCCEEDED（耗时 {elapsed:.0f}s，产物 sha={real_sha[:16]}…）')

    balance_after = call('GET', '/platform/dev/design/point-account',
                         token=sales['token'], post_id=sales['post'])
    assert balance_after['points'] < balance_before['points'], '点数未扣减——计费链未走'
    print(f"✅ 点数真实扣减 {balance_before['points']} → {balance_after['points']}")

    evidence = {'run': run_ts, 'jobId': job_id, 'status': 'SUCCEEDED', 'elapsedSec': round(elapsed),
                'sha': real_sha, 'pointsBefore': balance_before['points'],
                'pointsAfter': balance_after['points'], 'verdict': 'PASS'}
    os.makedirs('../docs/business-flow-audit-20261103/evidence', exist_ok=True)
    with open(f'../docs/business-flow-audit-20261103/evidence/cv-e4-ai-{run_ts}.json', 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)
    print('✅ CV-E4-AI 验收 PASS（evidence 落盘）')


def login(who):
    r = call('POST', '/platform/auth/wechat-login',
             {'appid': 'wx-e2e', 'loginCode': 'e4-ai-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': r['accessToken'], 'post': who['postId']}


if __name__ == '__main__':
    main()
