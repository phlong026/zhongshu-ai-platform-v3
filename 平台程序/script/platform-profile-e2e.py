#!/usr/bin/env python3
"""阶段 0 · 基-3 员工 profile 接口端到端验收（08 文档 AC2-AC9）。

前提：本地 server 以 local,pg,zsdev 运行，PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e。
断言口径：HTTP 状态 + body.code 二元组（未认证为 HTTP 200 + code=401，08 文档 F9）。
用法：python3 script/platform-profile-e2e.py
"""
import json
import threading
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0


def raw_call(method, path, body=None, token=None):
    """返回 (http_status, payload)；不抛业务断言。"""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def call(method, path, body=None, token=None, expect=(200, [0])):
    status, payload = raw_call(method, path, body, token)
    ok_status = status in (expect[0] if isinstance(expect[0], (list, tuple)) else [expect[0]])
    codes = expect[1] if isinstance(expect[1], (list, tuple)) else [expect[1]]
    got = payload.get('code') if isinstance(payload, dict) else None
    if not ok_status or got not in codes:
        raise AssertionError(f'{method} {path} -> 预期{expect} 实际(HTTP {status}, code={got}) msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def sms_login(mobile):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile}, expect=(200, [0, None],))
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return result['accessToken']


def psql(sql):
    import subprocess
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


NICK_INVALID = 1_101_000_011
AVATAR_INVALID = 1_101_000_012


def main():
    # ================= 组织与账号 =================
    step('dev-setup 搭组织（唯一 batch/mobilePrefix，防撞批次串用户）')
    ts = int(time.time()) % 10000000
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                 {'batch': 'pf' + str(ts), 'mobilePrefix': '137%07d' % ts})
    sales, boss = setup['sales'], setup['boss']

    step('AC2 未认证：GET/PUT /platform/profile/mine 均 (HTTP 200, code=401)')
    for method in ('GET', 'PUT'):
        status, payload = raw_call(method, '/platform/profile/mine', {} if method == 'PUT' else None)
        assert status == 200 and payload.get('code') == 401, (status, payload)

    step('AC2 短信登录后 GET mine：本人 id/nickname/mobile/avatar 四字段与底座一致')
    token = sms_login(sales['mobile'])
    p = call('GET', '/platform/profile/mine', token=token)
    assert p['id'] == sales['userId'], p
    assert p['nickname'] == sales['name'], p
    assert p['mobile'] == sales['mobile'], p
    assert 'avatar' in p, p

    # ================= AC3 昵称 =================
    step('AC3 昵称边界 1/20 字成功且读回一致；trim 归一化落库')
    call('PUT', '/platform/profile/mine', {'nickname': '张'}, token=token)
    assert call('GET', '/platform/profile/mine', token=token)['nickname'] == '张'
    call('PUT', '/platform/profile/mine', {'nickname': '张' * 20}, token=token)
    assert call('GET', '/platform/profile/mine', token=token)['nickname'] == '张' * 20
    call('PUT', '/platform/profile/mine', {'nickname': '  张业务 '}, token=token)
    row = call('GET', '/platform/profile/mine', token=token)
    assert row['nickname'] == '张业务', row
    db_nick = psql(f"SELECT nickname FROM system_users WHERE id={sales['userId']}")
    assert db_nick == '张业务', db_nick  # trim 后值落库

    step('AC3 拒绝集：21字/空串/纯空格/缺字段/类型错 → code=1_101_000_011 且资料不变')
    before = call('GET', '/platform/profile/mine', token=token)
    bad_nick_bodies = [{'nickname': '张' * 21}, {'nickname': ''}, {'nickname': '   '}, {}, {'nickname': 123}]
    for body in bad_nick_bodies:
        call('PUT', '/platform/profile/mine', body, token=token, expect=(200, [NICK_INVALID]))
    after = call('GET', '/platform/profile/mine', token=token)
    assert after == before, '昵称校验失败请求不应改动资料'

    # ================= AC4 头像 =================
    step('AC4 头像 URL 更新：读回同值 + 查库同值')
    url = 'https://cdn.example.com/avatars/e2e-%d.png' % ts
    call('PUT', '/platform/profile/mine', {'nickname': '张业务', 'avatar': url}, token=token)
    assert call('GET', '/platform/profile/mine', token=token)['avatar'] == url
    assert psql(f"SELECT avatar FROM system_users WHERE id={sales['userId']}") == url

    step('AC4 显式 null 清除：GET avatar=null 且查库空串；空串清除同效')
    code, payload = None, None
    req = urllib.request.Request(BASE + '/platform/profile/mine', method='PUT', data=json.dumps(
        {'nickname': '张业务', 'avatar': None}).encode())
    req.add_header('content-type', 'application/json'); req.add_header('tenant-id', '1')
    req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode())
    assert resp.status == 200 and payload.get('code') == 0, payload
    assert payload['data']['avatar'] is None, payload
    assert psql(f"SELECT avatar FROM system_users WHERE id={sales['userId']}") == ''
    call('PUT', '/platform/profile/mine', {'nickname': '张业务', 'avatar': ''}, token=token)
    assert call('GET', '/platform/profile/mine', token=token)['avatar'] is None

    step('AC4 键缺省=保持：先设 URL，再只改昵称，查库 avatar 列不变')
    call('PUT', '/platform/profile/mine', {'nickname': '张业务', 'avatar': url}, token=token)
    call('PUT', '/platform/profile/mine', {'nickname': '张改昵'}, token=token)
    assert psql(f"SELECT avatar FROM system_users WHERE id={sales['userId']}") == url

    step('AC4 拒绝集：javascript:/ftp://x/513长URL/类型错 → 1_101_000_012 且资料不变')
    exact512 = 'https://x/' + 'a' * (512 - len('https://x/'))
    assert len(exact512) == 512
    bad_avatars = ['javascript:alert(1)', 'ftp://x', exact512 + 'a', {'evil': 1}]
    for bad in bad_avatars:
        call('PUT', '/platform/profile/mine', {'nickname': '张改昵', 'avatar': bad}, token=token,
             expect=(200, [AVATAR_INVALID]))
    assert call('GET', '/platform/profile/mine', token=token)['avatar'] == url, '非法头像请求不应改动资料'
    step('AC4 边界：恰 512 长 URL 成功')
    call('PUT', '/platform/profile/mine', {'nickname': '张改昵', 'avatar': exact512}, token=token)
    assert psql(f"SELECT avatar FROM system_users WHERE id={sales['userId']}") == exact512

    # ================= AC5 幂等重放 =================
    step('AC5 幂等重放：同 body 连续两次 PUT，读回一致')
    body = {'nickname': '幂等昵称'}
    call('PUT', '/platform/profile/mine', body, token=token)
    first = call('GET', '/platform/profile/mine', token=token)
    call('PUT', '/platform/profile/mine', body, token=token)
    assert call('GET', '/platform/profile/mine', token=token) == first

    # ================= AC6 隔离 =================
    step('AC6 隔离：boss 更新自己不影响 sales；各自只读本人')
    boss_token = sms_login(boss['mobile'])
    call('PUT', '/platform/profile/mine', {'nickname': '老板本名'}, token=boss_token)
    p_sales = call('GET', '/platform/profile/mine', token=token)
    assert p_sales['nickname'] == '幂等昵称' and p_sales['id'] == sales['userId'], p_sales
    p_boss = call('GET', '/platform/profile/mine', token=boss_token)
    assert p_boss['nickname'] == '老板本名' and p_boss['id'] == boss['userId'], p_boss

    # ================= AC7 手机号不变性 =================
    step('AC7 PUT 额外携带 mobile：被忽略不报错，GET mobile 不变')
    call('PUT', '/platform/profile/mine', {'nickname': '张改昵', 'mobile': '19999999999'}, token=token)
    assert call('GET', '/platform/profile/mine', token=token)['mobile'] == sales['mobile']

    # ================= AC8 微信登录回归 =================
    step('AC8 微信首登绑定（带 phoneCode）→ 记录绑定行与 mobile_hash')
    login_code = 'e2e-profile-%d' % ts
    first = call('POST', '/platform/auth/wechat-login',
                 {'appid': 'wx-e2e', 'loginCode': login_code, 'phoneCode': sales['mobile']})
    assert first.get('accessToken') and first.get('userId') == sales['userId'], first
    openid = 'stub-' + login_code
    hash_before = psql(f"SELECT mobile_hash FROM pf_wechat_binding WHERE appid='wx-e2e' AND openid='{openid}' AND status='ACTIVE'")
    assert len(hash_before) == 64, hash_before

    step('AC8 PUT 改昵称+头像后，同身份不带 phoneCode 再登：needPhone 假、userId 不变（绑定未被资料更新破坏）')
    call('PUT', '/platform/profile/mine', {'nickname': '微信回归', 'avatar': url}, token=token)
    again = call('POST', '/platform/auth/wechat-login', {'appid': 'wx-e2e', 'loginCode': login_code})
    assert again.get('userId') == sales['userId'], again
    assert not again.get('needPhone'), again
    assert again.get('accessToken'), again
    hash_after = psql(f"SELECT mobile_hash FROM pf_wechat_binding WHERE appid='wx-e2e' AND openid='{openid}' AND status='ACTIVE'")
    assert hash_after == hash_before, (hash_before, hash_after)

    # ================= AC9 真 PG 并发 =================
    step('AC9 两线程并发 PUT（不同昵称）：均成功，终值其一、行无损坏')
    results = []

    def worker(name):
        try:
            call('PUT', '/platform/profile/mine', {'nickname': name}, token=token)
            results.append(name)
        except AssertionError as e:  # noqa: 保留断言信息
            results.append(e)

    threads = [threading.Thread(target=worker, args=(n,)) for n in ('并发甲', '并发乙')]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert all(not isinstance(r, AssertionError) for r in results), results
    final = call('GET', '/platform/profile/mine', token=token)
    assert final['nickname'] in ('并发甲', '并发乙'), final
    db_final = psql(f"SELECT nickname FROM system_users WHERE id={sales['userId']}")
    assert db_final == final['nickname'], (db_final, final)

    print(f'\n全部断言通过 ✔  （基-3 profile e2e 共 {step_no} 步）')


if __name__ == '__main__':
    main()
