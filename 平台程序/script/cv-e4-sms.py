#!/usr/bin/env python3
"""CV-E4-SMS · 腾讯云短信真实通道验收（M02-T04 E4 部分——real client 批次#39 已实现）。

03A 校正（2026-11-13，任务书 03A）：
  1. 登录字段改为后端契约 `captcha`（SmsLoginReqVO/PlatformAuthController 均读 captcha），
     原 `code` 字段会被参数校验拒绝；
  2. send 与 login 拆分为两次独立执行：带 MOBILE_CODE 的执行只登录、不再重发验证码
     （避免替换旧验证码/触发 60s 发码限流）；无 MOBILE_CODE 的执行只发码；
  3. 目标 BASE / tenant / 证据目录可显式覆盖（环境变量 CV_E4_SMS_BASE / CV_E4_SMS_TENANT /
     CV_E4_SMS_EVIDENCE_DIR），证据目录默认基于脚本位置解析，不随执行目录漂移；
  4. 输出与证据一律脱敏手机号；验证码与令牌不写入证据。
  契约回归：script/dev/cv-e4-sms-contract-test.py（本地 mock，不发真实短信）。

前提（凭据注入——缺任一即 SKIP 退出码 2。该检查只证明本进程配置，后端运行配置由任务 04 单独核验）：
  PF_SMS_PROVIDER=real PF_SMS_SDK_APP_ID=... PF_SMS_SIGN_NAME=... PF_SMS_TEMPLATE_ID=... \
  PF_SMS_SECRET_ID=... PF_SMS_SECRET_KEY=... \
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
用法：
  第一次（发码）：MOBILE=138xxxxxxxx python3 script/cv-e4-sms.py
  第二次（登录）：MOBILE=138xxxxxxxx MOBILE_CODE=123456 python3 script/cv-e4-sms.py
验收：send 真实提交（HTTP 0）→ 真实手机收到验证码（人工确认）→
  带 MOBILE_CODE 二次执行完成登录断言；发送成功不代表登录通过，两步各自独立验收。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get('CV_E4_SMS_BASE', 'http://127.0.0.1:48080/admin-api').rstrip('/')
TENANT = os.environ.get('CV_E4_SMS_TENANT', '1')
EVIDENCE_DIR = Path(os.environ.get('CV_E4_SMS_EVIDENCE_DIR')
                    or (Path(__file__).resolve().parent.parent
                        / 'docs' / 'business-flow-audit-20261103' / 'evidence'))
REQUIRED = ['PF_SMS_PROVIDER', 'PF_SMS_SDK_APP_ID', 'PF_SMS_SIGN_NAME', 'PF_SMS_TEMPLATE_ID',
            'PF_SMS_SECRET_ID', 'PF_SMS_SECRET_KEY']
missing = [k for k in REQUIRED if not os.environ.get(k, '').strip()]
if missing:
    print('SKIP：短信 real 凭据未注入——缺 ' + ', '.join(missing))
    print('  凭据到位后执行：PF_SMS_*=... java -jar ... 后按本文件头用法运行')
    sys.exit(2)
if os.environ.get('PF_SMS_PROVIDER') != 'real':
    print('SKIP：PF_SMS_PROVIDER 须为 real')
    sys.exit(2)
MOBILE = os.environ.get('MOBILE', '').strip()
if not MOBILE:
    print('SKIP：MOBILE 未指定（接收验证码的真实手机号）')
    sys.exit(2)
MOBILE_CODE = os.environ.get('MOBILE_CODE', '').strip()


def call(method, path, body=None, token=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', TENANT)
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


def mask(mobile):
    return mobile if len(mobile) < 7 else mobile[:3] + '****' + mobile[-4:]


def main():
    run = str(int(time.time()))
    print(f'[配置] BASE={BASE} tenant={TENANT} 证据目录={EVIDENCE_DIR}')
    if not MOBILE_CODE:
        # ① 仅发码：send 与 login 拆分，本步骤不代表登录通过、不落证据
        r = call('POST', '/platform/auth/sms/send', {'mobile': MOBILE})
        assert r.get('code', 0) == 0, r
        print(f'✅ 验证码已真实提交（腾讯云短信 SDK Ok）——请查收 {mask(MOBILE)} 的短信')
        print(f'  登录另行执行：MOBILE_CODE=短信收到的验证码 MOBILE={mask(MOBILE)} '
              f'python3 script/cv-e4-sms.py')
        print('  （本步骤仅证明发送成功，不代表登录通过）')
        return
    # ② 仅登录：不重发验证码，直接断言登录
    login = call('POST', '/platform/auth/sms/login', {'mobile': MOBILE, 'captcha': MOBILE_CODE})
    data = login.get('data', login)
    assert data.get('accessToken'), login
    print('✅ 短信验证码登录成功（accessToken 取得）——M02-T04 真实送达+登录全链验收 PASS')
    evidence = {'run': run, 'mobileMasked': mask(MOBILE), 'base': BASE, 'tenant': TENANT,
                'verdict': 'PASS', 'scope': 'sms-login'}
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    with open(EVIDENCE_DIR / f'cv-e4-sms-{run}.json', 'w') as f:
        json.dump(evidence, f, ensure_ascii=False, indent=1)
    print('evidence 落盘')


if __name__ == '__main__':
    main()
