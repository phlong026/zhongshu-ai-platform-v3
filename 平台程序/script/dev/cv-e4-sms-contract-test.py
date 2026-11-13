#!/usr/bin/env python3
"""cv-e4-sms 契约测试（任务书 03A）：本地 mock 复现 code/captcha 不匹配并回归校正后的脚本。

只依赖标准库；仅监听本机回环；不发真实短信、不写真实凭据、不访问生产。
用法：python3 script/dev/cv-e4-sms-contract-test.py <结果JSON输出路径>

覆盖：
  R1 复现：原始脚本（git HEAD 版本）登录请求体为 `code`，被后端 captcha 契约拒绝（动态复现；
     若 48080 被占用则退化为静态复现并记录原因）。
  F1 发码：校正后脚本无 MOBILE_CODE 时只 send——恰好 1 次 send、0 次 login、不写登录证据。
  F2 登录：带 MOBILE_CODE 时只 login——0 次 send（不重发）、请求体含 6 位 captcha 且不含 code、
     证据落盘到显式目录且不含验证码/令牌/完整手机号。
  F3 脱敏：两次执行的 stdout 均不出现完整手机号。
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

B_ROOT = Path(__file__).resolve().parents[2]
FIXED_SCRIPT = B_ROOT / 'script' / 'cv-e4-sms.py'
MOBILE = '13800001234'
MASKED = '138****1234'
DUMMY_ENV = {
    'PF_SMS_PROVIDER': 'real',
    'PF_SMS_SDK_APP_ID': '1400000000',
    'PF_SMS_SIGN_NAME': 'mock-sign',
    'PF_SMS_TEMPLATE_ID': '1',
    'PF_SMS_SECRET_ID': 'dummy-id-not-real',
    'PF_SMS_SECRET_KEY': 'dummy-key-not-real',
}


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        with self.lock:
            self.sends = 0
            self.logins = []


STATE = State()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0) or 0)
        raw = self.rfile.read(length)
        if self.path.endswith('/platform/auth/sms/send'):
            with STATE.lock:
                STATE.sends += 1
            self._json({'code': 0, 'data': True})
            return
        if self.path.endswith('/platform/auth/sms/login'):
            try:
                body = json.loads(raw.decode() or '{}')
            except Exception:
                body = {}
            with STATE.lock:
                STATE.logins.append(body)
            captcha = body.get('captcha')
            ok = isinstance(captcha, str) and re.fullmatch(r'\d{6}', captcha) and 'code' not in body
            if ok:
                self._json({'code': 0, 'data': {'accessToken': 'mock-access-token',
                                                'refreshToken': 'mock-refresh-token',
                                                'expiresTime': 0}})
            else:
                self._json({'code': 1, 'msg': 'login 契约错误：须含6位captcha且不含code'})
            return
        self._json({'code': 1, 'msg': 'unexpected path: ' + self.path}, status=404)


def bind_server(preferred):
    """优先绑定指定端口（复现原始硬编码 BASE），失败则退回临时端口。"""
    ports = ([preferred] if preferred else []) + [0]
    for port in ports:
        try:
            server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
            return server, server.server_address[1]
        except OSError:
            continue
    raise RuntimeError('mock 端口绑定失败')


def run_script(script_path, base, evidence_dir, code=None, timeout=30):
    env = dict(os.environ)
    env.update(DUMMY_ENV)
    env['CV_E4_SMS_BASE'] = base
    env['CV_E4_SMS_TENANT'] = '1'
    env['CV_E4_SMS_EVIDENCE_DIR'] = str(evidence_dir)
    env['MOBILE'] = MOBILE
    env.pop('MOBILE_CODE', None)
    if code is not None:
        env['MOBILE_CODE'] = code
    return subprocess.run([sys.executable, str(script_path)], env=env, cwd=str(B_ROOT),
                          capture_output=True, text=True, timeout=timeout)


def main():
    out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    checks = {}
    failures = []

    def check(name, ok, detail):
        checks[name] = {'ok': bool(ok), 'detail': detail}
        if not ok:
            failures.append(name)

    # R1 复现：优先动态（48080=原始脚本硬编码 BASE），被占用则静态复现
    reproduction_mode = 'dynamic'
    server, port = bind_server(48080)
    if port != 48080:
        reproduction_mode = 'static'
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{port}/admin-api'
    original_src = subprocess.check_output(
        ['git', '-C', str(B_ROOT), 'show', 'HEAD:./script/cv-e4-sms.py'], text=True)
    try:
        if reproduction_mode == 'dynamic':
            STATE.reset()
            with tempfile.TemporaryDirectory() as tmp:
                original = Path(tmp) / 'cv-e4-sms-original.py'
                original.write_text(original_src)
                proc = run_script(original, base, Path(tmp) / 'evi', code='123456')
                wrong_login = [b for b in STATE.logins if 'code' in b and 'captcha' not in b]
                check('R1-动态复现-原始脚本被captcha契约拒绝',
                      proc.returncode != 0 and len(wrong_login) == 1,
                      f'rc={proc.returncode} login bodies={STATE.logins}')
                # 泄露发生在「仅发码」路径（发送成功后打印二次执行提示）；登录失败路径提前退出不打印
                STATE.reset()
                proc_leak = run_script(original, base, Path(tmp) / 'evi-leak', code=None)
                check('R1-复现-发码路径打印完整手机号（旧行为）',
                      proc_leak.returncode == 0 and MOBILE in proc_leak.stdout,
                      f'rc={proc_leak.returncode} 完整手机号出现在stdout={MOBILE in proc_leak.stdout}')
        else:
            check('R1-静态复现-原始脚本login发code', "'code': code" in original_src,
                  '48080 被占用，退化为源码级复现')
            check('R1-静态复现-原始脚本用法提示含完整手机号',
                  "'MOBILE=%s python3' % MOBILE" in original_src or "MOBILE=%s" in original_src,
                  '原始脚本无脱敏')
        # 其余用例统一走临时端口（与 48080 是否空闲解耦）
        if port == 48080:
            server.shutdown()
            server.server_close()
            server, port = bind_server(None)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            base = f'http://127.0.0.1:{port}/admin-api'

        # F1 发码：只 send
        STATE.reset()
        with tempfile.TemporaryDirectory() as tmp:
            evi = Path(tmp) / 'evi'
            proc = run_script(FIXED_SCRIPT, base, evi, code=None)
            check('F1-仅发码退出码0', proc.returncode == 0, f'rc={proc.returncode} stderr={proc.stderr[-300:]}')
            check('F1-恰好1次send且0次login', STATE.sends == 1 and len(STATE.logins) == 0,
                  f'sends={STATE.sends} logins={len(STATE.logins)}')
            check('F1-发送不写登录证据', not any(evi.glob('*')), 'send 步骤不应落证据文件')
            check('F1-不宣称登录通过', '登录成功' not in proc.stdout and 'PASS' not in proc.stdout,
                  '发送成功不得表述为登录 PASS')

        # F2 登录：只 login，不重发
        STATE.reset()
        with tempfile.TemporaryDirectory() as tmp:
            evi = Path(tmp) / 'evi'
            proc = run_script(FIXED_SCRIPT, base, evi, code='123456')
            check('F2-仅登录退出码0', proc.returncode == 0, f'rc={proc.returncode} stderr={proc.stderr[-300:]}')
            check('F2-登录不重发(0次send)', STATE.sends == 0, f'sends={STATE.sends}')
            check('F2-请求体含6位captcha且无code',
                  len(STATE.logins) == 1 and STATE.logins[0].get('captcha') == '123456'
                  and 'code' not in STATE.logins[0], f'bodies={STATE.logins}')
            files = list(evi.glob('*.json'))
            check('F2-登录证据落盘', len(files) == 1, f'files={files}')
            if files:
                text = files[0].read_text()
                check('F2-证据不含验证码/令牌', '123456' not in text and 'mock-access-token' not in text,
                      '证据泄露校验')
                check('F2-证据手机号脱敏', MASKED in text and MOBILE not in text, '证据脱敏校验')

        # F3 脱敏：stdout 不含完整手机号
        check('F3-输出全程脱敏', MOBILE not in proc.stdout and MOBILE not in proc.stderr,
              '校正后脚本 stdout/stderr 不得出现完整手机号')
    finally:
        server.shutdown()
        server.server_close()

    verdict = 'PASS' if not failures else 'FAIL'
    summary = {
        'verdict': verdict,
        'reproduction': reproduction_mode,
        'fixedScript': str(FIXED_SCRIPT),
        'mobileMasked': MASKED,
        'checks': checks,
        'failures': failures,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    if out_path:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1) + '\n')
    sys.exit(0 if not failures else 1)


if __name__ == '__main__':
    main()
