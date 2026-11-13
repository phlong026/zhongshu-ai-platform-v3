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
覆盖：开通建池 → 调点 → 内容任务预扣 → 老板 summary/合并视图 → 员工 my-usage
      → 月度额度拒绝 → 业务权限拒绝 → 人工核对结算（退差 0）→ 迁移预检 → 身份映射报告。
说明：三态代数、跨月归月、并发与终态互斥由集成测试覆盖（OrgPoint*/OrgMember*），
      本脚本是"真实 HTTP + 真实库"的端到端冒烟门，不替代集成测试。
证据落 docs/business-flow-audit-20261103/evidence/verify-org-point-<run>.json
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get('ZS_VERIFY_BASE', 'http://127.0.0.1:48080').rstrip('/')
ADMIN_PASSWORD = os.environ.get('ZS_VERIFY_ADMIN_PASSWORD', '').strip()
if not ADMIN_PASSWORD:
    print('SKIP：缺少 ZS_VERIFY_ADMIN_PASSWORD（本机联调库超管密码）')
    sys.exit(2)

# P2-1：毫秒 + 随机后缀，保证业务键/批次/证据文件名唯一
RUN = '%d%03d' % (int(time.time() * 1000), random.randint(0, 999))
FAILS = []
CHECKS = 0


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
    global CHECKS
    CHECKS += 1
    print(('  ok  ' if ok else ' FAIL ') + name + (('  ' + str(detail)) if detail and not ok else ''))
    if not ok:
        FAILS.append(name)
    return ok


def fail(name, detail=''):
    """流程级失败：同时计入总数与失败数（避免"失败 1/0 项"）。"""
    global CHECKS
    CHECKS += 1
    FAILS.append(name)
    print(' FAIL ' + name + (('  ' + str(detail)) if detail else ''))


def ok_body(status, body):
    """成功响应：HTTP 200 且业务 code 显式等于 0（缺码/None 不算成功）。"""
    return status == 200 and body.get('code') == 0


def has_code(body, code):
    """错误断言：业务错误码精确匹配（yudao 错误可能仍为 HTTP 200）。"""
    return body.get('code') == code


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
    cleanup = []          # [(name, method, path, token, post_id)]，finally 执行且失败计入 FAILS
    sink = {'orgId': None, 'taskId': None, 'operationId': None, 'expected': 0,
            'completed': False}
    try:
        admin = login_admin()   # 登录失败也必须落 FAIL 证据（不能在 try 外）
        run(admin, cleanup, sink)
    except SystemExit as e:  # 流程提前终止也要落 FAIL 证据，且不得返回 0
        fail('流程未完成：' + str(e))
    except Exception as e:   # 网络/解析异常同样计失败
        fail('流程异常：' + repr(e))
    finally:
        for name, method, path, token, post_id in reversed(cleanup):
            try:
                status, body = http(method, path, token=token, post_id=post_id)
                if not ok_body(status, body):
                    check('清理：' + name, False, body)
            except Exception as e:  # 异常也要计入失败（P1-3）
                check('清理：' + name, False, str(e))
        write_evidence(sink)
    # 最终结论在清理之后统一输出，避免"先报成功、后因清理失败翻案"的矛盾
    if FAILS or not sink.get('completed'):
        print('❌ VERIFY-ORG-POINT 失败 %d/%d 项：%s' % (len(FAILS), CHECKS, FAILS))
        return 1
    print('✅ VERIFY-ORG-POINT 全部通过（%d 项）' % CHECKS)
    return 0


