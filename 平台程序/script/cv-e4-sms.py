#!/usr/bin/env python3
"""CV-E4-SMS · 腾讯云短信真实通道验收（M02-T04 E4 部分——real client 批次#39 已实现）。

前提（凭据注入——缺任一即 SKIP 退出码 2）：
  PF_SMS_PROVIDER=real PF_SMS_SDK_APP_ID=... PF_SMS_SIGN_NAME=... PF_SMS_TEMPLATE_ID=... \
  PF_SMS_SECRET_ID=... PF_SMS_SECRET_KEY=... \
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
用法：MOBILE=138xxxxxxxx python3 script/cv-e4-sms.py
验收：SmsCaptchaService.send 真实提交（HTTP 0）→ 真实手机收到验证码（人工确认）→
MOBILE_CODE=xxx 二次执行完成短信登录全链（真实手机号+验证码登录成功断言）。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
REQUIRED = ['PF_SMS_PROVIDER', 'PF_SMS_SDK_APP_ID', 'PF_SMS_SIGN_NAME', 'PF_SMS_TEMPLATE_ID',
            'PF_SMS_SECRET_ID', 'PF_SMS_SECRET_KEY']
missing = [k for k in REQUIRED if not os.environ.get(k, '').strip()]
if missing:
    print('SKIP：短信 real 凭据未注入——缺 ' + ', '.join(missing))
    print('  凭据到位后执行：PF_SMS_*=... java -jar ... 后 MOBILE=13xxxxxxxxx python3 script/cv-e4-sms.py')
    sys.exit(2)
MOBILE = os.environ.get('MOBILE', '').strip()
if not MOBILE:
    print('SKIP：MOBILE 未指定（接收验证码的真实手机号）')
    sys.exit(2)
if os.environ.get('PF_SMS_PROVIDER') != 'real':
    print('SKIP：PF_SMS_PROVIDER 须为 real')
    sys.exit(2)


def call(method, path, body=None, token=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', '1')
    req.add_header('content-type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def main():
    run = str(int(time.time()))
    # 1) 发送验证码（真实腾讯云短信——HTTP 0 即提交成功；送达由人工查收确认）
    r = call('POST', '/platform/auth/sms/send', {'mobile': MOBILE})
    assert r.get('code', 0) == 0, r
    print(f'✅ 验证码已真实提交（腾讯云短信 SDK Ok）——请查收 {MOBILE[:3]}****{MOBILE[-4:]} 的短信')

    # 2) 登录全链：MOBILE_CODE 注入时用真实收到的验证码登录
    code = os.environ.get('MOBILE_CODE', '').strip()
    if not code:
        print('  二次执行完成全链：MOBILE_CODE=短信收到的验证码 MOBILE=%s python3 script/cv-e4-sms.py' % MOBILE)
        sys.exit(0)
    login = call('POST', '/platform/auth/sms/login', {'mobile': MOBILE, 'code': code})
    data = login.get('data', login)
    assert data.get('accessToken'), login
    print('✅ 短信验证码登录成功（accessToken 取得）——M02-T04 真实送达+登录全链验收 PASS')
    evidence = {'run': run, 'mobileMasked': MOBILE[:3] + '****' + MOBILE[-4:], 'verdict': 'PASS'}
    os.makedirs('../docs/business-flow-audit-20261103/evidence', exist_ok=True)
    with open(f'../docs/business-flow-audit-20261103/evidence/cv-e4-sms-{run}.json', 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)
    print('evidence 落盘')


if __name__ == '__main__':
    main()
