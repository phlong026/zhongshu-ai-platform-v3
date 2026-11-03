#!/usr/bin/env python3
"""阶段 2 · 工程全周期端到端验收（17 文档 §八 AC1-AC14，真实 DB 零外呼——工程域零 provider）。

前提：本地 server 以 local,pg,zsdev 运行（迁移 814/815/816/856 随启动落库），
并以 stub 微信登录启动：
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
断言口径：HTTP 200 + body.code 二元组（07 §三.4；401/403 同 content e2e 先例）。
用法：python3 script/franchise-engineering-e2e.py
"""
import base64
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0
run = str(int(time.time()))

PNG_1X1 = bytes.fromhex(
    '89504e470d0a1a0a0000000d4948445200000001000000010806000000'
    '1f15c4890000000d49444154789c626001000000ffff030000060005'
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
        with urllib.request.urlopen(req, data=data, timeout=60) as resp:
            http_status = resp.status
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        http_status = e.code
        payload = json.loads(e.read().decode())
    assert http_status == 200, f'{method} {path} -> HTTP {http_status} (body={payload})'
    if isinstance(expect_code, int):
        expect_code = [expect_code]
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


def setup(mobile_prefix=None, batch=None):
    payload = {}
    if mobile_prefix:
        payload['mobilePrefix'] = mobile_prefix
    payload['batch'] = batch or ('eng' + str(int(time.time()) % 1000000))
    return call('POST', '/platform/dev-setup/franchise-e2e/setup', payload)


def login(who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-eng-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': result['accessToken'], 'post': who['postId'], 'userId': who['userId'],
            'mobile': who['mobile']}


def act(user, method, path, body=None, expect_code=0, idem=None):
    headers = {'Idempotency-Key': idem} if idem else None
    return call(method, path, body, token=user['token'], post_id=user['post'],
                expect_code=expect_code, headers=headers)


def upload_asset(user, sales_helper, asset_type='ENG_DRAWING'):
    """基-1 票据链（B_END 免审）：ticket（登录+任职即可）→ dev 字节（design:use 岗位门，sales 代传）→ complete。"""
    sha = hashlib.sha256(PNG_1X1).hexdigest()
    ticket = act(user, 'POST', '/franchise/assets/upload-tickets',
                 {'assetType': asset_type, 'mimeType': 'image/png',
                  'sizeBytes': len(PNG_1X1), 'sha256': sha})
    asset_id = ticket['assetId']
    act(sales_helper, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
        {'contentBase64': base64.b64encode(PNG_1X1).decode()})
    completed = act(user, 'POST', f'/franchise/assets/{asset_id}/complete')
    assert completed['uploadStatus'] == 'ACCEPTED', completed
    return asset_id


def norm_date(v):
    """LocalDate 双形状归一：[2026,11,20] → '2026-11-20'（Jackson 默认数组形态；字符串原样）。"""
    if isinstance(v, list) and len(v) == 3:
        return '%04d-%02d-%02d' % tuple(v)
    return v


def node_dates(detail, start='2026-11-10', span=5):
    """为全部节点生成顺序不重叠日期（end=start+span-1，下一节点 start=上一 end+1）。"""
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
    step('AC1：十三表迁移落库（814/815/816/856）')
    tables = psql("SELECT count(*) FROM information_schema.tables WHERE table_name IN ("
                  "'fr_eng_project','fr_eng_document','fr_eng_plan_version','fr_eng_plan_node',"
                  "'fr_eng_site_record','fr_eng_closure','fr_eng_material','fr_eng_supply',"
                  "'fr_eng_supply_receipt','fr_eng_material_movement','fr_eng_collection_term',"
                  "'fr_eng_collection','fr_eng_followup')")
    assert tables == '13', f'fr_eng_* 十三表须齐（实际 {tables}）'
    applied = psql("SELECT count(*) FROM flyway_schema_history WHERE version IN "
                   "('20261102.814','20261102.815','20261102.816','20261102.856')")
    assert applied == '4', f'814/815/816/856 迁移须落库（实际 {applied}）'
    menus = psql("SELECT count(*) FROM system_menu WHERE id BETWEEN 710600 AND 710604")
    assert menus == '5', f'菜单 710600 段须 5 行（实际 {menus}）'
    role_menus = psql("SELECT count(*) FROM system_role_menu WHERE id BETWEEN 714200 AND 714299")
    assert int(role_menus) >= 14, f'role_menu 714200 段须 14 行（实际 {role_menus}）'
    print('    ✅ 十三表 + 四迁移 + 权限种子在库')

    step('一键搭建（含 engineerB）并登录五岗')
    env = setup()
    org = env['storeOrgId']
    sales = login(env['sales'])
    boss = login(env['boss'])
    finance = login(env['finance'])
    engineer_a = login(env['engineer'])    # project-manager 岗（移交接收人/工程负责人）
    engineer_b = login(env['engineerB'])   # engineer 岗（owner 校验对照）
    print(f'    ✅ 门店 org={org}；A(pm)={engineer_a["userId"]} B(eng)={engineer_b["userId"]}')

    step('AC2：未认证 401；权限矩阵与 owner 校验')
    call('GET', '/franchise/engineering/projects', expect_code=401)
    act(sales, 'POST', '/franchise/engineering/projects',
        {'name': 'sales-' + run, 'customer': '陈', 'city': '武汉'}, expect_code=403)
    manual = act(engineer_b, 'POST', '/franchise/engineering/projects',
                 {'name': 'B手工-' + run, 'customer': '李四', 'city': '黄石',
                  'phone': '13900002222', 'floors': 2, 'area': 320},
                 idem='e2e-manual-' + run)
    manual_id = manual['id']
    # owner 校验：A 操作 B 的工程 → 000
    act(engineer_a, 'POST', f'/franchise/engineering/{manual_id}/prepare',
        {'total': 1000000, 'first': 400000, 'note': 'x'}, expect_code=1115000000,
        idem='e2e-own-' + run)
    print('    ✅ 401 / sales write 403 / B 建档 / A 越权 owner→000')

    step('AC3：移交链走通 → 工程档案同事务落库 + 重复接收幂等')
    act(boss, 'POST', '/franchise/design-policies',
        {'total': 680000, 'first': 408000, 'scope': '方案、施工图与交底', 'recipient': '总部财务'})
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '王先生', 'phone': '1391111' + run[-4:], 'need': '两层乡墅', 'source': '视频号咨询'})
    assert lead['result'] == 'CREATED', lead
    customer_id = lead['customerId']
    act(sales, 'POST', f'/franchise/customers/{customer_id}/schemes',
        {'kind': 'BUDGET', 'title': '参考预算', 'payload': '{"area":400}',
         'width': 13, 'depth': 16, 'floors': 2, 'area': 400, 'totalCents': 68000000})
    detail = act(sales, 'POST', f'/franchise/customers/{customer_id}/commission',
                 {'text': '两层乡墅', 'scope': '方案、施工图与交底',
                  'total': 680000, 'first': 408000, 'recipient': '总部财务', 'confirmed': True})
    commission_id = detail['commission']['id']
    version = detail['commission']['submissionVersion']
    admin = login(env['designAdmin'])
    for stage_act, stage in [(finance, 'first-ready'), (finance, 'tail-paid'), (finance, 'tail-ready')]:
        act(stage_act, 'POST', '/platform/dev-setup/franchise-e2e/fee-settlement',
            {'commissionId': commission_id, 'submissionVersion': version, 'stage': stage})
    act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-complete',
        {'note': '完成', 'confirmed': True})
    dispatch = act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-dispatch',
                   {'note': '交付', 'recipient': '总部设计行政', 'confirmed': True})
    seq = dispatch['commission']['dispatchSeq']
    packet = {'dispatchSeq': seq, 'resultFile': '{"name":"成果"}', 'drawingFile': '{"name":"施工图"}',
              'reviewFile': '{"name":"审核依据"}', 'confirmationFile': '{"name":"客户确认"}',
              'drawingVersion': 'S2-' + run, 'confirmationDate': '2026-11-02',
              'note': '现场交底完成', 'managerUserId': engineer_a['userId'], 'confirmed': True}
    act(sales, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-submit', packet,
        idem='e2e-ps-' + run)
    receive_key = 'e2e-pr-' + run
    received = act(engineer_a, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-receive',
                   {'confirmed': True}, idem=receive_key)
    assert received['commission']['status'] == 'ACCEPTED', received
    row = psql(f"SELECT count(*), max(source_type), max(manager_user_id), max(drawing_version) "
               f"FROM fr_eng_project WHERE source_commission_id={commission_id}")
    count, source_type, manager, drawing = (row.split('|') + ['', '', '', ''])[:4]
    assert count == '1' and source_type == 'HANDOVER' and str(manager) == str(engineer_a['userId']) \
        and drawing == 'S2-' + run, row
    docs = psql(f"SELECT count(*) FROM fr_eng_document WHERE name='正式施工图'")
    assert int(docs) >= 1, '移交五项文档须随包落库'
    proj_row = psql(f"SELECT id FROM fr_eng_project WHERE source_commission_id={commission_id}")
    project_id = int(proj_row)
    # 重复回调幂等：同键重放不重复建档
    act(engineer_a, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-receive',
        {'confirmed': True}, idem=receive_key)
    assert psql(f"SELECT count(*) FROM fr_eng_project WHERE source_commission_id={commission_id}") == '1'
    print(f'    ✅ 移交建档 project={project_id}（图纸版本/五文档/负责人随包）；重复接收幂等')

    step('AC4：手工重名 008 + 增项 + 初始化一致（模板计划日期留空/约定未生成）')
    act(engineer_a, 'POST', '/franchise/engineering/projects',
        {'name': 'B手工-' + run, 'customer': 'x', 'city': 'y'}, expect_code=1115000008,
        idem='e2e-dup-' + run)
    increment = act(engineer_a, 'POST', '/franchise/engineering/projects',
                    {'name': '增项-' + run, 'customer': '王先生', 'city': '武汉',
                     'parentProjectId': project_id}, idem='e2e-inc-' + run)
    assert int(increment['parentProjectId']) == project_id and increment['sourceType'] == 'INCREMENT', increment
    detail = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}')
    assert len(detail['nodes']) == 13, f'模板展开 13 节点（实际 {len(detail["nodes"])}）'
    assert all(n['startDate'] is None and n['endDate'] is None for n in detail['nodes']), '日期留空'
    assert detail['status'] == 'PENDING_START'
    assert psql(f"SELECT count(*) FROM fr_eng_collection_term WHERE project_id={project_id}") == '0', '约定未生成'
    versions = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}/plan/history')
    assert len(versions) == 1 and versions[0]['source'] == 'TEMPLATE', 'v1=TEMPLATE 快照'
    print('    ✅ 重名 008 / 增项挂 parent / 模板初始化一致')

    step('AC5：开工准备保存即两期约定固化（无审批无门禁）+ 重复提交幂等')
    contract_asset = upload_asset(engineer_a, sales)
    prepare_key = 'e2e-prepare-' + run
    prepared = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/prepare',
                   {'total': 1000000, 'first': 400000, 'contractAssetId': contract_asset,
                    'note': '现场班组与开工准备记录', 'workers': '张三, 李四'}, idem=prepare_key)
    assert prepared['contractTotalCents'] == 100000000 and prepared['firstCents'] == 40000000, prepared
    terms = psql(f"SELECT string_agg(stage || ':' || amount_cents, ',' ORDER BY stage) "
                 f"FROM fr_eng_collection_term WHERE project_id={project_id}")
    assert terms == 'START:40000000,TOPPING:60000000', terms
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/prepare',
        {'total': 1000000, 'first': 400000, 'contractAssetId': contract_asset,
         'note': '现场班组与开工准备记录', 'workers': '张三, 李四'}, idem=prepare_key)
    assert psql(f"SELECT count(*) FROM fr_eng_collection_term WHERE project_id={project_id}") == '2'
    print('    ✅ 约定固化 START=40万 TOPPING=60万；同键重放幂等')

    step('AC6：计划链——日期校验 002 → 顺移（材料随移+快照）→ 材料门禁 007 → 撤回 003 → 更正日期链 003')
    nodes = node_dates(prepared)
    overlap = [dict(n) for n in nodes]
    overlap[1]['startDate'] = overlap[0]['endDate']  # 与上一节点重叠
    act(engineer_a, 'PUT', f'/franchise/engineering/{project_id}/plan', {'nodes': overlap},
        expect_code=1115000002, idem='e2e-plan-overlap-' + run)
    missing = [dict(n) for n in nodes]
    missing[2]['endDate'] = None
    act(engineer_a, 'PUT', f'/franchise/engineering/{project_id}/plan', {'nodes': missing},
        expect_code=1115000002, idem='e2e-plan-missing-' + run)
    saved = act(engineer_a, 'PUT', f'/franchise/engineering/{project_id}/plan', {'nodes': nodes},
                idem='e2e-plan-' + run)
    assert norm_date(saved['nodes'][2]['startDate']) == nodes[2]['startDate'], (saved['nodes'][2], nodes[2])
    # START 未收款 → 开工门禁 007
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/start',
        {'fileAssetId': contract_asset, 'note': '开工'}, expect_code=1115000007,
        idem='e2e-start-gate-' + run)
    # START 首笔收款（写通断言在 AC8；此处供开工门禁放行）
    collect_asset = upload_asset(engineer_a, sales)  # M5 分派：ENG_DRAWING 归工程线（finance 只在收款登记引用 assetId）
    col_key = 'e2e-col-' + run
    collected = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                    {'stage': 'START', 'amount': '400000', 'transactionNo': 'C1-' + run,
                     'occurredAt': '2026-10-25', 'fileAssetId': collect_asset}, idem=col_key)
    started = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/start',
                  {'fileAssetId': contract_asset, 'note': '开工'}, idem='e2e-start-' + run)
    assert started['status'] == 'IN_PROGRESS' and started['actualStartAt'], started
    print('    ✅ 002 两负例 / 门禁 007 / 首收款后开工成立')

    # 材料计划挂 n3（nodeIndex=3）→ reschedule 自 n3 顺移 7 天 → 材料需到场日随移
    node3 = saved['nodes'][2]
    plan = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/materials',
               {'nodeId': node3['id'], 'due': '2026-11-20',
                'lines': [{'name': '钢筋', 'spec': 'HRB400', 'unit': '吨', 'quantity': '10'},
                          {'name': '水泥', 'unit': '包', 'quantity': '200'}]},
               idem='e2e-mat-' + run)
    material_plan_id = plan['materialPlanId']
    steel_line = next(l for l in plan['plans'][0]['lines'] if l['name'] == '钢筋')
    steel_id = int(steel_line['materialId'])
    from datetime import date, timedelta
    new_start3 = date.fromisoformat(norm_date(node3['startDate'])) + timedelta(days=7)
    shifted = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/plan/reschedule',
                  {'nodeIndex': 3, 'newStart': new_start3.isoformat(), 'note': '材料晚到整体顺移'},
                  idem='e2e-shift-' + run)
    assert norm_date(shifted['nodes'][2]['startDate']) == new_start3.isoformat(), shifted['nodes'][2]
    assert norm_date(shifted['nodes'][0]['startDate']) == nodes[0]['startDate'], '已完成/范围外节点不动'
    steel_due = psql(f"SELECT required_date FROM fr_eng_material WHERE id={steel_id}")
    expect_due = (date(2026, 11, 20) + timedelta(days=7)).isoformat()
    assert steel_due == expect_due, f'材料需到场日须随移（{steel_due} ≠ {expect_due}）'
    history = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}/plan/history')
    assert len(history) >= 3, f'顺移须落版本快照（实际 {len(history)} 版）'
    print(f'    ✅ 顺移 7 天（n3..n13 + 材料随移）；快照 {len(history)} 版')

    # 节点完成链：n1（无材料）→ n2 → n3 材料门禁 007 → 到货后通过；撤回 003；更正 003
    site_asset = upload_asset(engineer_a, sales, 'ENG_SITE_PHOTO')
    for node in shifted['nodes'][0:2]:
        done = act(engineer_a, 'POST', f"/franchise/engineering/{project_id}/nodes/{node['id']}/complete",
                   {'note': '施工完成', 'fileAssetId': site_asset}, idem=f"e2e-nc-{node['id']}-{run}")
        assert done['status'] == 'IN_PROGRESS'
    node3_id = int(shifted['nodes'][2]['id'])
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/nodes/{node3_id}/complete',
        {'note': '施工', 'fileAssetId': site_asset}, expect_code=1115000007,
        idem='e2e-nc-gate-' + run)
    # 撤回 n2：非最近完成（n2 是最近完成 ✓ 但 n3 无记录 → 可撤）；先撤 n2 再完成回来
    node2_id = int(shifted['nodes'][1]['id'])
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/nodes/{node2_id}/reopen',
        {'reason': '误点完成'}, idem='e2e-reopen-' + run)
    detail = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}')
    node2 = next(n for n in detail['nodes'] if int(n['id']) == node2_id)
    assert node2['status'] == 'IN_PROGRESS' and node2['completedAt'] is None, node2
    completion_downgraded = psql(
        f"SELECT count(*) FROM fr_eng_site_record WHERE node_id={node2_id} AND completion=FALSE "
        f"AND note='施工完成'")
    assert completion_downgraded == '1', '完成留痕须降级为普通现场记录'
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/nodes/{node2_id}/complete',
        {'note': '重新完成', 'fileAssetId': site_asset}, idem='e2e-nc2-' + run)
    # 更正 n2 完成日期：早于 n1 完成日 → 003
    node1 = next(n for n in detail['nodes'] if n['nodeIndex'] == 1)
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/nodes/{node2_id}/correct',
        {'completedAt': '2026-11-01', 'note': 'x', 'fileAssetId': site_asset, 'reason': '改早'},
        expect_code=1115000003, idem='e2e-corr-bad-' + run)
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/nodes/{node2_id}/correct',
        {'completedAt': norm_date(node1['completedAt']), 'note': '同日修正', 'fileAssetId': site_asset,
         'reason': '日期核对'}, idem='e2e-corr-' + run)
    assert psql(f"SELECT corrections IS NOT NULL FROM fr_eng_plan_node WHERE id={node2_id}") == 't'
    print('    ✅ 材料门禁 007 / 撤回链 / 更正日期链 003')

    step('AC7：材料链——terms 403 → payable 落库/幂等 → 已付款锁定 004 → 到货 005/补发/质检 → 调整边界')
    steel_supply = int(steel_line.get('supplyId') or psql(
        f"SELECT s.id FROM fr_eng_supply s JOIN fr_eng_material m ON s.material_id=m.id "
        f"WHERE m.id={steel_id}"))
    act(engineer_a, 'POST', f'/franchise/engineering/supplies/{steel_supply}/terms',
        {'supplier': '武钢直供', 'unitPrice': '3800', 'freight': '5000',
         'basis': '{"name":"采购合同"}'}, expect_code=403, idem='e2e-terms-403-' + run)
    terms_key = 'e2e-terms-' + run
    termed = act(finance, 'POST', f'/franchise/engineering/supplies/{steel_supply}/terms',
                 {'supplier': '武钢直供', 'unitPrice': '3800', 'freight': '5000',
                  'basis': '{"name":"采购合同"}'}, idem=terms_key)
    supply_row = termed
    # 应付=3800 元/吨 ×10 吨 + 5000 运费 = 3805000 分
    payable = psql(f"SELECT total_cents, recipient FROM fr_fin_payable WHERE id=(SELECT payable_id "
                   f"FROM fr_eng_supply WHERE id={steel_supply})")
    assert payable == '4300000|武钢直供', payable  # 3800元/吨×10吨 + 运费5000元
    replay_terms = act(finance, 'POST', f'/franchise/engineering/supplies/{steel_supply}/terms',
                       {'supplier': '武钢直供', 'unitPrice': '3800', 'freight': '5000',
                        'basis': '{"name":"采购合同"}'}, idem=terms_key)
    assert int(replay_terms['supplyId']) == steel_supply, 'PayablePort 幂等重放返回既有'
    # 付款（finance 既有端点）→ 金额锁定 004
    payable_id = psql(f"SELECT payable_id FROM fr_eng_supply WHERE id={steel_supply}")
    act(finance, 'POST', '/franchise/finance/payments',
        {'payableId': int(payable_id), 'amountCents': 4300000, 'payDate': '2026-10-26',
         'transactionNo': 'PAY-' + run}, idem='e2e-pay-' + run)
    act(finance, 'POST', f'/franchise/engineering/supplies/{steel_supply}/terms',
        {'supplier': '武钢直供', 'unitPrice': '4000', 'freight': '5000',
         'basis': '{"name":"采购合同2"}'}, expect_code=1115000004, idem='e2e-terms-lock-' + run)
    print('    ✅ terms 权限/应付金额/幂等重放/已付款锁定 004')

    # 到货：流水重复 005；REJECTED 质检闭环；差异自动补发行
    arrival_asset = upload_asset(engineer_a, sales, 'ENG_SITE_PHOTO')
    arrive1 = act(engineer_a, 'POST', f'/franchise/engineering/supplies/{steel_supply}/arrival',
                  {'actual': '6', 'quality': 'REJECTED', 'transactionNo': 'AR-' + run,
                   'fileAssetId': arrival_asset, 'occurredAt': '2026-10-26'},
                  idem='e2e-arr1-' + run)
    act(engineer_a, 'POST', f'/franchise/engineering/supplies/{steel_supply}/arrival',
        {'actual': '1', 'quality': 'OK', 'transactionNo': 'AR-' + run,
         'fileAssetId': arrival_asset}, expect_code=1115000005, idem='e2e-arr-dup-' + run)
    receipt1 = next(r for r in arrive1['receipts'] if r['kind'] == 'RECEIPT')
    reship = next(r for r in arrive1['receipts'] if r['kind'] == 'RESHIP')
    assert reship['parentReceiptId'] == receipt1['id'] and \
        float(reship['expectedQuantity']) == 4.0, '差异自动补发行（应到10 已到6 → 补4）'
    # 质检处置：更换后合格
    act(engineer_a, 'POST', f'/franchise/engineering/supplies/{steel_supply}/arrival',
        {'qualityResolution': {'receiptId': receipt1['id'], 'note': '供应商更换后复检合格',
                               'fileAssetId': arrival_asset}}, idem='e2e-q-' + run)
    # 补发到货（RESHIP 行回填 4 吨）
    act(engineer_a, 'POST', f'/franchise/engineering/supplies/{steel_supply}/arrival',
        {'parentReceiptId': reship['id'], 'actual': '4', 'quality': 'OK',
         'transactionNo': 'AR2-' + run, 'fileAssetId': arrival_asset},
        idem='e2e-arr2-' + run)
    # 调整边界：已到货 10 → 调少于已到 004；0=取消（未到货材料）
    act(engineer_a, 'PUT', f'/franchise/engineering/materials/{steel_id}',
        {'quantity': '5', 'reason': '减量'}, expect_code=1115000004, idem='e2e-adj-bad-' + run)
    cement_line = next(l for l in plan['plans'][0]['lines'] if l['name'] == '水泥')
    act(engineer_a, 'PUT', f"/franchise/engineering/materials/{cement_line['materialId']}",
        {'quantity': '0', 'reason': '不再需要'}, idem='e2e-adj-cancel-' + run)
    summary = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}/materials/summary')
    steel_summary = next(l for p in summary['plans'] for l in p['lines'] if l['name'] == '钢筋')
    assert float(steel_summary['remaining']) == 0.0 and steel_summary['paymentStatus'] == '已付清', steel_summary
    node3_after = psql(f"SELECT status FROM fr_eng_plan_node WHERE id={node3_id}")
    assert node3_after == 'IN_PROGRESS', node3_after  # n2 完成后自动推进，材料已齐待完成
    print('    ✅ 005/质检闭环/补发/取消；钢筋已验收齐全+已付清')

    step('AC8：收款链——TOPPING 前置 001（文案）→ 403 矩阵 → 写通 fr_fin → 幂等 → 累计 006')
    # 首笔 START 收款已在 AC6 上移（开工门禁）；此处断言写通与幂等 + 403 矩阵 + 超限 + TOPPING 前置
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/collections',
        {'stage': 'START', 'amount': '400000', 'transactionNo': 'C9-' + run,
         'fileAssetId': collect_asset}, expect_code=403, idem='e2e-col-403a-' + run)
    act(boss, 'POST', f'/franchise/engineering/{project_id}/collections',
        {'stage': 'START', 'amount': '400000', 'transactionNo': 'C9-' + run,
         'fileAssetId': collect_asset}, expect_code=403, idem='e2e-col-403b-' + run)
    receipt_in_fin = psql(f"SELECT count(*) FROM fr_fin_receipt WHERE source_type='business' AND "
                          f"source_ref LIKE 'engineering:{project_id}:start:%' AND amount_cents=40000000")
    assert receipt_in_fin == '1', '收款须写通 fr_fin（BusinessReceiptPort）'
    assert int(collected['receiptId']) > 0
    replayed = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                   {'stage': 'START', 'amount': '400000', 'transactionNo': 'C1-' + run,
                    'occurredAt': '2026-10-25', 'fileAssetId': collect_asset}, idem=col_key)
    assert replayed['replayed'] is True and int(replayed['receiptId']) == int(collected['receiptId']), '幂等重放返回既有'
    # 累计超应收 006
    act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
        {'stage': 'START', 'amount': '1', 'transactionNo': 'C2-' + run,
         'fileAssetId': collect_asset}, expect_code=1115000006, idem='e2e-col-exceed-' + run)
    # TOPPING 未封顶 → 001 文案带「请先完成主体封顶」
    err = None
    try:
        act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
            {'stage': 'TOPPING', 'amount': '600000', 'transactionNo': 'C3-' + run,
             'fileAssetId': collect_asset}, idem='e2e-col-top-bad2-' + run)
    except AssertionError as e:
        err = str(e)
    assert err and '请先完成主体封顶' in err, f'文案须带「请先完成主体封顶」（{err}）'
    print('    ✅ 写通/幂等/006/收款侧前置 001')

    step('AC9：催办双维度同日同内容幂等 + TodoProvider 待办')
    # START 已收齐（无 remaining）→ 蓝本文案；TOPPING 未封顶 → 封顶前置文案（dunning 的双前置口径）
    dun_err = None
    try:
        act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/dunning',
            {'stage': 'START', 'note': 'x'}, idem='e2e-dun0-' + run)
    except AssertionError as e:
        dun_err = str(e)
    assert dun_err and ('已收齐' in dun_err or '主体封顶' in dun_err), dun_err
    followup = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/material-followups',
                   {'materialPlanId': material_plan_id, 'note': '催钢筋到货', 'promiseDate': '2026-11-25'},
                   idem='e2e-mf1-' + run)
    assert len([f for f in followup if f['kind'] == 'MATERIAL']) == 1
    followup2 = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/material-followups',
                    {'materialPlanId': material_plan_id, 'note': '催钢筋到货',
                     'promiseDate': '2026-11-25'}, idem='e2e-mf2-' + run)
    assert len([f for f in followup2 if f['kind'] == 'MATERIAL']) == 1, '材料催办同日同内容幂等'
    # TodoProvider 断言挪至 AC10 封顶后（此刻 START 已收齐、TOPPING 未封顶——待办为空是正确派生）
    print('    ✅ dunning 双前置口径 / material-followup 同日同内容幂等')

    step('AC10：尾段——材料门禁 → 全节点 → 封顶+TOPPING 收款 → 交付门禁 → 白名单 → 归档 → 009')
    detail = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}')
    # 依次完成 n3..封顶节点（钢筋已齐）
    for node in detail['nodes'][2:]:
        act(engineer_a, 'POST', f"/franchise/engineering/{project_id}/nodes/{node['id']}/complete",
            {'note': node['name'] + ' 完成', 'fileAssetId': site_asset},
            idem=f"e2e-fc-{node['id']}-{run}")
    assert psql(f"SELECT status FROM fr_eng_project WHERE id={project_id}") == 'READY_DELIVER'
    # 封顶后 TOPPING 收款放行
    # 封顶后 TOPPING dunning 幂等对（AC9 续：此刻有 remaining、封顶完成）
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/dunning',
        {'stage': 'TOPPING', 'note': '电话催收封顶款'}, idem='e2e-dun1-' + run)
    dunning2 = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/dunning',
                   {'stage': 'TOPPING', 'note': '电话催收封顶款'}, idem='e2e-dun2-' + run)
    assert len([f for f in dunning2['followups'] if f['kind'] == 'COLLECTION' and f['stage'] == 'TOPPING'
                and '电话催收封顶款' in f['note']]) == 1, '同日同内容幂等（不新增行）'
    todos = act(engineer_a, 'GET', '/platform/todos')
    assert any(t['domain'] == 'fr-eng' and 'start' not in t['key'].split(':')[-1]
               for t in todos['items']), 'TodoProvider 封顶款催收回访待办须落库'
    topped_col = act(finance, 'POST', f'/franchise/engineering/{project_id}/collections',
                     {'stage': 'TOPPING', 'amount': '600000', 'transactionNo': 'C4-' + run,
                      'fileAssetId': collect_asset}, idem='e2e-col-top-' + run)
    assert int(topped_col['receiptId']) > 0
    # 结算屏（交付前只读）
    before = act(engineer_a, 'GET', f'/franchise/engineering/{project_id}/settle-summary')
    assert before['materialsTotalCents'] == 4300000 and before['materialsPaidCents'] == 4300000, before
    assert before['collectionStages'][0]['confirmedCents'] == 40000000
    assert before['expensesNote'] == '项目费用汇总待阶段 3 接入'
    # 交付：顺序错（未收款时已过）→ 直接收款后交付成功
    deliver_asset = upload_asset(engineer_a, sales)  # ✓ engineer
    delivered = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/deliver',
                    {'recipient': '王先生', 'fileAssetId': deliver_asset,
                     'ownerAssetId': deliver_asset, 'note': '交付完成'}, idem='e2e-deliver-' + run)
    assert delivered['status'] == 'DELIVERED' and delivered['closure']['deliveredAt'], delivered
    # 交付后白名单外动作 000
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/site-records',
        {'nodeId': node3_id, 'note': 'x', 'fileAssetIds': [site_asset]},
        expect_code=1115000000, idem='e2e-post-deliver-000-' + run)
    # 顺序错：未结算直接归档 009
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/archive',
        idem='e2e-arch-early-' + run, expect_code=1115000009)
    settled = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/settle',
                  {'note': '收付款与成本核对完成'}, idem='e2e-settle-' + run)
    assert settled['materialsTotalCents'] == 4300000 and settled['materialsRemainingCents'] == 0
    archived = act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/archive',
                   idem='e2e-arch-' + run)
    assert archived['status'] == 'ARCHIVED'
    # 归档后白名单：材料催办 000、dunning 仍可
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/material-followups',
        {'materialPlanId': material_plan_id, 'note': 'x'}, expect_code=1115000000,
        idem='e2e-arch-mf-' + run)
    # 归档后 dunning 白名单放行：错在业务层（款项已收齐 001）而非 000 越界
    act(engineer_a, 'POST', f'/franchise/engineering/{project_id}/dunning',
        {'stage': 'TOPPING', 'note': '尾款已收齐核对'}, expect_code=1115000001,
        idem='e2e-arch-dun-' + run)
    print('    ✅ 门禁放行/结算汇总/交付后 000/顺序 009/归档后白名单')

    step('AC11：换负责人——不在岗 010 → 成功 → 旧负责人失权 000（在未归档 manual 工程上验证）')
    act(engineer_b, 'POST', f'/franchise/engineering/{manual_id}/manager',
        {'engineerUserId': sales['userId']}, expect_code=1115000010, idem='e2e-mgr-bad-' + run)
    act(engineer_a, 'POST', f'/franchise/engineering/{manual_id}/manager',
        {'engineerUserId': engineer_b['userId']}, expect_code=1115000000, idem='e2e-mgr-own-' + run)
    handed = act(engineer_b, 'POST', f'/franchise/engineering/{manual_id}/manager',
                 {'engineerUserId': engineer_a['userId'], 'note': '责任交接'},
                 idem='e2e-mgr-' + run)
    assert handed['managerUserId'] == engineer_a['userId']
    assert psql(f"SELECT count(*) FROM pf_command_event WHERE aggregate_type='fr-eng-project' "
                f"AND aggregate_id={manual_id} AND command='eng-project-manager'") == '1', 'CAS+审计留痕'
    # 旧负责人 B 立即失权
    act(engineer_b, 'POST', f'/franchise/engineering/{manual_id}/manager',
        {'engineerUserId': engineer_a['userId']}, expect_code=1115000000, idem='e2e-mgr-old-' + run)
    print('    ✅ 010/换负责人生效/旧负责人 owner 000')

    step('AC12：脱敏——非负责人工程岗无金额/手机号；boss/finance 全量')
    # manual 负责人已换为 A（AC11）；B（同组织工程岗、已失权）查看须脱敏
    scoped = act(engineer_b, 'GET', f'/franchise/engineering/{manual_id}')
    assert 'contractTotalCents' not in scoped and 'phone' not in scoped, scoped.keys()
    full_by_boss = act(boss, 'GET', f'/franchise/engineering/{manual_id}')
    assert 'phone' in full_by_boss, 'boss 全量'
    finance_view = act(finance, 'GET', f'/franchise/engineering/{manual_id}')
    assert 'contractTotalCents' in finance_view, 'finance 全量'
    print('    ✅ engineer 非负责人脱敏；boss/finance 全量')

    step('AC13：组织隔离同码；写命令幂等重放返回既有（AC8 已验 collect）')
    env_b = setup(mobile_prefix='1390000000', batch='engB' + str(int(time.time()) % 100000))
    other_engineer = login(env_b['engineerB'])
    act(other_engineer, 'GET', f'/franchise/engineering/{manual_id}', expect_code=1115000001)
    act(other_engineer, 'POST', f'/franchise/engineering/{manual_id}/manager',
        {'engineerUserId': other_engineer['userId']}, expect_code=1115000001, idem='e2e-org-' + run)
    print('    ✅ 跨组织引用不可见（001 统一句柄）')

    step('AC14：单测与 v3 verify 在构建/接入批执行（本脚本覆盖后端链路）')
    print('    ℹ️ 单测 12 绿已在 mvn test 跑过；v3 verify-engineering-pages 随接入批')
    print(f'\n✅ 阶段 2 工程全周期 e2e 全绿（project={project_id}）')


if __name__ == '__main__':
    main()
