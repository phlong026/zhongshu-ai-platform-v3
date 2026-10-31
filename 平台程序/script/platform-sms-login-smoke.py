#!/usr/bin/env python3
"""员工双通道登录冒烟：手机号短信验证码登录全链（限流、错码、成功签发、选岗）。

前置：本地 server 以 local,pg + stub 短信运行（端口 48080）。
用法：python3 script/platform-sms-login-smoke.py
依赖：franchise-m2-e2e 同款 dev-setup 一键建员工。
"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0


def call(method, path, body=None, token=None, expect_code=0):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read().decode())
    if isinstance(expect_code, int):
        expect_code = [expect_code]
    if payload.get('code') not in expect_code:
        raise AssertionError(f'{method} {path} -> code={payload.get("code")} msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def main():
    step('dev-setup 一键搭建总部与门店五名员工')
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': str(int(time.time()) % 10000)})
    mobile = setup['sales']['mobile']
    print(f'    以业务员工（尾号 {mobile[-4:]}）验证短信登录')

    step('发送登录验证码并从 dev 出码接口读取（仅 local）')
    call('POST', '/platform/auth/sms/send', {'mobile': mobile})
    stored = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    assert stored and len(stored) == 6, stored
    print(f'    验证码已出码（{stored[:2]}****）')

    step('60 秒内重复发送被限流（1104000005）')
    call('POST', '/platform/auth/sms/send', {'mobile': mobile}, expect_code=1_104_000_005)

    step('错误验证码被拒（1104000007），连续错 5 次后作废')
    for i in range(5):
        call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': '00000%d' % i}, expect_code=1_104_000_007)

    step('作废后原验证码登录被拒（1104000008，验证码过期）')
    call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': stored}, expect_code=1_104_000_008)

    step('换一名员工走成功链（不受上一手机号限流影响）')
    mobile2 = setup['finance']['mobile']
    call('POST', '/platform/auth/sms/send', {'mobile': mobile2})
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile2}')
    assert captcha and len(captcha) == 6, captcha
    print(f'    验证码已出码（{captcha[:2]}****）')

    step('验证码登录成功，签发令牌')
    login = call('POST', '/platform/auth/sms/login', {'mobile': mobile2, 'captcha': captcha})
    assert login.get('accessToken'), login
    print(f'    userId={login["userId"]} 令牌已签发')

    step('令牌可用：查询本人任职（posts/mine）')
    posts = call('GET', '/platform/posts/mine', token=login['accessToken'])
    assert posts and posts[0]['postId'], posts
    print(f'    任职 {len(posts)} 个岗位，首个 postId={posts[0]["postId"]}')

    step('未登记手机号登录被拒（1104000003）')
    stranger = '139' + '%08d' % (int(time.time()) % 100_000_000)
    call('POST', '/platform/auth/sms/send', {'mobile': stranger})
    code = call('GET', f'/platform/dev-setup/sms-captcha?mobile={stranger}')
    call('POST', '/platform/auth/sms/login', {'mobile': stranger, 'captcha': code}, expect_code=1_104_000_003)

    print('冒烟通过：短信登录限流/作废/出码/签发/选岗/未登记拒绝 全链符合预期')


if __name__ == '__main__':
    main()
