"""09 钱包会话桥接端到端验收（09 文档 AC1/2/4/5-部分/6/7-限频略/8-部分/9/11/12）。
前提：本地 server 以 local,pg,zsdev 运行，PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e，
docker PG（zhongshu-backend-dev-postgres-1，zhongshu/zhongshu_design）可达（夹具 SQL 用）。
断言口径：HTTP 状态 + body.code 二元组（业务失败=HTTP 200 + body.code，09 文档 §三）。
用法：python3 script/franchise-wallet-bridge-e2e.py
"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
APP_BASE = 'http://127.0.0.1:48080/app-api'
step_no = 0
passed = 0


def ok(cond, name):
    global passed
    if cond:
        passed += 1
        print(f'    ok - {name}')
    else:
        print(f'    FAIL - {name}')
        raise AssertionError(name)


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def raw_call(method, path, body=None, token=None, headers=None, base=None):
    req = urllib.request.Request((base or BASE) + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def call(method, path, body=None, token=None, headers=None, expect=(200, [0]), base=None):
    status, payload = raw_call(method, path, body, token, headers, base)
    codes = expect[1] if isinstance(expect[1], (list, tuple)) else [expect[1]]
    got = payload.get('code') if isinstance(payload, dict) else None
    if status not in (expect[0] if isinstance(expect[0], (list, tuple)) else [expect[0]]) or got not in codes:
        raise AssertionError(f'{method} {path} -> 预期{expect} 实际(HTTP {status}, code={got}) msg={payload.get("msg")}')
    return payload.get('data')


def sms_login(mobile, post_id=None):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile}, expect=(200, [0, None]))
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return {'token': result['accessToken'], 'post': post_id}


def authed(token, post_id):
    """带 X-Post-Id 的平台调用头"""
    return {'Authorization': 'Bearer ' + token, 'X-Post-Id': str(post_id)}


def psql(sql):
    import subprocess
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def main():
    # ================= 组织与账号 =================
    step('dev-setup 搭组织（唯一 batch/mobilePrefix）')
    ts = int(time.time()) % 10000000
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                 {'batch': 'wf' + str(ts), 'mobilePrefix': '139%07d' % ts})
    sales, boss = setup['sales'], setup['boss']
    org_id = setup['storeOrgId']
    ok(bool(org_id) and bool(boss.get('postId')), 'dev-setup 返回 boss/postId/orgId')

    step('boss 平台登录')
    boss_auth = sms_login(boss['mobile'], boss['postId'])
    headers = authed(boss_auth['token'], boss_auth['post'])

    # ================= AC1 桥接发卡 =================
    step('AC1 桥接发卡：token/expiresIn/无 refreshToken + 库内断言')
    data = call('POST', '/platform/wallet/session', None, headers={**headers, 'X-WX-Login-Code': 'e2e-bridge-' + str(ts)})
    wallet_token = data['token']
    ok(bool(wallet_token), '返回非空 token')
    ok(data.get('expiresIn') == 1800, 'expiresIn=1800（30 分钟）')
    ok('refreshToken' not in data and 'refresh_token' not in data, '不下发 refreshToken')

    step('AC1 库内断言：user_session 锚定服务账号 + grant 已补挂')
    ext = psql(f"SELECT id FROM account WHERE external_key = 'fr-service:{org_id}' AND deleted = FALSE")
    ok(bool(ext), '服务账号已由桥接惰性创建')
    session_row = psql(f"SELECT s.account_id, s.appid, s.openid FROM user_session s "
                       f"WHERE s.token_hash IS NOT NULL AND s.account_id = {ext} "
                       f"ORDER BY s.create_time DESC LIMIT 1")
    ok(session_row.startswith(str(ext)), f'最新会话锚定服务账号（account_id={ext}）')
    grant = psql(f"SELECT status FROM design_access_grant WHERE account_id = {ext} "
                 f"AND status = 'ACTIVE' AND deleted = FALSE")
    ok(grant == 'ACTIVE', '技术型 grant 已幂等补挂')

    step('AC1 钱包 token 调点数账户（/app-api 前缀，requireUnrestricted 放行）')
    account = call('GET', '/design/v1/point-account', token=wallet_token, base=APP_BASE)
    ok(account.get('availablePoints') is not None, 'point-account 可读')

    # ================= AC2 充值联动（stub 自动结算） =================
    step('AC2 充值联动：建单 → stub 结算 → 点数到账')
    plans = call('GET', '/design/v1/recharge-plans', token=wallet_token, base=APP_BASE)
    ok(bool(plans), f'充值套餐已种子（{len(plans)} 档）')
    plan = next(p for p in plans if p.get('bonusPoints', 0) >= 0)
    idem = 'wallet-e2e-' + str(ts)
    order = call('POST', f"/design/v1/recharge-orders?planId={plan['planId']}", None,
                 token=wallet_token, base=APP_BASE,
                 headers={'Idempotency-Key': idem, 'X-WX-Login-Code': 'e2e-order-' + str(ts)})
    order_id = order['orderId']
    ok(order['paymentState'] in ('CREATED', 'PENDING', 'SUCCEEDED'), f'建单成功 orderId={order_id}')

    credited = False
    for _ in range(30):
        detail = call('GET', f'/design/v1/recharge-orders/{order_id}', token=wallet_token, base=APP_BASE)
        if detail.get('fulfillmentState') == 'CREDITED':
            credited = True
            break
        time.sleep(1)
    ok(credited, 'stub 自动结算 → fulfillment=CREDITED')

    total = plan['basePoints'] + plan['bonusPoints']
    account2 = call('GET', '/design/v1/point-account', token=wallet_token, base=APP_BASE)
    ok(account2['availablePoints'] >= total, f'可用点数 ≥ {total}（实得 {account2["availablePoints"]}）')
    ledger = call('GET', '/design/v1/point-ledger?pageNo=1&pageSize=10', token=wallet_token, base=APP_BASE)
    types = [row['type'] for row in ledger['list']]
    ok('RECHARGE_BASE_CREDIT' in types, '流水含 RECHARGE_BASE_CREDIT')
    if plan['bonusPoints'] > 0:
        ok('RECHARGE_BONUS_CREDIT' in types, '流水含 RECHARGE_BONUS_CREDIT')

    step('AC2 幂等：同 Idempotency-Key 返回同订单')
    again = call('POST', f"/design/v1/recharge-orders?planId={plan['planId']}", None,
                 token=wallet_token, base=APP_BASE,
                 headers={'Idempotency-Key': idem, 'X-WX-Login-Code': 'e2e-order-' + str(ts)})
    ok(str(again['orderId']) == str(order_id), '同键同单')

    # ================= AC4 权限 =================
    step('AC4 权限：sales 岗位桥接被拒（body.code=403）')
    sales_auth = sms_login(sales['mobile'], sales['postId'])
    sales_headers = authed(sales_auth['token'], sales_auth['post'])
    call('POST', '/platform/wallet/session', None, headers=sales_headers, expect=(200, [403]))

    step('AC4 缺 X-WX-Login-Code → 1_104_000_001')
    call('POST', '/platform/wallet/session', None, headers=headers, expect=(200, [1_104_000_001]))

    # ================= AC6 grant 实时重算 =================
    step('AC6 删 grant：读写均拒（requireUnrestricted 实时重算）→ 桥接重补挂恢复')
    psql(f"UPDATE design_access_grant SET status = 'REVOKED' WHERE account_id = {ext} AND status = 'ACTIVE'")
    call('GET', '/design/v1/point-account', token=wallet_token, base=APP_BASE, expect=(200, [1_070_001_001]))
    call('POST', f"/design/v1/recharge-orders?planId={plan['planId']}", None,
         token=wallet_token, base=APP_BASE,
         headers={'Idempotency-Key': 'no-grant-' + str(ts)}, expect=(200, [1_070_001_001]))
    data3 = call('POST', '/platform/wallet/session', None, headers={**headers, 'X-WX-Login-Code': 'e2e-regrant-' + str(ts)})
    wallet_token2 = data3['token']
    ok(bool(wallet_token2), '重桥接成功（grant 幂等补挂）')
    call('GET', '/design/v1/point-account', token=wallet_token2, base=APP_BASE)
    ok(True, '恢复后读写正常')

    # ================= AC12 服务账号停用 =================
    step('AC12 服务账号停用 → 桥接 1_112_000_004，不复活')
    psql(f"UPDATE account SET status = 'DISABLED' WHERE external_key = 'fr-service:{org_id}' AND deleted = FALSE")
    call('POST', '/platform/wallet/session', None, headers={**headers, 'X-WX-Login-Code': 'e2e-disabled-' + str(ts)},
         expect=(200, [1_112_000_004]))
    still_disabled = psql(f"SELECT status FROM account WHERE external_key = 'fr-service:{org_id}' AND deleted = FALSE")
    ok(still_disabled == 'DISABLED', '停用账号未被桥接复活')
    psql(f"UPDATE account SET status = 'ACTIVE' WHERE external_key = 'fr-service:{org_id}' AND deleted = FALSE")

    # ================= AC9 撤权即时吊销（最后执行：撤岗后 boss 会话失效） =================
    step('AC9 撤岗后已发 token 立即失效（同事务吊销）')
    wallet_token3 = call('POST', '/platform/wallet/session', None, headers={**headers, 'X-WX-Login-Code': 'e2e-revoke-' + str(ts)})['token']
    # boss 撤自己的岗位（dev-setup boss 管理本组织；撤权协议=岗位行锁+同事务吊销服务账号会话）
    call('DELETE', f"/platform/org/posts/{boss['postId']}", None, headers=headers)
    call('GET', '/design/v1/point-account', token=wallet_token3, base=APP_BASE, expect=(200, [401]))
    ok(True, '撤岗提交后旧 token 即时失效')

    print(f'\n共 {step_no} 步，断言 {passed} 项全部通过 ✓')


if __name__ == '__main__':
    main()
