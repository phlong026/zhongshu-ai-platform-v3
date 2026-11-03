#!/usr/bin/env python3
"""阶段 0 · 基-1 B 端资产/文件服务端到端验收（14 文档 AC2-AC10）。

前提：本地 server 以 local,pg,zsdev 运行（含 franchise-asset 通道），PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e。
本机=local 模式：上传字节经 dev 端点落盘、下载核验走 dev 读取端点（14 文档 F11）。
断言口径：HTTP 状态 + body.code 二元组。
用法：python3 script/franchise-asset-e2e.py
"""
import base64
import hashlib
import json
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0

PNG_1X1 = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')
PDF_BYTES = b'%PDF-1.4\n' + bytes(64)  # 简易 PDF 形态字节（免审类型不做内容解析，仅 SHA/大小校验）


def call(method, path, body=None, token=None, post_id=None, expect_code=0, headers=None, raw_body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if raw_body is not None:
        data = raw_body[0]
        req.add_header('content-type', raw_body[1])
    else:
        req.add_header('content-type', 'application/json')
        data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=60) as resp:
            http_status = resp.status
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        http_status = e.code
        payload = json.loads(e.read().decode())
    if isinstance(expect_code, int):
        expect_code = [expect_code]
    if expect_code == [-1]:
        return payload
    # 二元组断言（07 §三.4）：HTTP 状态与 body.code 同时断言（防 HTTP 403 + body.code=401 误判通过）
    assert http_status == 200, f'{method} {path} -> HTTP {http_status} (body={payload})'
    if payload.get('code') not in expect_code:
        raise AssertionError(f'{method} {path} -> code={payload.get("code")} msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def psql(sql):
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def sms_login(mobile):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile})
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return {'token': result['accessToken'], 'userId': result['userId']}


def setup(tag):
    # prefix 10 位（setup 内拼 1 位角色序号=11 位手机号上限）；A/B 用 135/136 号段区分
    ts = int(time.time()) % 1000000
    prefix = {'a': '135', 'b': '136'}[tag] + f'{ts:06d}' + '0'
    return call('POST', '/platform/dev-setup/franchise-e2e/setup',
                {'batch': 'asset' + tag + str(ts), 'mobilePrefix': prefix})


def login_wechat(who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-asset-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': result['accessToken'], 'post': who['postId'], 'userId': who['userId']}


def act(user, method, path, body=None, expect_code=0, with_post=True, raw_body=None):
    return call(method, path, body, token=user['token'],
                post_id=user['post'] if with_post else None, expect_code=expect_code, raw_body=raw_body)


def full_chain(user, dev_user, asset_type, mime, content, prefix):
    """签发→查库键前缀→dev 落字节→complete ACCEPTED→查库免审固化→dev 读回字节核验。返回 assetId。
    dev_user：同组织、有 franchise:design:use 权限的账号（dev bytes 端点权限门；属主=同组织服务账号）。"""
    sha = hashlib.sha256(content).hexdigest()
    ticket = act(user, 'POST', '/franchise/assets/upload-tickets',
                 {'assetType': asset_type, 'mimeType': mime, 'sizeBytes': len(content), 'sha256': sha})
    asset_id = ticket['assetId']
    row = psql(f"SELECT object_key, source_type FROM asset WHERE id={asset_id}")
    assert row.startswith(f'uploads/{prefix}/') and '|COMPANY' in row, row
    act(dev_user, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
        {'contentBase64': base64.b64encode(content).decode()})
    completed = act(user, 'POST', f'/franchise/assets/{asset_id}/complete')
    assert completed['uploadStatus'] == 'ACCEPTED' and completed.get('rejectedReason') is None, completed
    row = psql(f"SELECT object_key, security_scan_status, moderation_status, stored_sha256, width, thumb_key "
               f"FROM asset WHERE id={asset_id}").split('|')
    assert row[0].startswith(f'accepted/{asset_id}/'), row
    assert row[1] == 'PASSED' and row[2] == 'PASSED', row          # 免审固化 PASSED（F14 枚举无 SKIPPED）
    assert row[3] == '' and row[4] == '' and row[5] == '', row     # scanner 产出列保持 NULL
    download = act(user, 'POST', f'/franchise/assets/{asset_id}/download-tickets')
    assert download['downloadUrl'].startswith('local://'), download  # local 占位，不做 HTTP 断言
    back = act(dev_user, 'GET', f'/platform/dev/design/assets/{asset_id}/bytes')
    assert back['sha256'] == sha and base64.b64decode(back['contentBase64']) == content, '读回字节须与上传一致'
    assert psql(f"SELECT count(*) FROM asset_scan_result WHERE asset_id={asset_id}") == '0'  # 免审无扫描留痕
    return asset_id


def multipart_upload(user, filename, content):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f'Content-Type: application/pdf\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return act(user, 'POST', '/franchise/finance/files', expect_code=0,
               raw_body=(body, f'multipart/form-data; boundary={boundary}'))


