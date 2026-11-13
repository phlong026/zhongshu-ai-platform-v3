#!/usr/bin/env python3
"""VERIFY-ORG-POINT · 公司积分池端到端验收（24 号任务书 §七 验收门）。

前提（缺任一即 SKIP 退出码 2，绝不假绿）：
  1) 本地后端以 zsdev 起服（local,pg,zsdev），内容 provider=fake：
     java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
  2) 环境变量：
     ZS_VERIFY_ADMIN_PASSWORD=admin123   # yudao 超管密码（仅本机联调库）
     ZS_VERIFY_BASE=http://127.0.0.1:48080   # 可选，默认本机
用法：
  ZS_VERIFY_ADMIN_PASSWORD=admin123 python3 script/verify-org-point.py
验收链：开通/调点 → 内容任务预扣（公司池）→ 老板视图（余额/在途/操作合并）
        → 月度额度拒绝 → 业务权限拒绝 → 人工核对结算 → 迁移预检 → 身份映射报告。
证据落 ../docs/business-flow-audit-20261103/evidence/verify-org-point-<ts>.json
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get('ZS_VERIFY_BASE', 'http://127.0.0.1:48080').rstrip('/')
ADMIN_PASSWORD = os.environ.get('ZS_VERIFY_ADMIN_PASSWORD', '').strip()
if not ADMIN_PASSWORD:
    print('SKIP：缺少 ZS_VERIFY_ADMIN_PASSWORD（本机联调库超管密码）')
    sys.exit(2)

RUN = str(int(time.time()))
FAILS = []


def http(method, path, body=None, token=None, post_id=None, headers=None):
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
        with urllib.request.urlopen(req, data, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or '{}')
        except Exception:
            return e.code, {'raw': raw[:300]}


def check(name, ok, detail=''):
    print(('  ok  ' if ok else ' FAIL ') + name + (('  ' + str(detail)) if detail else ''))
    if not ok:
        FAILS.append(name)
    return ok


def login_staff(mobile, appid='wx-e2e'):
    status, body = http('POST', '/admin-api/platform/auth/wechat-login',
                        {'appid': appid, 'loginCode': 'vo-' + mobile, 'phoneCode': mobile})
    data = body.get('data') or {}
    if not data.get('accessToken'):
        raise SystemExit(f'员工登录失败 mobile={mobile} status={status} body={body}')
    return data['accessToken'], data.get('posts') or []


def login_admin():
    status, body = http('POST', '/admin-api/system/auth/login',
                        {'username': 'admin', 'password': ADMIN_PASSWORD})
    data = body.get('data') or {}
    if not data.get('accessToken'):
        raise SystemExit(f'管理员登录失败 status={status} body={body}')
    return data['accessToken']


def main():
    admin = login_admin()

    # 1) 一键搭建（唯一批次，避免跨轮污染）
    suffix = 'vo' + RUN[-6:]
    status, body = http('POST', '/admin-api/platform/dev-setup/franchise-e2e/setup',
                        {'batch': suffix, 'storeName': '验收门店'})
    env = body.get('data') or {}
    if status != 200 or 'storeOrgId' not in env:
        raise SystemExit(f'dev-setup 失败 status={status} body={body}')
    org_id = env['storeOrgId']
    sales, boss = env['sales'], env['boss']
    sales_token, posts = login_staff(sales['mobile'])
    sales_post = next((p['postId'] for p in posts if p.get('roleCode') == 'sales'), sales['postId'])
    boss_token, boss_posts = login_staff(boss['mobile'])
    boss_post = next((p['postId'] for p in boss_posts if p.get('roleCode') == 'boss'), boss['postId'])
    check('dev-setup 组织与岗位就绪', org_id > 0 and sales['userId'] > 0 and boss['userId'] > 0)

    # 1b) 公司开通（幂等复用组织 + 建公司积分池）；dev-setup 直接建组织不会建池
    status, body = http('POST', '/admin-api/platform/org/onboard',
                        {'orgType': 'store', 'orgName': '验收门店-' + suffix,
                         'adminName': boss['name'], 'adminMobile': boss['mobile'],
                         'designEnabled': True, 'initialCredits': 0}, token=admin)
    onboard = body.get('data') or {}
    check('公司开通幂等复用并建池',
          status == 200 and onboard.get('orgId') == org_id and onboard.get('reusedOrg') is True,
          {'expectedOrg': org_id, 'got': onboard})
    org_id = onboard.get('orgId') or org_id

    # 2) 总部调点 +100
    status, body = http('POST', '/admin-api/design/v1/org-point-adjustments',
                        {'orgId': org_id, 'credits': 100, 'bizKey': 'verify:' + RUN, 'note': '验收调点'},
                        token=admin)
    check('总部调点 +100', status == 200 and (body.get('data') or {}).get('balance') == 100,
          body.get('msg', ''))

    # 3) 销售创建数字人任务（4 字 → 2 秒 → 1 积分）
    text = '验收口播'
    expected = max(1, -(-(((len(text) + 1) // 2) * 50000) // 1000000))
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-task-' + RUN})
    task = body.get('data') or {}
    task_id = task.get('id')
    check('内容任务创建并预扣公司池', status == 200 and task_id, body.get('msg', ''))
    if not task_id:
        raise SystemExit('任务创建失败，终止')

    # 4) 老板视图：余额/在途
    status, body = http('GET', '/admin-api/platform/org-point/summary', token=boss_token, post_id=boss_post)
    summary = body.get('data') or {}
    check('老板 summary 余额扣减', status == 200 and summary.get('balance') == 100 - expected, summary)
    check('老板 summary 在途=预扣额', summary.get('inFlight') == expected, summary.get('inFlight'))

    # 5) 操作合并视图含本条 PENDING
    status, body = http('GET', '/admin-api/platform/org-point/operations', token=boss_token, post_id=boss_post)
    ops = body.get('data') or []
    op = next((o for o in ops if o.get('subject') == 'digital' and o.get('state') == 'PENDING'), None)
    check('操作合并视图含 PENDING 且金额正确', bool(op) and op.get('requested') == expected, op)
    operation_id = op.get('operationId') if op else None

    # 6) 员工 my-usage（普通员工可访问）
    status, body = http('GET', '/admin-api/platform/org-point/my-usage', token=sales_token, post_id=sales_post)
    check('员工 my-usage 可访问且本人用量正确',
          status == 200 and (body.get('data') or {}).get('used') == expected, body.get('data'))

    # 7) 月度额度：设 1 分，再建任务必须被拒（1_072_000_012）
    http('PUT', '/admin-api/platform/org-point/member-quotas',
         {'userId': sales['userId'], 'monthlyCredits': 1}, token=boss_token, post_id=boss_post)
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-quota-' + RUN})
    check('超月度额度被拒（1_072_000_012）',
          body.get('code') == 1_072_000_012 or status != 200, body)
    http('DELETE', '/admin-api/platform/org-point/member-quotas?userId=' + str(sales['userId']),
         token=boss_token, post_id=boss_post)

    # 8) 业务权限：只允许 link_resolution，digital 必须被拒（1_072_000_015）
    http('PUT', '/admin-api/platform/org-point/member-permissions',
         {'userId': sales['userId'], 'allowedSubjects': ['link_resolution']},
         token=boss_token, post_id=boss_post)
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-perm-' + RUN})
    check('业务权限拒绝 digital（1_072_000_015）',
          body.get('code') == 1_072_000_015 or status != 200, body)
    http('DELETE', '/admin-api/platform/org-point/member-permissions?userId=' + str(sales['userId']),
         token=boss_token, post_id=boss_post)

    # 9) 人工核对结算：按实际 1 单位结算，余额不再变化（预扣=实收）
    if operation_id:
        status, body = http('POST', '/admin-api/design/v1/org-point-operations/%d/settle?actualQuantity=%d'
                            % (int(operation_id), expected), token=admin)
        settled = body.get('data') or {}
        refunded = settled.get('refunded')
        check('人工核对结算成功且退差=0',
              status == 200 and refunded is not None and int(refunded) == 0, settled)
        status, body = http('GET', '/admin-api/platform/org-point/summary', token=boss_token, post_id=boss_post)
        check('结算后余额稳定、在途归零',
              (body.get('data') or {}).get('balance') == 100 - expected
              and (body.get('data') or {}).get('inFlight') == 0, body.get('data'))

    # 10) 存量迁移预检（只读）
    status, body = http('GET', '/admin-api/design/v1/point-migration/dry-run', token=admin)
    dry = body.get('data') or {}
    check('点数迁移预检可读且结构完整',
          status == 200 and all(k in dry for k in ('totalAvailable', 'eligibleAccounts', 'blocked')))

    # 11) 身份映射生成/报告（只读语义：生成候选 + 报告结构）
    status, _ = http('POST', '/admin-api/design/v1/identity-mapping/generate', {}, token=admin)
    check('身份映射生成可执行', status == 200)
    status, body = http('GET', '/admin-api/design/v1/identity-mapping/report', token=admin)
    rep = body.get('data') or {}
    check('身份映射报告结构完整',
          status == 200 and all(k in rep for k in ('total', 'confirmed', 'unmatched', 'byMatchType')),
          {k: rep.get(k) for k in ('total', 'confirmed', 'unmatched')})

    evidence = {
        'run': RUN, 'orgId': org_id, 'taskId': task_id, 'operationId': operation_id,
        'expectedCredits': expected, 'failures': FAILS, 'verdict': 'PASS' if not FAILS else 'FAIL'
    }
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'docs', 'business-flow-audit-20261103', 'evidence')
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f'verify-org-point-{RUN}.json'), 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)

    if FAILS:
        print(f'❌ VERIFY-ORG-POINT 失败 {len(FAILS)} 项：{FAILS}')
        return 1
    print('✅ VERIFY-ORG-POINT 全部通过（evidence 落盘）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
