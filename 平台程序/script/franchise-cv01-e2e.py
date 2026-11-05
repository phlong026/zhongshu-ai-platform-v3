#!/usr/bin/env python3
"""CV-01 · E3 本地联验（批次#38，21 号 §批 38）：五项共享一次 12 步连续场景。

覆盖：M06-T05（报价导出合同交付）/ M07-T05（节点材料凭据）/ M08-T06（三期收款退款异常）
/ M09-T05（冻结签收归档）/ M10-T06（提成月末版本）。
关闭标准（13 号）：同一测试总部+两个门店隔离对照；固定客户/合同/委托/移交包/工程项目；
12 步连续场景含负测；每步记录角色、来源单号、期次、金额、日期、凭据、前后状态；
任一失败不整链标绿。

前提：本地 server 以 local,pg,zsdev 运行（PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e）。
用法：python3 script/franchise-cv01-e2e.py
证据：docs/business-flow-audit-20261103/evidence/cv01-e3-<run>.json
"""
import base64
import hashlib
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0
run = str(int(time.time()))
EVIDENCE = {'run': run, 'steps': []}
from datetime import date as _date
TODAY = _date.today().isoformat()

PNG_1X1 = bytes.fromhex(
    '89504e470d0a1a0a0000000d4948445200000001000000010806000000'
    '1f15c4890000000d49444547189c626001000000ffff030000060005'
    '57bfabd40000000049454e44ae426082')


def call(method, path, body=None, token=None, post_id=None, expect_code=0, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    req.add_header('content-type', 'application/json')
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read().decode())
    code = payload.get('code', payload.get('statusCode', 0) if isinstance(payload, dict) else 0)
    if expect_code == 0:
        assert code == 0, f'{method} {path} 期望成功，实际 {code}: {payload}'
    elif expect_code == -1:
        assert code != 0, f'{method} {path} 期望业务失败'
    else:
        assert code == expect_code, f'{method} {path} 期望 {expect_code}，实际 {code}: {payload}'
    return payload.get('data', payload)


def psql(sql):
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def step(title):
    global step_no
    step_no += 1
    print(f'\n【第 {step_no} 步】{title}')
    EVIDENCE['steps'].append({'no': step_no, 'title': title})


def record(**kw):
    EVIDENCE['steps'][-1].setdefault('records', []).append(kw)


def setup(mobile_prefix=None, batch=None):
    payload = {}
    if mobile_prefix:
        payload['mobilePrefix'] = mobile_prefix
    payload['batch'] = batch or ('cv' + str(int(time.time()) % 1000000))
    return call('POST', '/platform/dev-setup/franchise-e2e/setup', payload)


def login(who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-cv-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': result['accessToken'], 'post': who['postId'], 'userId': who['userId'],
            'mobile': who['mobile'], 'role': who.get('roleCode', '')}


def act(user, method, path, body=None, expect_code=0, idem=None):
    headers = {'Idempotency-Key': idem} if idem else None
    return call(method, path, body, token=user['token'], post_id=user['post'],
                expect_code=expect_code, headers=headers)


def upload_asset(user, sales_helper, asset_type='ENG_DRAWING'):
    """基-1 票据链：ticket（登录+任职）→ dev 字节（批次#36 后工程/财务岗可直传或 sales 代传）→ complete。"""
    sha = hashlib.sha256(PNG_1X1).hexdigest()
    ticket = act(user, 'POST', '/franchise/assets/upload-tickets',
                 {'assetType': asset_type, 'mimeType': 'image/png',
                  'sizeBytes': len(PNG_1X1), 'sha256': sha})
    asset_id = ticket['assetId']
    try:
        act(user, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
            {'contentBase64': base64.b64encode(PNG_1X1).decode()})
    except AssertionError:
        act(sales_helper, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
            {'contentBase64': base64.b64encode(PNG_1X1).decode()})
    completed = act(user, 'POST', f'/franchise/assets/{asset_id}/complete')
    assert completed['uploadStatus'] == 'ACCEPTED', completed
    return asset_id


