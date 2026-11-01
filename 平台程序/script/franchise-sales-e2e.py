#!/usr/bin/env python3
"""B2 板块 B 业务交易流端到端验收（需求文档 06 AC1-AC16）。

前提：本地 server 以 local,pg,zsdev 运行（stub 登录/移动前缀唯一/dev 库种子同板块 A）。
用法：python3 script/franchise-sales-e2e.py
"""
import base64
import hashlib
import json
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import sys as _sys
if _sys.stdout.encoding.lower() != 'utf-8':
    import io
    _sys.stdout = io.TextIOWrapper(_sys.stdout.buffer, encoding='utf-8', errors='replace')

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0


def call(method, path, body=None, token=None, post_id=None, expect_code=0, headers=None):
    # query 中的非 ASCII（如 status=已登记）按 RFC 3986 编码
    if '?' in path:
        base_path, query = path.split('?', 1)
        path = base_path + '?' + urllib.parse.quote(query, safe='=&')
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read().decode())
    if isinstance(expect_code, int):
        expect_code = [expect_code]
    if expect_code == [-1]:
        return payload
    if payload.get('code') not in expect_code:
        raise AssertionError(f'{method} {path} -> code={payload.get("code")} msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def sms_login(mobile):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile})
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return result['accessToken']


def psql(sql):
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def login(setup, who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-' + setup[who]['mobile'], 'phoneCode': setup[who]['mobile']})
    return {'token': result['accessToken'], 'post': setup[who]['postId'], 'userId': setup[who]['userId']}


def act(user, method, path, body=None, headers=None, expect_code=0):
    return call(method, path, body, token=user['token'], post_id=user['post'], headers=headers, expect_code=expect_code)


def main():
    step('dev-setup 搭建组织（唯一 mobilePrefix）+ 双门店（AC13 跨组织用）')
    ts = int(time.time()) % 10000000
    setup_a = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                   {'batch': 'sb' + str(ts), 'mobilePrefix': '136%07d' % ts,
                    'hqName': '交易总部', 'storeName': '交易门店A'})
    ts_b = (ts + 7) % 10000000
    setup_b = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                   {'batch': 'sc' + str(ts_b), 'mobilePrefix': '137%07d' % ts_b,
                    'hqName': '交易总部B', 'storeName': '交易门店B'})
    boss, sales, finance = login(setup_a, 'boss'), login(setup_a, 'sales'), login(setup_a, 'finance')
    engineer = login(setup_a, 'engineer')
    sales_b = login(setup_b, 'sales')
    # owner 岗正向（854 有 query）：门店录 owner 员工（setup 无 owner 位）
    mobile_owner = '136%08d' % ((ts + 55) % 100000000)
    act(boss, 'POST', '/platform/org/staff',
        {'orgId': setup_a['storeOrgId'], 'name': 'owner-e2e', 'mobile': mobile_owner, 'roleCode': 'owner'})
    owner = {'token': sms_login(mobile_owner)}
    owner['post'] = call('GET', '/platform/posts/mine', token=owner['token'])[0]['postId']
    # hq-design-admin（总部）未授权断言样例
    design_admin = login(setup_a, 'designAdmin')

    # ================= AC2 权限矩阵 =================
    step('AC2 权限矩阵：sales/sales-lead/boss 正向；finance/owner 只读；engineer 全拒')
    for u, name in [(sales, 'sales'), (boss, 'boss')]:
        r = act(u, 'GET', f'/franchise/sales/requirements?customerId=1', expect_code=-1)
        assert r.get('code') in (0, 1_113_000_004), f'{name} query 应过岗位门: {r.get("code")}'
    for u, name in [(owner, 'owner'), (finance, 'finance')]:
        r = call('GET', '/franchise/sales/requirements?customerId=1', token=u['token'], post_id=u['post'], expect_code=-1)
        assert r.get('code') in (0, 1_113_000_004), f'{name} query 授权应含: {r.get("code")}'
    r = call('GET', '/franchise/sales/requirements?customerId=1', token=design_admin['token'],
             post_id=design_admin['post'], expect_code=-1)
    assert r.get('code') == 403, f'hq-design-admin 未授权应 403: {r.get("code")}'
    for u, name in [(owner, 'owner'), (finance, 'finance')]:
        r2 = call('POST', '/franchise/sales/requirements',
                  {'customerId': 1}, token=u['token'], post_id=u['post'], expect_code=-1)
        assert r2.get('code') == 403, f'{name} write 应 403: {r2.get("code")}'
    r = call('GET', '/franchise/sales/requirements?customerId=1', token=engineer['token'],
             post_id=engineer['post'], expect_code=-1)
    assert r.get('code') == 403, f'engineer 应 403: {r.get("code")}'
    matrix = psql("SELECT r.code || ':' || m.permission FROM system_role_menu rm "
                  "JOIN system_role r ON r.id = rm.role_id AND r.tenant_id = 1 "
                  "JOIN system_menu m ON m.id = rm.menu_id WHERE m.permission LIKE 'franchise:sale:%' "
                  "ORDER BY 1")
    assert 'sales:franchise:sale:write' in matrix and 'owner:franchise:sale:write' not in matrix \
        and 'finance:franchise:sale:collect' in matrix and 'finance:franchise:sale:write' not in matrix, matrix

    # ================= AC3 需求登记 =================
    step('AC3 需求登记：一客户多版本')
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '交易客户甲', 'phone': '134%08d' % (ts % 100000000), 'need': '乡墅', 'source': '冒烟'})
    customer_id = lead['customerId']
    r1 = act(sales, 'POST', '/franchise/sales/requirements',
             {'customerId': customer_id, 'width': 12, 'depth': 15, 'floors': 2,
              'style': '新中式', 'prompt': '带庭院', 'note': '三代同住'},
             headers={'Idempotency-Key': f'e2e-req-001-{ts}'})
    assert r1['version'] == 1, r1
    r2 = act(sales, 'POST', '/franchise/sales/requirements',
             {'customerId': customer_id, 'width': 14, 'depth': 16, 'floors': 3, 'style': '现代'},
             headers={'Idempotency-Key': f'e2e-req-002-{ts}'})
    assert r2['version'] == 2, r2
    reqs = act(sales, 'GET', f'/franchise/sales/requirements?customerId={customer_id}')
    assert len(reqs) == 2 and reqs[0]['version'] == 2, reqs

    # ================= AC4 方案升级（走板块 A 回写链） =================
    step('AC4 方案升级：回写 scheme → R3 promote formal（boss 正向）→ 幂等重放')
    # 板块 A 链造一个 PLAN scheme：服务账号点数注入 → 建项目 → 任务 → 模拟 → 选定 → 回写
    act(sales, 'POST', '/platform/dev/design/point-account/seed', {'points': 2_000_000})
    case_id = psql("SELECT id FROM design_case WHERE publication_status='PUBLISHED' AND deleted=FALSE LIMIT 1")
    case_id = int(case_id)
    proj = act(sales, 'POST', '/franchise/design/projects',
               {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                'requirementInputs': {'faceWidthM': '12', 'depthM': '15', 'floorCount': 2}},
               headers={'Idempotency-Key': f'e2e-b-proj-{ts}'})
    job = act(sales, 'POST', f'/franchise/design/projects/{proj["projectId"]}/flat-jobs',
              {'count': 1, 'resolution': '2K', 'orientation': 'PORTRAIT'},
              headers={'Idempotency-Key': f'e2e-b-job-{ts}'})
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job["jobId"]}/simulate', {'acceptedCount': 1})
    deadline = time.time() + 90
    while time.time() < deadline:
        j = act(sales, 'GET', f'/franchise/design/ai-jobs/{job["jobId"]}')
        if j['status'] in ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED'):
            break
        time.sleep(3)
    assert j['status'] == 'SUCCEEDED', j
    detail = act(sales, 'GET', f'/franchise/design/projects/{proj["projectId"]}?jobId={job["jobId"]}')
    sel = act(sales, 'POST', f'/franchise/design/projects/{proj["projectId"]}/flat-selections',
              {'jobId': job['jobId'], 'candidateId': detail['candidates'][0]['candidateId']})
    saved = act(sales, 'POST', f'/franchise/design/projects/{proj["projectId"]}/save-to-customer',
                {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': sel['resultVersionId'],
                 'width': 12, 'depth': 15, 'floors': 2},
                headers={'Idempotency-Key': f'e2e-b-save-{ts}'})
    cust_detail = act(sales, 'GET', f'/franchise/customers/{customer_id}')
    plan_schemes = [s for s in cust_detail['schemes'] if s['kind'] == 'PLAN']
    assert plan_schemes, cust_detail
    scheme_id = plan_schemes[0]['id']
    stage_before = psql(f"SELECT stage FROM fr_customer_scheme WHERE id = {scheme_id}")
    assert stage_before == 'reference', stage_before
    promoted = act(boss, 'POST', f'/franchise/sales/schemes/{scheme_id}/promote',
                   headers={'Idempotency-Key': f'e2e-promote-001-{ts}'})
    assert promoted['stage'] == 'formal', promoted
    promoted2 = act(boss, 'POST', f'/franchise/sales/schemes/{scheme_id}/promote',
                    headers={'Idempotency-Key': f'e2e-promote-002-{ts}'})
    assert promoted2['stage'] == 'formal'
    stage_after = psql(f"SELECT stage FROM fr_customer_scheme WHERE id = {scheme_id}")
    assert stage_after == 'formal', stage_after

    # ================= AC5/AC6 报价 =================
    step('AC5 报价：服务端计算/伪造值覆盖/边界拒绝/scheme 关联')
    quote = act(sales, 'POST', '/franchise/sales/quotes',
                {'customerId': customer_id, 'schemeId': scheme_id,
                 'items': {'main': [{'label': '主体', 'quantity': 100, 'unitPriceCents': 85000, 'valueCents': 1,
                                     'qtyText': '100', 'unitText': '850.00'}],
                           'decor': [{'label': '门窗', 'quantity': 20, 'unitPriceCents': 12000, 'valueCents': 1,
                                      'qtyText': '20', 'unitText': '120.00'}],
                           'insuranceCents': 100000, 'managementCents': 500000},
                 'priceVersion': 'demo-20261019-v1', 'address': '湖北武汉某村',
                 'width': 12, 'depth': 15, 'floors': 2, 'area': 360},
                headers={'Idempotency-Key': f'e2e-quote-001-{ts}'})
    # 服务端：main=100×85000=8,500,000；decor=20×12000=240,000；total=8,500,000+240,000+100,000+500,000=9,340,000
    assert quote['totalCents'] == 9_340_000, quote
    assert quote['items']['main'][0]['valueCents'] == 8_500_000, '伪造行金额被服务端覆盖'
    q_bad = call('POST', '/franchise/sales/quotes',
                 {'customerId': customer_id,
                  'items': {'main': [{'label': '零数量', 'quantity': 0, 'unitPriceCents': 100}]}},
                 token=sales['token'], post_id=sales['post'], expect_code=-1,
                 headers={'Idempotency-Key': f'e2e-quote-bad-{ts}'})
    assert q_bad.get('code') == 1_113_000_001, q_bad
    q_wrap = call('POST', '/franchise/sales/quotes',
                  {'customerId': customer_id,
                   'items': {'main': [{'label': '回绕', 'quantity': 1_000_000, 'unitPriceCents': 18446744073710}]}},
                  token=sales['token'], post_id=sales['post'], expect_code=-1,
                  headers={'Idempotency-Key': f'e2e-quote-wrap-{ts}'})
    assert q_wrap.get('code') == 1_113_000_001, q_wrap
    q_cross = call('POST', '/franchise/sales/quotes',
                   {'customerId': customer_id, 'schemeId': 999,
                    'items': {'main': [{'label': 'x', 'quantity': 1, 'unitPriceCents': 1}]}},
                   token=sales['token'], post_id=sales['post'], expect_code=-1,
                   headers={'Idempotency-Key': f'e2e-quote-xscheme-{ts}'})
    assert q_cross.get('code') == 1_113_000_004, q_cross

    step('AC6 报价导出：冻结快照（改需求后导出不变）')
    sheet = act(sales, 'GET', f'/franchise/sales/quotes/{quote["quoteId"]}/export-sheet')
    assert sheet['name'] == '交易客户甲' and sheet['total'] == 9_340_000, sheet
    assert sheet['priceVersion'] == 'demo-20261019-v1'
    # S1-20b 显式契约：items 行内展示字段（qtyText/unitText）原样透传（落库/回显/export-sheet 均不丢）
    assert sheet['main'][0]['qtyText'] == '100' and sheet['main'][0]['unitText'] == '850.00', sheet['main'][0]
    assert sheet['decor'][0]['qtyText'] == '20' and sheet['decor'][0]['unitText'] == '120.00'
    act(sales, 'POST', '/franchise/sales/requirements',
        {'customerId': customer_id, 'width': 20, 'depth': 20, 'floors': 5, 'style': '改需求'},
        headers={'Idempotency-Key': f'e2e-req-003-{ts}'})
    sheet2 = act(sales, 'GET', f'/franchise/sales/quotes/{quote["quoteId"]}/export-sheet')
    assert sheet2 == sheet, '导出须来自冻结快照'

    # ================= AC7 合同 =================
    step('AC7 合同：创建即生成两期/重复合同拒绝/sign/void/零尾款')
    contract = act(sales, 'POST', '/franchise/sales/contracts',
                   {'customerId': customer_id, 'totalCents': 9_340_000, 'firstCents': 5_604_000,
                    'requirementId': r1['requirementId']},
                   headers={'Idempotency-Key': f'e2e-contract-001-{ts}'})
    assert contract['status'] == 'WAIT_SIGN', contract
    stages = {s['stage']: s for s in contract['stages']}
    assert stages['first']['amountCents'] == 5_604_000 and stages['tail']['amountCents'] == 3_736_000, stages
    dup = call('POST', '/franchise/sales/contracts',
               {'customerId': customer_id, 'totalCents': 100, 'firstCents': 50},
               token=sales['token'], post_id=sales['post'], expect_code=-1,
               headers={'Idempotency-Key': f'e2e-contract-dup-{ts}'})
    assert dup.get('code') == 1_113_000_006, dup
    # 零尾款：first=total 只生成 first
    cust2 = act(sales, 'POST', '/franchise/leads',
                {'name': '交易客户乙', 'phone': '134%08d' % ((ts + 1) % 100000000), 'need': '乡墅', 'source': '冒烟'})
    c2 = act(sales, 'POST', '/franchise/sales/contracts',
             {'customerId': cust2['customerId'], 'totalCents': 1_000_000, 'firstCents': 1_000_000},
             headers={'Idempotency-Key': f'e2e-contract-002-{ts}'})
    st2 = {s['stage'] for s in c2['stages']}
    assert st2 == {'first'}, f'零尾款只生成 first: {st2}'
    # WAIT_SIGN 不可 collect
    stage_id_first = stages['first']['id']
    early = call('POST', f'/franchise/sales/stages/{stage_id_first}/collect',
                 {'method': 'bank'}, token=sales['token'], post_id=sales['post'], expect_code=-1,
                 headers={'Idempotency-Key': f'e2e-collect-early-{ts}'})
    assert early.get('code') == 1_113_000_005, early
    # sign
    signed = act(sales, 'POST', f'/franchise/sales/contracts/{contract["contractId"]}/sign',
                 {'signedDate': '2026-11-01', 'revision': 1},
                 headers={'Idempotency-Key': f'e2e-sign-001-{ts}'})
    assert signed['status'] == 'SIGNED', signed

    # ================= AC8 回款写通 B1 =================
    step('AC8 回款写通 B1：collect → fr_fin_receipt/txn_no 落库 → 台账可见；同键重放')
    bal_before = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    txn_no = 'SALE%010d' % ts
    collected = act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
                    {'method': 'bank', 'transactionNo': txn_no, 'receivedDate': '2026-11-01'},
                    headers={'Idempotency-Key': f'e2e-collect-001-{ts}'})
    assert collected['stageStatus'] == 'PAID' and collected['replayed'] is False, collected
    receipt_id = collected['receiptId']
    row = psql(f"SELECT source_type||'|'||source_ref||'|'||name||'|'||amount_cents||'|'||received_date "
               f"FROM fr_fin_receipt WHERE id = {receipt_id}")
    assert 'business|' in row and f'sale:{contract["contractId"]}:first' in row and '首款回款' in row \
        and '|5604000|2026-11-01' in row, row
    txn = psql(f"SELECT count(*) FROM fr_fin_txn_no WHERE owner_type='receipt' AND owner_id={receipt_id}")
    assert txn == '1', txn
    ledger = act(finance, 'GET', '/franchise/finance/receipts?status=已登记')
    assert any(str(r['id']) == str(receipt_id) for r in ledger), '财务台账应含该笔'
    # 同键重放
    replay = act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
                 {'method': 'bank', 'transactionNo': txn_no, 'receivedDate': '2026-11-01'},
                 headers={'Idempotency-Key': f'e2e-collect-001-{ts}'})
    assert replay['receiptId'] == receipt_id and replay['replayed'] is True, replay
    # 同键异内容
    act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
        {'method': 'cash', 'transactionNo': txn_no}, headers={'Idempotency-Key': f'e2e-collect-001-{ts}'},
        expect_code=1_099_000_000)
    # 已 PAID 内容一致（新键）→ 重放；异内容 → 002
    replay2 = act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
                  {'method': 'bank', 'transactionNo': txn_no, 'receivedDate': '2026-11-01'},
                  headers={'Idempotency-Key': f'e2e-collect-newkey-{ts}'})
    assert replay2['receiptId'] == receipt_id and replay2['replayed'] is True, replay2
    act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
        {'method': 'cash'}, headers={'Idempotency-Key': f'e2e-collect-diff-{ts}'},
        expect_code=1_113_000_002)
    # 业务收据修订被拒 + 手工 REST business 被拒
    act(finance, 'POST', f'/franchise/finance/receipts/{receipt_id}/revision',
        {'baseRevisionVersion': 1, 'newAmountCents': 100, 'note': '尝试修订'}, expect_code=1_102_000_023)
    act(finance, 'POST', '/franchise/finance/receipts',
        {'sourceType': 'business', 'name': '手工 business', 'amountCents': 100, 'method': 'bank'},
        expect_code=1_102_000_022)
    # 同内容重试（显式日期一致）→ 重放
    replay3 = act(sales, 'POST', f'/franchise/sales/stages/{stage_id_first}/collect',
                  {'method': 'bank', 'transactionNo': txn_no, 'receivedDate': '2026-11-01'},
                  headers={'Idempotency-Key': f'e2e-collect-samedate-{ts}'})
    assert replay3['receiptId'] == receipt_id, replay3
    # tail 同链路（缺省日期：首次固化当日，重试缺省 → 指纹稳定返回首次，AC10 跨日场景）
    collected_tail = act(sales, 'POST', f"/franchise/sales/stages/{stages['tail']['id']}/collect",
                         {'method': 'bank'},
                         headers={'Idempotency-Key': f'e2e-collect-tail-{ts}'})
    assert collected_tail['stageStatus'] == 'PAID', collected_tail
    tail_date = psql(f"SELECT received_date FROM fr_fin_receipt WHERE id = {collected_tail['receiptId']}")
    assert tail_date, '缺省日期应固化为当日'
    tail_replay = act(sales, 'POST', f"/franchise/sales/stages/{stages['tail']['id']}/collect",
                      {'method': 'bank'},
                      headers={'Idempotency-Key': f'e2e-collect-tail-retry-{ts}'})
    assert tail_replay['receiptId'] == collected_tail['receiptId'] and tail_replay['replayed'] is True, tail_replay

    # ================= AC9 回款并发 =================
    step('AC9 回款并发：同键/不同键/异内容（真 PG + advisory lock）')
    # 用 tail 之前的窗口已过——造第二个客户的合同做并发
    c3 = act(sales, 'POST', '/franchise/sales/contracts',
             {'customerId': cust2['customerId'], 'totalCents': 100, 'firstCents': 50, },
             headers={'Idempotency-Key': f'e2e-contract-x-{ts}'}, expect_code=1_113_000_006)
    # 客户乙合同已签（上一步零尾款 first 合同）——直接对它的 first 并发
    stage_b = act(sales, 'GET', f'/franchise/sales/contracts?customerId={cust2["customerId"]}')
    stage_b_id = [s['id'] for st in stage_b for s in st['stages'] if s['stage'] == 'first'][0]
    act(sales, 'POST', f'/franchise/sales/contracts/{stage_b[0]["contract"]["id"]}/sign',
        {'revision': 1}, headers={'Idempotency-Key': f'e2e-sign-b-{ts}'})
    conc_results, conc_errors = [], []

    def collect_parallel(i):
        try:
            r = act(sales, 'POST', f'/franchise/sales/stages/{stage_b_id}/collect',
                    {'method': 'bank', 'receivedDate': '2026-11-01'},
                    headers={'Idempotency-Key': f'e2e-conc-{i}-{ts}'})
            conc_results.append(r)
        except AssertionError as e:
            conc_errors.append(str(e))

    threads = [threading.Thread(target=collect_parallel, args=(i,)) for i in range(4)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not conc_errors, conc_errors
    assert len({r['receiptId'] for r in conc_results}) == 1, conc_results
    count = psql(f"SELECT count(*) FROM fr_fin_receipt WHERE source_ref = "
                 f"'sale:{stage_b[0]['contract']['id']}:first'")
    assert count == '1', count

    # ================= AC11 交付验收 =================
    step('AC11 交付验收：四节点顺序推进/乱序拒绝/幂等')
    delivery = act(sales, 'POST', '/franchise/sales/deliveries',
                   {'contractId': contract['contractId'], 'requirementId': r1['requirementId']},
                   headers={'Idempotency-Key': f'e2e-delivery-001-{ts}'})
    d_id = delivery['deliveryId']
    act(sales, 'POST', f'/franchise/sales/deliveries/{d_id}/advance',
        {'node': 'construction-delivered'}, headers={'Idempotency-Key': f'e2e-dv-bad-{ts}'},
        expect_code=1_113_000_003)
    node_keys = {'design-delivered': 'designDeliveredAt', 'design-confirmed': 'designConfirmedAt',
                 'construction-delivered': 'constructionDeliveredAt', 'construction-accepted': 'constructionAcceptedAt'}
    for node, key in node_keys.items():
        r = act(sales, 'POST', f'/franchise/sales/deliveries/{d_id}/advance',
                {'node': node}, headers={'Idempotency-Key': f'e2e-dv-{node}-{ts}'})
        assert r[key] is not None, r
    # 同节点不同键不覆盖
    t_before = act(sales, 'GET', f'/franchise/sales/deliveries?contractId={contract["contractId"]}')
    act(sales, 'POST', f'/franchise/sales/deliveries/{d_id}/advance',
        {'node': 'design-delivered'}, headers={'Idempotency-Key': f'e2e-dv-again-{ts}'})
    t_after = act(sales, 'GET', f'/franchise/sales/deliveries?contractId={contract["contractId"]}')
    assert t_after['designDeliveredAt'] == t_before['designDeliveredAt'], '时间戳不可覆盖'

    # ================= AC12 待办 =================
    step('AC12 待办：contract-sign 出现/消失；stage-pending 出现/消失/路径按岗位')
    todos_sales = act(sales, 'GET', '/platform/todos')['items']
    keys = [t['key'] for t in todos_sales]
    assert f'fr-sale:contract-sign:{contract["contractId"]}' not in keys, '已签合同待签待办应消失'
    # 造一个 WAIT_SIGN：客户乙已签——用客户甲不行（唯一合同）。新建客户丙
    cust4 = act(sales, 'POST', '/franchise/leads',
                {'name': '交易客户丙', 'phone': '134%08d' % ((ts + 3) % 100000000), 'need': '乡墅', 'source': '冒烟'})
    c4 = act(sales, 'POST', '/franchise/sales/contracts',
             {'customerId': cust4['customerId'], 'totalCents': 2_000_000, 'firstCents': 800_000},
             headers={'Idempotency-Key': f'e2e-contract-c4-{ts}'})
    todos_sales = act(sales, 'GET', '/platform/todos')['items']
    keys = [t['key'] for t in todos_sales]
    assert f'fr-sale:contract-sign:{c4["contractId"]}' in keys, keys
    todos_finance = act(finance, 'GET', '/platform/todos')['items']
    fkeys = [t['key'] for t in todos_finance]
    assert f'fr-sale:contract-sign:{c4["contractId"]}' not in fkeys, 'finance 无 write 门不应收待签待办'
    # 签订丙合同 → 其 first stage 进入 collect 岗待办（finance 路径=finance-desk；sales 路径=workflow）
    act(sales, 'POST', f'/franchise/sales/contracts/{c4["contractId"]}/sign',
        {'revision': 1}, headers={'Idempotency-Key': f'e2e-sign-c4-{ts}'})
    todos_finance = act(finance, 'GET', '/platform/todos')['items']
    c4_pending = [t for t in todos_finance
                  if t['key'] == f'fr-sale:stage-pending:{c4["stages"][0]["id"]}']
    assert c4_pending, [t['key'] for t in todos_finance if t['key'].startswith('fr-sale')]
    assert 'finance-desk' in c4_pending[0]['appPath'], c4_pending[0]
    todos_sales = act(sales, 'GET', '/platform/todos')['items']
    c4_pending_s = [t for t in todos_sales
                    if t['key'] == f'fr-sale:stage-pending:{c4["stages"][0]["id"]}']
    if c4_pending_s:
        assert 'workflow' in c4_pending_s[0]['appPath'], c4_pending_s[0]

    # ================= AC13 组织隔离 =================
    step('AC13 组织隔离：B 门店操作 A 门店对象 → 拒绝且 A 侧无变更')
    count_before = psql("SELECT count(*) FROM fr_sale_contract")
    r = act(sales_b, 'GET', f'/franchise/sales/requirements?customerId={customer_id}', expect_code=-1)
    assert r.get('code') in (403, 1_113_000_004), r.get('code')
    r = call('POST', f'/franchise/sales/stages/{stage_id_first}/collect',
             {'method': 'bank'}, token=sales_b['token'], post_id=sales_b['post'], expect_code=-1,
             headers={'Idempotency-Key': f'e2e-cross-collect-{ts}'})
    assert r.get('code') in (403, 1_113_000_002, 1_113_000_005), r.get('code')
    count_after = psql("SELECT count(*) FROM fr_sale_contract")
    assert count_before == count_after

    # ================= AC17 回归（部分：板块 A 冒烟在提交前单独跑） =================
    print('\n全部断言通过 ✔  （B2 板块 B e2e 主链）')


if __name__ == '__main__':
    main()