def run(admin, cleanup, sink):
    # 1) 一键搭建（唯一批次）
    suffix = 'vo' + RUN
    status, body = http('POST', '/admin-api/platform/dev-setup/franchise-e2e/setup',
                        {'batch': suffix, 'storeName': '验收门店'})
    env = body.get('data') or {}
    if not ok_body(status, body) or 'storeOrgId' not in env:
        raise SystemExit(f'dev-setup 失败 status={status} body={body}')
    org_id = env['storeOrgId']
    sink['orgId'] = org_id
    sales, boss = env['sales'], env['boss']
    sales_token, posts = login_staff(sales['mobile'])
    sales_post = next((p['postId'] for p in posts if p.get('roleCode') == 'sales'), sales['postId'])
    boss_token, boss_posts = login_staff(boss['mobile'])
    boss_post = next((p['postId'] for p in boss_posts if p.get('roleCode') == 'boss'), boss['postId'])
    check('dev-setup 组织与岗位就绪', org_id > 0 and sales['userId'] > 0 and boss['userId'] > 0)

    # 1b) 公司开通（幂等复用 + 建池）
    status, body = http('POST', '/admin-api/platform/org/onboard',
                        {'orgType': 'store', 'orgName': '验收门店-' + suffix,
                         'adminName': boss['name'], 'adminMobile': boss['mobile'],
                         'designEnabled': True, 'initialCredits': 0}, token=admin)
    onboard = body.get('data') or {}
    check('公司开通幂等复用并建池',
          ok_body(status, body) and onboard.get('orgId') == org_id and onboard.get('reusedOrg') is True,
          {'expectedOrg': org_id, 'got': onboard})

    # 2) 总部调点 +100
    status, body = http('POST', '/admin-api/design/v1/org-point-adjustments',
                        {'orgId': org_id, 'credits': 100, 'bizKey': 'verify:' + RUN, 'note': '验收调点'},
                        token=admin)
    check('总部调点 +100', ok_body(status, body) and (body.get('data') or {}).get('balance') == 100,
          body.get('msg', ''))

    # 3) 内容任务预扣（4 字 → 2 秒 → 1 积分）
    text = '验收口播'
    expected = max(1, -(-(((len(text) + 1) // 2) * 50000) // 1000000))
    sink['expected'] = expected
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-task-' + RUN})
    task = body.get('data') or {}
    task_id = task.get('id')
    sink['taskId'] = task_id
    check('内容任务创建并预扣公司池', ok_body(status, body) and bool(task_id), body.get('msg', ''))
    if not task_id:
        raise SystemExit('任务创建失败，终止（后续断言不可信）')

    # 4) 老板视图
    status, body = http('GET', '/admin-api/platform/org-point/summary', token=boss_token, post_id=boss_post)
    summary = body.get('data') or {}
    check('老板 summary 余额扣减',
          ok_body(status, body) and summary.get('balance') == 100 - expected, summary)
    check('老板 summary 在途=预扣额', summary.get('inFlight') == expected, summary.get('inFlight'))

    # 5) 操作合并视图：operationId 必须是硬断言（P1-2）
    status, body = http('GET', '/admin-api/platform/org-point/operations', token=boss_token, post_id=boss_post)
    ops = body.get('data') or []
    op = next((o for o in ops if o.get('subject') == 'digital' and o.get('state') == 'PENDING'), None)
    operation_id = op.get('operationId') if op else None
    sink['operationId'] = operation_id
    check('操作合并视图含 PENDING 且金额正确（operationId 非空）',
          ok_body(status, body) and bool(operation_id) and op.get('requested') == expected, op)
    if not operation_id:
        raise SystemExit('operationId 缺失，终止（不可跳过结算断言）')

    # 6) 员工 my-usage
    status, body = http('GET', '/admin-api/platform/org-point/my-usage', token=sales_token, post_id=sales_post)
    check('员工 my-usage 可访问且本人用量正确',
          ok_body(status, body) and (body.get('data') or {}).get('used') == expected, body.get('data'))

    # 7) 月度额度：设 1 分 → 再建任务必须 1_072_000_012；清理必检
    cleanup.append(('删月度额度', 'DELETE',
                    '/admin-api/platform/org-point/member-quotas?userId=' + str(sales['userId']),
                    boss_token, boss_post))
    status, body = http('PUT', '/admin-api/platform/org-point/member-quotas',
                        {'userId': sales['userId'], 'monthlyCredits': 1},
                        token=boss_token, post_id=boss_post)
    check('设置月度额度成功', ok_body(status, body), body)
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-quota-' + RUN})
    check('超月度额度被拒（1_072_000_012）', has_code(body, 1_072_000_012), body)

    # 8) 业务权限：只允许 link_resolution → digital 必须 1_072_000_015；清理必检
    cleanup.append(('删业务权限', 'DELETE',
                    '/admin-api/platform/org-point/member-permissions?userId=' + str(sales['userId']),
                    boss_token, boss_post))
    status, body = http('PUT', '/admin-api/platform/org-point/member-permissions',
                        {'userId': sales['userId'], 'allowedSubjects': ['link_resolution']},
                        token=boss_token, post_id=boss_post)
    check('设置业务权限成功', ok_body(status, body), body)
    status, body = http('POST', '/admin-api/franchise/content/tasks',
                        {'kind': 'digital', 'voice': 'v', 'text': text, 'avatar': 'a'},
                        token=sales_token, post_id=sales_post,
                        headers={'Idempotency-Key': 'vo-perm-' + RUN})
    check('业务权限拒绝 digital（1_072_000_015）', has_code(body, 1_072_000_015), body)

    # 9) 人工核对结算：退差 0；结算后余额稳定
    status, body = http('POST', '/admin-api/design/v1/org-point-operations/%d/settle?actualQuantity=%d'
                        % (int(operation_id), expected), token=admin)
    settled = body.get('data') or {}
    ok = ok_body(status, body) and settled.get('refunded') is not None and int(settled['refunded']) == 0
    check('人工核对结算成功且退差=0', ok, settled)
    status, body = http('GET', '/admin-api/platform/org-point/summary', token=boss_token, post_id=boss_post)
    data = body.get('data') or {}
    check('结算后余额稳定、在途归零',
          ok_body(status, body) and data.get('balance') == 100 - expected and data.get('inFlight') == 0, data)

    # 10) 迁移预检（只读）
    status, body = http('GET', '/admin-api/design/v1/point-migration/dry-run', token=admin)
    dry = body.get('data') or {}
    check('点数迁移预检可读且结构完整',
          ok_body(status, body) and all(k in dry for k in ('totalAvailable', 'eligibleAccounts', 'blocked')),
          body)

    # 11) 身份映射生成/报告（生成必须 code==0）
    status, body = http('POST', '/admin-api/design/v1/identity-mapping/generate', {}, token=admin)
    check('身份映射生成可执行', ok_body(status, body), body)
    status, body = http('GET', '/admin-api/design/v1/identity-mapping/report', token=admin)
    rep = body.get('data') or {}
    check('身份映射报告结构完整',
          ok_body(status, body) and all(k in rep for k in ('total', 'confirmed', 'unmatched', 'byMatchType')),
          {k: rep.get(k) for k in ('total', 'confirmed', 'unmatched')})

    sink['completed'] = True


def write_evidence(sink):
    evidence = {
        'run': RUN, 'checks': CHECKS, 'failures': FAILS,
        'orgId': sink.get('orgId'), 'taskId': sink.get('taskId'),
        'operationId': sink.get('operationId'), 'expectedCredits': sink.get('expected'),
        'completed': bool(sink.get('completed')),
        'verdict': 'PASS' if (not FAILS and sink.get('completed')) else 'FAIL'
    }
    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'docs', 'business-flow-audit-20261103', 'evidence')
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f'verify-org-point-{RUN}.json'), 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)
    print(f'证据已落盘：{out_dir}/verify-org-point-{RUN}.json')


if __name__ == '__main__':
    sys.exit(main())