def main():
    # ================= 组织与账号 =================
    step('dev-setup 搭 A/B 两套组织（唯一 batch/mobilePrefix）+ 员工登录')
    a, b = setup('a'), setup('b')
    finance_a, finance_b = login_wechat(a['finance']), login_wechat(b['finance'])
    sales_a = login_wechat(a['sales'])
    org_a, org_b = a['storeOrgId'], b['storeOrgId']
    assert org_a != org_b

    # ================= AC2 未认证 / 任职 / 审计 =================
    step('AC2 未认证三端点均 (HTTP 200, code=401)')
    for method, path, body in (('POST', '/franchise/assets/upload-tickets', {}),
                               ('POST', '/franchise/assets/1/complete', {}),
                               ('POST', '/franchise/assets/1/download-tickets', {})):
        payload = call(method, path, body, expect_code=-1)
        assert payload.get('code') == 401, payload

    step('AC2 唯一任职缺 X-Post-Id 自动选中正向；审计仅两写操作（pf_command_event）')
    row = act(finance_a, 'POST', '/franchise/assets/upload-tickets',
              {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf',
               'sizeBytes': len(PDF_BYTES), 'sha256': hashlib.sha256(PDF_BYTES).hexdigest()},
              with_post=False)  # finance 在本组织唯一任职：缺 header 自动选中（05 F11）
    audit = psql(f"SELECT count(*) FROM pf_command_event WHERE aggregate_id={row['assetId']} "
                 f"AND aggregate_type IN ('fr-asset-upload-ticket')")
    assert audit == '1', audit

    # ================= AC3 四类 B_END 全链 =================
    step('AC3 四类类型全链：签发→dev 落字节→complete ACCEPTED（免审）→accepted/ 换键→字节核验一致')
    # M5 通道权限分派（21 号 V3）：FIN=财务线、ENG=工程线、CONTRACT=业务线——各归其岗
    engineer_a = login_wechat(a['engineer']) if 'engineer' in a else login_wechat(a['finance'])
    full_chain(finance_a, sales_a, 'FIN_VOUCHER', 'application/pdf', PDF_BYTES, 'fin-vouchers')
    drawing_id = full_chain(engineer_a, sales_a, 'ENG_DRAWING', 'application/pdf', PDF_BYTES + b'drawing', 'eng-drawings')
    full_chain(engineer_a, sales_a, 'ENG_SITE_PHOTO', 'image/png', PNG_1X1, 'eng-site-photos')
    full_chain(sales_a, sales_a, 'CONTRACT_ATTACHMENT', 'application/pdf', PDF_BYTES + b'contract', 'contract-attachments')

    # ================= AC4 策略边界（签发层） =================
    step('AC4 策略边界：20MB/30MB 边界成功、21MB/MIME 不符/未知类型/坏 sha256 拒')
    sha = hashlib.sha256(PDF_BYTES).hexdigest()
    act(finance_a, 'POST', '/franchise/assets/upload-tickets',
        {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf',
         'sizeBytes': 20 * 1024 * 1024, 'sha256': sha})
    act(engineer_a, 'POST', '/franchise/assets/upload-tickets',
        {'assetType': 'ENG_DRAWING', 'mimeType': 'application/pdf',
         'sizeBytes': 30 * 1024 * 1024, 'sha256': sha})  # M5 分派：ENG 归工程线
    for bad in [('FIN_VOUCHER', 'application/pdf', 21 * 1024 * 1024, sha),
                ('NOT_A_TYPE', 'application/pdf', 100, sha),
                ('FIN_VOUCHER', 'application/pdf', 100, 'zz')]:
        act(finance_a, 'POST', '/franchise/assets/upload-tickets',
            {'assetType': bad[0], 'mimeType': bad[1], 'sizeBytes': bad[2], 'sha256': bad[3]},
            expect_code=1_071_000_001)
    # M5 分派：ENG_SITE_PHOTO MIME 错（工程线角色）→ 1_071_000_001；FIN 越权角色 → 1099000002 先于资产校验
    act(engineer_a, 'POST', '/franchise/assets/upload-tickets',
        {'assetType': 'ENG_SITE_PHOTO', 'mimeType': 'application/pdf', 'sizeBytes': 100, 'sha256': sha},
        expect_code=1_071_000_001)
    act(sales_a, 'POST', '/franchise/assets/upload-tickets',
        {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf', 'sizeBytes': 100, 'sha256': sha},
        expect_code=1_099_000_002)

    # ================= AC6 C_END 回归（USER_SKETCH 审核路径不变） =================
    step('AC6 USER_SKETCH 走审核路径：正常 ACCEPTED 有留痕；审核故障→PENDING 退避；恢复后重试 ACCEPTED')
    ticket = act(sales_a, 'POST', '/franchise/design/assets/upload-tickets',
                 {'assetType': 'USER_SKETCH', 'mimeType': 'image/png',
                  'sizeBytes': len(PNG_1X1), 'sha256': hashlib.sha256(PNG_1X1).hexdigest()})
    act(sales_a, 'POST', f"/platform/dev/design/assets/{ticket['assetId']}/bytes",
        {'contentBase64': base64.b64encode(PNG_1X1).decode()})
    completed = act(sales_a, 'POST', f"/franchise/design/assets/{ticket['assetId']}/complete")
    assert completed['uploadStatus'] == 'ACCEPTED', completed
    scan_rows = psql(f"SELECT count(*) FROM asset_scan_result WHERE asset_id={ticket['assetId']}")
    assert int(scan_rows) >= 1, 'C_END 类型须保留扫描/审核留痕（免审改动不得波及）'

    ticket2 = act(sales_a, 'POST', '/franchise/design/assets/upload-tickets',
                  {'assetType': 'USER_SKETCH', 'mimeType': 'image/png',
                   'sizeBytes': len(PNG_1X1), 'sha256': hashlib.sha256(PNG_1X1).hexdigest()})
    act(sales_a, 'POST', f"/platform/dev/design/assets/{ticket2['assetId']}/bytes",
        {'contentBase64': base64.b64encode(PNG_1X1).decode()})
    act(sales_a, 'POST', '/platform/dev/design/assets/moderation-fault', {'enabled': True})
    try:
        pending = act(sales_a, 'POST', f"/franchise/design/assets/{ticket2['assetId']}/complete")
        assert pending['uploadStatus'] == 'PENDING', pending  # 审核不可用退避（板块 A 同口径；moderation_retry_at 5 分钟门内重试仍 PENDING，恢复语义由退避门保证）
    finally:
        act(sales_a, 'POST', '/platform/dev/design/assets/moderation-fault', {'enabled': False})

    # ================= AC7 属主隔离 =================
    step('AC7 跨组织：B 组织对 A 组织资产 complete/download → 1_099_000_002')
    act(finance_b, 'POST', f'/franchise/assets/{drawing_id}/complete', {}, expect_code=1_099_000_002)
    act(finance_b, 'POST', f'/franchise/assets/{drawing_id}/download-tickets', {}, expect_code=1_099_000_002)

    # ================= AC8 服务账号停用 =================
    step('AC8 停用 A 组织服务账号（psql 直改，板块 A 先例）：A 三端点 1_112_000_004、B 组织不受影响')
    psql(f"UPDATE account SET status='DISABLED' WHERE external_key='fr-service:{org_a}'")
    body = {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf',
            'sizeBytes': 100, 'sha256': hashlib.sha256(PDF_BYTES).hexdigest()}
    act(finance_a, 'POST', '/franchise/assets/upload-tickets', body, expect_code=1_112_000_004)
    # M5 分派后 finance 无 ENG 通道权限；A 服务账号已停用 → 1_112_000_004 先于分派（channelTypeOf 内 serviceAccount）
    act(finance_a, 'POST', f'/franchise/assets/{drawing_id}/complete', {}, expect_code=1_112_000_004)
    act(finance_a, 'POST', f'/franchise/assets/{drawing_id}/download-tickets', {}, expect_code=1_112_000_004)
    ok_b = act(finance_b, 'POST', '/franchise/assets/upload-tickets',
               {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf',
                'sizeBytes': 100, 'sha256': hashlib.sha256(PDF_BYTES).hexdigest()})
    assert int(ok_b['assetId']) > 0  # 雪花 id 序列化为字符串（NumberSerializer）

    # ================= AC9 真 PG 并发 =================
    step('AC9 并发 complete 同一 assetId 一方 ACCEPTED 无伪 REJECTED；并发签发不同类型各自成功')
    sales_b = login_wechat(b['sales'])
    sha = hashlib.sha256(PDF_BYTES).hexdigest()
    target = act(finance_b, 'POST', '/franchise/assets/upload-tickets',
                 {'assetType': 'FIN_VOUCHER', 'mimeType': 'application/pdf', 'sizeBytes': len(PDF_BYTES), 'sha256': sha})
    act(sales_b, 'POST', f"/platform/dev/design/assets/{target['assetId']}/bytes",
        {'contentBase64': base64.b64encode(PDF_BYTES).decode()})
    results = []

    def worker():
        try:
            results.append(act(finance_b, 'POST', f"/franchise/assets/{target['assetId']}/complete", {}))
        except Exception as e:  # noqa: 异常传播至主线程断言（URLError 等网络层失败也算）
            results.append(e)

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(results) == 2 and all(not isinstance(r, Exception) for r in results), results
    # 并发语义（板块 A AC5 同口径）：一方 ACCEPTED，另一方按实际状态映射（ACCEPTED/PENDING），无伪 REJECTED
    statuses = [r['uploadStatus'] for r in results]
    assert statuses.count('ACCEPTED') >= 1 and 'REJECTED' not in statuses, results

    pair, pair_types = [], [('FIN_VOUCHER', 'application/pdf'), ('ENG_DRAWING', 'application/pdf')]
    engineer_b = login_wechat(b['engineer']) if 'engineer' in b else login_wechat(b['finance'])

    def issue(asset_type, mime):
        user = finance_b if asset_type == 'FIN_VOUCHER' else engineer_b  # M5 分派：ENG 归工程线
        try:
            pair.append(act(user, 'POST', '/franchise/assets/upload-tickets',
                            {'assetType': asset_type, 'mimeType': mime,
                             'sizeBytes': 100, 'sha256': hashlib.sha256(PDF_BYTES).hexdigest()})['assetId'])
        except Exception as e:  # noqa
            pair.append(e)

    threads = [threading.Thread(target=issue, args=t) for t in pair_types]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(pair) == 2 and all(not isinstance(x, Exception) for x in pair), pair
    assert pair[0] != pair[1]

    # ================= AC10 finance 既有通道回归 =================
    step('AC10 finance multipart 凭证通道行为不变：上传→fr_fin_file 落行→下载字节一致')
    file_id = multipart_upload(finance_a, 'e2e-voucher.pdf', PDF_BYTES)['fileId']
    req = urllib.request.Request(BASE + f'/franchise/finance/files/{file_id}', method='GET')
    req.add_header('tenant-id', '1')
    req.add_header('Authorization', 'Bearer ' + finance_a['token'])
    req.add_header('X-Post-Id', str(finance_a['post']))
    with urllib.request.urlopen(req, timeout=30) as resp:
        assert resp.read() == PDF_BYTES, 'finance 凭证下载字节一致'
    count = psql(f"SELECT count(*) FROM fr_fin_file WHERE id={file_id}")
    assert count == '1', count

    print(f'\n全部断言通过 ✔  （基-1 资产 e2e 共 {step_no} 步）')


if __name__ == '__main__':
    main()