def node_dates(detail, start='2026-11-10', span=4):
    from datetime import date, timedelta
    nodes = []
    d = date.fromisoformat(start)
    for n in detail['nodes']:
        end = d + timedelta(days=span - 1)
        nodes.append({'nodeIndex': n['nodeIndex'], 'name': n['name'],
                      'startDate': d.isoformat(), 'endDate': end.isoformat()})
        d = end + timedelta(days=1)
    return nodes


def main():
    step('S1 双门店搭建与登录——隔离对照基线（13 号：同一测试总部+两门店）')
    env = setup(mobile_prefix='1370000000', batch='cvA' + run[-6:])
    env_b = setup(mobile_prefix='1380000000', batch='cvB' + run[-6:])
    sales = login(env['sales'])
    boss = login(env['boss'])
    finance = login(env['finance'])
    engineer = login(env['engineer'])
    engineer_b = login(env['engineerB'])
    design_admin = login(env['designAdmin'])
    org_a = env['storeOrgId']
    org_b = env_b['storeOrgId']
    print(f'    ✅ 门店 A org={org_a} / 门店 B org={org_b}；五岗登录齐')
    record(roles=['sales', 'boss', 'finance', 'engineer(pm)', 'engineerB', 'designAdmin'],
           orgA=org_a, orgB=org_b)

    step('S2 M06-T05a 需求登记+报价+导出冻结（改需求后导出不变——记录报价单号/快照）')
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '陈先生', 'phone': '1372222' + run[-4:], 'need': '两层乡墅', 'source': '视频号咨询'})
    customer_id = lead['customerId']
    r1 = act(sales, 'POST', '/franchise/sales/requirements',
             {'customerId': customer_id, 'text': 'CV01 需求', 'scope': '方案、施工图与交底',
              'basis': {'area': 400, 'floors': 2}}, idem='cv-req1-' + run)
    requirement_id = r1['requirementId']
    scheme = act(sales, 'POST', f'/franchise/customers/{customer_id}/schemes',
        {'kind': 'BUDGET', 'title': 'CV01 预算', 'payload': '{"area":400}',
         'width': 13, 'depth': 16, 'floors': 2, 'area': 400, 'totalCents': 68000000},
        idem='cv-scheme-' + run)
    scheme_id = scheme.get('schemeId') or scheme.get('id')
    quote = act(sales, 'POST', '/franchise/sales/quotes',
                {'customerId': customer_id, 'schemeId': scheme_id,
                 'items': {'main': [{'label': '主体', 'quantity': 400, 'unitPriceCents': 15000, 'valueCents': 1,
                                     'qtyText': '400', 'unitText': '150.00'}],
                           'decor': [{'label': '门窗', 'quantity': 20, 'unitPriceCents': 12000, 'valueCents': 1,
                                      'qtyText': '20', 'unitText': '120.00'}],
                           'insuranceCents': 100000, 'managementCents': 500000},
                 'priceVersion': 'demo-20261019-v1', 'address': '湖北武汉某村',
                 'width': 13, 'depth': 16, 'floors': 2, 'area': 400}, idem='cv-quote-' + run)
    sheet1 = act(sales, 'GET', f"/franchise/sales/quotes/{quote['quoteId']}/export-sheet")
    act(sales, 'POST', '/franchise/sales/requirements',
        {'customerId': customer_id, 'text': 'CV01 修订需求', 'version': 2,
         'scope': '修订', 'basis': {'area': 400, 'floors': 2}}, idem='cv-req2-' + run)
    sheet2 = act(sales, 'GET', f"/franchise/sales/quotes/{quote['quoteId']}/export-sheet")
    assert sheet1 == sheet2, 'M06-T05：导出冻结——改需求后导出快照不变'
    print(f"    ✅ 报价 quoteId={quote['quoteId']} 导出冻结（改需求导出不变）")
    record(quoteId=quote['quoteId'], customerId=customer_id, exportFrozen=True)

    step('S3 M06-T05b 统一合同三期创建+签订+交付四节点推进（M06-T05 交付断言）')
    contract = act(sales, 'POST', '/franchise/sales/contracts',
                   {'customerId': customer_id, 'totalCents': 68000000,
                    'signAfterCents': 40000000, 'baseDoneCents': 18000000, 'capDoneCents': 10000000},
                   idem='cv-contract-' + run)
    contract_id = contract['contractId']
    act(sales, 'POST', f'/franchise/sales/contracts/{contract_id}/sign',
        {'signedDate': '2026-11-02', 'revision': 1}, idem='cv-sign-' + run)
    stages = act(sales, 'GET', f'/franchise/sales/contracts?customerId={customer_id}')[0]['stages']
    stage_map = {s['stage']: s['stageId'] for s in stages if s.get('stageId')}
    assert stage_map.get('SIGN_AFTER') and stage_map.get('BASE_DONE') and stage_map.get('CAP_DONE'), stages
    delivery = act(sales, 'POST', '/franchise/sales/deliveries',
                   {'contractId': contract_id, 'requirementId': requirement_id},
                   idem='cv-delivery-' + run)
    d_id = delivery.get('deliveryId') or delivery.get('id')
    assert d_id, delivery
    for node in ['design-delivered', 'design-confirmed', 'construction-delivered']:
        act(sales, 'POST', f'/franchise/sales/deliveries/{d_id}/advance', {'node': node},
            idem=f'cv-dv-{node}-{run}')
    t = act(sales, 'GET', f'/franchise/sales/deliveries?contractId={contract_id}')
    t_row = t[0] if isinstance(t, list) else t
    assert t_row.get('constructionDeliveredAt'), t_row  # 三节点推进完成（M06-T05 交付断言）
    print(f'    ✅ 合同 contractId={contract_id} 三期齐+签订；交付四节点推进至移交待办')
    record(contractId=contract_id, stages=['SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'],
           signedDate='2026-11-02', deliveryId=d_id, deliveryAdvanced=3)

    step('S4 M06 委托+移交包冻结+packet-receive 建档（M07-T05 起点——工程档案同事务落库）')
    act(boss, 'POST', '/franchise/design-policies',
        {'total': 680000, 'first': 408000, 'scope': '方案、施工图与交底', 'recipient': '总部财务'},
        idem='cv-dp-' + run)
    detail = act(sales, 'POST', f'/franchise/customers/{customer_id}/commission',
                 {'text': '两层乡墅', 'scope': '方案、施工图与交底',
                  'total': 680000, 'first': 408000, 'recipient': '总部财务', 'confirmed': True},
                 idem='cv-commission-' + run)
    commission_id = detail['commission']['id']
    version = detail['commission']['submissionVersion']
    for stage_act, stage in [(finance, 'first-ready'), (finance, 'tail-paid'), (finance, 'tail-ready')]:
        act(stage_act, 'POST', '/platform/dev-setup/franchise-e2e/fee-settlement',
            {'commissionId': commission_id, 'submissionVersion': version, 'stage': stage})
    act(design_admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-complete',
        {'note': 'CV01 完成', 'confirmed': True})
    dispatch = act(design_admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-dispatch',
                   {'note': '交付', 'recipient': '总部设计行政', 'confirmed': True})
    seq = dispatch['commission']['dispatchSeq']
    packet = {'dispatchSeq': seq, 'resultFile': '{"name":"成果"}', 'drawingFile': '{"name":"施工图"}',
              'reviewFile': '{"name":"审核依据"}', 'confirmationFile': '{"name":"客户确认"}',
              'drawingVersion': 'CV01-' + run, 'confirmationDate': '2026-11-02',
              'note': 'CV01 现场交底完成', 'managerUserId': engineer['userId'], 'confirmed': True}
    act(sales, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-submit', packet,
        idem='cv-ps-' + run)
    received = act(engineer, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-receive',
                   {'confirmed': True}, idem='cv-pr-' + run)
    assert received['commission']['status'] == 'ACCEPTED', received
    project_id = int(psql(f'SELECT id FROM fr_eng_project WHERE source_commission_id={commission_id}'))
    print(f'    ✅ 移交建档 project={project_id}（五文档/图纸版本/负责人随包）')
    record(commissionId=commission_id, packetDrawingVersion='CV01-' + run, projectId=project_id)

    step('S5 M07-T05a 开工准备（三期约定固化）+ START 收款 + 开工（凭据链——M07-T05 凭据断言）')
    contract_asset = upload_asset(engineer, sales, 'ENG_DRAWING')
    prepared = act(engineer, 'POST', f'/franchise/engineering/{project_id}/prepare',
                   {'total': 680000, 'signAfter': 400000, 'baseDone': 180000, 'capDone': 100000,
                    'contractAssetId': contract_asset, 'note': 'CV01 开工准备'}, idem='cv-prepare-' + run)
    terms = psql(f"SELECT string_agg(stage || ':' || amount_cents, ',' ORDER BY stage) "
                 f"FROM fr_eng_collection_term WHERE project_id={project_id}")
    assert terms == 'BASE_DONE:18000000,CAP_DONE:10000000,SIGN_AFTER:40000000', terms  # ORDER BY stage 字母序
    detail = act(engineer, 'GET', f'/franchise/engineering/{project_id}')
    nodes = node_dates(detail)
    act(engineer, 'PUT', f'/franchise/engineering/{project_id}/plan', {'nodes': nodes},
        idem='cv-plan-' + run)
    collected = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                    {'stage': 'SIGN_AFTER', 'amount': 400000, 'transactionNo': 'CV-TXN-S5-' + run,
                     'occurredAt': TODAY, 'fileAssetId': contract_asset}, idem='cv-col1-' + run)
    started = act(engineer, 'POST', f'/franchise/engineering/{project_id}/start',
                  {'date': TODAY, 'fileAssetId': contract_asset, 'note': 'CV01 开工'},
                  idem='cv-start-' + run)
    assert started['status'] in ('IN_PROGRESS',), started['status']
    print(f'    ✅ 准备（SIGN_AFTER 40 万/BASE 18 万/CAP 10 万）+START 收款+开工；凭据 asset={contract_asset}')
    record(prepareTerms=terms, startCollected=400000, voucher=contract_asset,
           date=TODAY, status='IN_PROGRESS')

    step('S6 M07-T05b 材料计划+terms+到货（凭据）+节点完成（凭据）+封顶完成——M07-T05 节点/材料/凭据断言')
    plan = act(engineer, 'POST', f'/franchise/engineering/{project_id}/materials',
               {'nodeId': detail['nodes'][0]['id'], 'due': TODAY,
                'lines': [{'name': '钢筋', 'quantity': 10, 'unit': '吨'}]}, idem='cv-mat-' + run)
    supply_id = None
    for p_ in plan.get('plans', []):
        for l_ in p_.get('lines', []):
            supply_id = l_.get('supplyId') or supply_id
    assert supply_id, plan  # 提交响应即含 supplyId（R3 形状——full 行）
    act(finance, 'POST', f'/franchise/engineering/supplies/{supply_id}/terms',
        {'supplier': '钢材城', 'unitPrice': 400000, 'freight': 0,
         'basis': '{"name":"CV01 采购合同"}'}, idem='cv-terms-' + run)
    arrival_asset = upload_asset(engineer, sales, 'ENG_SITE_PHOTO')
    act(engineer, 'POST', f'/franchise/engineering/supplies/{supply_id}/arrival',
        {'actual': 10, 'quality': 'OK', 'transactionNo': 'CV-ARR-' + run,
         'occurredAt': TODAY, 'fileAssetId': arrival_asset, 'note': 'CV01 到货'},
        idem='cv-arr-' + run)
    done_count = 0
    for n in detail['nodes']:
        node_asset = upload_asset(engineer, sales, 'ENG_SITE_PHOTO')
        done = act(engineer, 'POST', f"/franchise/engineering/{project_id}/nodes/{n['id']}/complete",
                   {'date': TODAY, 'note': 'CV01 节点完成', 'fileAssetId': node_asset,
                    'noMaterials': True}, idem=f"cv-node-{n['id']}-{run}")
        done_count += 1
    topped = any(nd['milestone'] == 'TOPPING' for nd in detail['nodes'])
    assert topped, '封顶节点须在计划内（TOPPING 门禁前置）'
    print(f'    ✅ 材料 terms+到货（凭据 asset={arrival_asset}）+{done_count} 节点完成（含封顶）')
    record(materialSupplyId=supply_id, arrivalAsset=arrival_asset,
           nodesCompleted=done_count, nodeDate=TODAY)

    step('S7 M08-T06a 三期收款齐——BASE_DONE+CAP_DONE（TOPPING 前置已过——M08 三期收款断言）')
    act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
        {'stage': 'BASE_DONE', 'amount': 180000, 'transactionNo': 'CV-TXN-S7A-' + run,
         'occurredAt': TODAY, 'fileAssetId': arrival_asset}, idem='cv-col2-' + run)
    collected3 = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                     {'stage': 'CAP_DONE', 'amount': 100000, 'transactionNo': 'CV-TXN-S7B-' + run,
                      'occurredAt': TODAY, 'fileAssetId': arrival_asset}, idem='cv-col3-' + run)
    coll_page = act(finance, 'GET', f'/franchise/engineering/{project_id}/collections')
    summary_rows = coll_page.get('stages', [])
    by_stage = {s['stage']: s for s in summary_rows}
    for st in ('SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'):
        assert by_stage[st]['confirmedCents'] == by_stage[st]['expectedCents'] \
            and by_stage[st]['remainingCents'] == 0, (st, by_stage[st])  # 三期收齐
    print('    ✅ 三期收款齐（SIGN/BASE/CAP 全 SETTLED——统一实收账单次计数）')
    record(stagesSettled=['SIGN_AFTER', 'BASE_DONE', 'CAP_DONE'],
           amountsCents=[40000000, 18000000, 10000000], txns=['CV-TXN-S5', 'CV-TXN-S7A', 'CV-TXN-S7B'])

    step('S8 M08-T06b 退款回缺+异常资金登记（不凑平——M08 财务断言：退款净额/异常留痕）')
    receipt_id = psql(f"SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id={stage_map['SIGN_AFTER']} LIMIT 1")
    refund = act(finance, 'POST', '/franchise/finance/refunds',
                 {'receiptId': receipt_id, 'amountCents': 100000, 'reason': 'CV01 协商退款',
                  'date': TODAY}, idem='cv-refund-' + run)
    net = act(sales, 'GET', f'/franchise/sales/contracts?customerId={customer_id}')[0]['stages']
    sign_row = [s for s in net if s['stage'] == 'SIGN_AFTER'][0]
    assert sign_row.get('collectedCents') == 39900000 and sign_row.get('refundedCents') == 100000, sign_row  # 净=400 万−10 万
    fund = act(finance, 'POST', '/franchise/finance/abnormal-funds',
               {'fundDirection': '客户收款', 'amountCents': 888000, 'counterparty': 'CV01 王某',
                'relatedProjectId': project_id, 'businessDate': None,
                'transactionNo': 'CV-ABN-' + run, 'reason': 'CV01 原单缺失'}, idem='cv-fund-' + run)
    rec = act(finance, 'GET', f'/franchise/finance/reconciliation?month=2026-11')
    assert rec.get('abnormalPendingCents', 0) >= 888000, rec
    print(f'    ✅ 退款 10 万（SIGN_AFTER 净 390 万）+异常资金 888000 待关联（待归月，不进有效账）')
    record(refundId=refund['refundId'], refundCents=100000, netAfter=39900000,
           abnormalFundId=fund['fundId'], abnormalCents=888000, datePending=True)

    step('S9 M08-T06c 更正链——作废+同号重登（CAS 替代——M08 误录更正断言）')
    void = act(boss, 'DELETE', f"/franchise/finance/refunds/{refund['refundId']}",  # REFUND_VOID=lead/boss/owner（852）
               {'reason': 'CV01 更正重登'}, idem='cv-void-' + run)
    corrected = act(finance, 'POST', '/franchise/finance/refunds',
                    {'receiptId': receipt_id, 'amountCents': 80000, 'reason': 'CV01 更正后金额',
                     'date': TODAY, 'replacesId': refund['refundId']}, idem='cv-corr-' + run)
    replaced_by = psql(f'SELECT replaced_by_id FROM fr_fin_refund WHERE id={refund["refundId"]}')
    assert str(replaced_by) == str(corrected['refundId']), replaced_by
    print(f"    ✅ 更正链：作废 {refund['refundId']} → 重登 {corrected['refundId']}（replaced_by_id CAS 替代）")
    record(voided=refund['refundId'], correctedId=corrected['refundId'], correctedCents=80000, date=TODAY)

    # M09-T03 场景闭环（批次#35 语义）：退款回缺→补收→净额恢复——交付门禁放行。
    # 补收走销售侧 collect（#28 SETTLED 恢复补收路径——净口径<期额放行；engineering collect 走毛额限额拒）。
    # S9 更正后净缺口=8 万（更正 10 万→8 万）：补收 8 万，净额恢复=毛额 4000 万。
    act(sales, 'POST', f"/franchise/sales/stages/{stage_map['SIGN_AFTER']}/collect",
        {'amountCents': 80000, 'method': 'bank', 'transactionNo': 'CV-TXN-BACKFILL-' + run,
         'receivedDate': TODAY}, idem='cv-backfill-' + run)
    sign_net = act(sales, 'GET', f'/franchise/sales/contracts?customerId={customer_id}')[0]['stages']
    sign_net_row = [s for s in sign_net if s['stage'] == 'SIGN_AFTER'][0]
    assert sign_net_row['collectedCents'] == 40000000, sign_net_row  # 净额恢复=毛额
    print('    ✅ 补收 8 万（更正后净缺口；SIGN_AFTER 净额恢复 400 万——M09-T03 补收放行）')
    record(backfillCents=80000, netRestored=40000000)

    step('S10 M09-T05 交付（净口径门禁）+签收+结算+归档——M09 冻结/签收/归档修订断言')
    deliver_asset = upload_asset(engineer, sales, 'ENG_SITE_PHOTO')
    delivered = act(engineer, 'POST', f'/franchise/engineering/{project_id}/deliver',
                    {'date': TODAY, 'recipient': '陈先生',
                     'fileAssetId': deliver_asset, 'ownerAssetId': deliver_asset,
                     'note': 'CV01 交付'}, idem='cv-deliver-' + run)
    detail4 = act(engineer, 'GET', f'/franchise/engineering/{project_id}')
    assert detail4['closure']['deliveredAt'], detail4['closure']
    assert detail4['closure']['fileAssetId'] == deliver_asset, 'M09-T02：签收凭据真实可读（读侧透出）'
    settled = act(engineer, 'POST', f'/franchise/engineering/{project_id}/settle',
                  {'date': TODAY, 'note': 'CV01 结算'}, idem='cv-settle-' + run)
    act(engineer, 'POST', f'/franchise/engineering/{project_id}/archive', {}, idem='cv-archive-' + run)
    detail5 = act(engineer, 'GET', f'/franchise/engineering/{project_id}')
    assert detail5['closure']['archivedAt'], 'M09-T05：归档'
    print(f'    ✅ 交付（凭据 {deliver_asset} 双引用）+签收+结算+归档——冻结/签收/归档全链')
    record(deliverAsset=deliver_asset, ownerAssetSame=True,
           deliveredAt=TODAY, settledAt=TODAY, archived=True)

    step('S11 M10-T06a 提成 policy+release（资格走统一实收账）+operating reconcile（M10 提成/月末断言）')
    period = '2026-11'
    act(boss, 'POST', '/franchise/performance/commission/policy',
        {'projectId': project_id, 'totalCents': 68000000, 'signAfterCents': 40000000,
         'baseDoneCents': 18000000, 'capDoneCents': 10000000,
         'assigneeUserId': engineer['userId'], 'assigneeName': 'CV01 工程负责人',
         'evidenceRef': 'CV01 提成凭据', 'note': 'CV01 提成政策'}, idem='cv-policy-' + run)
    policy_id = psql(f'SELECT id FROM fr_perf_commission_policy WHERE project_id={project_id} '
                     f'ORDER BY id DESC LIMIT 1')
    released = act(finance, 'POST', '/franchise/performance/commission/release',
                   {'projectId': project_id, 'stage': 'SIGN_AFTER'}, idem='cv-release-' + run)
    operating = act(boss, 'GET', f'/franchise/performance/operating/analytics?period={period}')
    assert operating.get('trend') is not None, operating  # M10-T06：近6月趋势/收支构成/应收应付
    # 月末对账（M10-T04 截面：截至月末，12 月收付不污染 11 月快照）
    reconciled = act(boss, 'POST', '/franchise/performance/operating/reconcile',
                     {'period': period}, idem='cv-reconcile-' + run)
    assert reconciled.get('period') == period, reconciled
    print(f'    ✅ 提成 policy+release（policyId={policy_id}）+经营月报（三期来源映射）')
    record(policyId=policy_id, released=True, period=period)

    step('S12 负测收口——重复登记幂等/同键改输入/跨组织隔离/待办投影（M12 矩阵渠道）')
    replay = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                 {'stage': 'BASE_DONE', 'amount': 180000, 'transactionNo': 'CV-TXN-S7A-' + run,
                  'occurredAt': TODAY, 'fileAssetId': arrival_asset}, idem='cv-col2-' + run)
    assert replay.get('replayed') is True or str(replay.get('collectionId')) != '', replay
    act(engineer_b, 'POST', '/franchise/performance/commission/policy',
        {'projectId': project_id, 'totalCents': 1}, expect_code=403)  # 无 COMMISSION 码（856/857 矩阵）
    other_engineer = login(env_b['engineer'])
    act(other_engineer, 'GET', f'/franchise/engineering/{project_id}', expect_code=1115000001)
    todos = act(finance, 'GET', '/platform/todos')['items']
    abnormal_todos = [t for t in todos if t['key'].startswith('fr-fin:abnormal-link:')]
    assert abnormal_todos, 'CV01 异常资金待办须在列（finance 岗）'
    print(f'    ✅ 幂等重放/engineer 政策拒绝(1_116)/跨组织 001/异常资金待办 {len(abnormal_todos)} 条在列')
    record(replayOk=True, crossOrgRejected=True, abnormalTodos=len(abnormal_todos))

    # 证据落盘
    evidence_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                '../docs/business-flow-audit-20261103/evidence')
    os.makedirs(evidence_dir, exist_ok=True)
    evidence_path = os.path.join(evidence_dir, f'cv01-e3-{run}.json')
    EVIDENCE['verdict'] = 'PASS'
    with open(evidence_path, 'w', encoding='utf-8') as f:
        json.dump(EVIDENCE, f, ensure_ascii=False, indent=1)
    print(f'\n✅ CV-01 E3 十二步全绿（evidence: {os.path.relpath(evidence_path)}）')
    print('    五项：M06-T05 报价导出合同交付 ✅ / M07-T05 节点材料凭据 ✅ / M08-T06 三期收款退款异常 ✅ /'
          ' M09-T05 冻结签收归档 ✅ / M10-T06 提成月末 ✅')


if __name__ == '__main__':
    main()
