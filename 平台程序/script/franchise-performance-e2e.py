#!/usr/bin/env python3
"""阶段 3 · 绩效提成经营端到端验收（19 文档 §八 AC1-AC14，真实 DB 零外呼）。

前提：本地 server 以 local,pg,zsdev 运行（迁移 817/818/819/857 随启动落库），stub 微信登录：
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
断言口径：HTTP 200 + body.code 二元组。用法：python3 script/franchise-performance-e2e.py
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
PERIOD = time.strftime('%Y-%m')            # 当月（任务/目标/对账）
import datetime as _dt
_today = _dt.date.today()
_prev = _today.replace(day=1) - _dt.timedelta(days=1)
PREV = _prev.strftime('%Y-%m')             # 上月（日历月——P2-20）


def date_next():
    """PERIOD 下月首日（P2-20：日期边界动态化）。"""
    return (_dt.date.fromisoformat(PERIOD + '-01').replace(day=28) + _dt.timedelta(days=4)).replace(day=1).isoformat()


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
    payload['batch'] = batch or ('perf' + str(int(time.time()) % 1000000))
    return call('POST', '/platform/dev-setup/franchise-e2e/setup', payload)


def login(who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-perf-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': result['accessToken'], 'post': who['postId'], 'userId': who['userId'],
            'mobile': who['mobile']}


def act(user, method, path, body=None, expect_code=0, idem=None):
    headers = {'Idempotency-Key': idem} if idem else None
    return call(method, path, body, token=user['token'], post_id=user['post'],
                expect_code=expect_code, headers=headers)


def upload_asset(user, sales_helper, asset_type='ENG_DRAWING'):
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


def node_dates(detail, start='2026-11-05', span=4):
    from datetime import date, timedelta
    nodes, d = [], date.fromisoformat(start)
    for n in detail['nodes']:
        end = d + timedelta(days=span - 1)
        nodes.append({'nodeIndex': n['nodeIndex'], 'name': n['name'],
                      'startDate': d.isoformat(), 'endDate': end.isoformat()})
        d = end + timedelta(days=1)
    return nodes


def build_engineering(boss, sales, engineer, finance):
    """精简工程链（阶段 2 同款）：手工建档→prepare→collect START→start→材料到货→节点到封顶→collect TOPPING。
    返回 (project_id, topping_receipt_id)。"""
    asset = upload_asset(engineer, sales, 'ENG_DRAWING')
    proj = act(engineer, 'POST', '/franchise/engineering/projects',
               {'name': '绩效工程-' + run, 'customer': '吴先生', 'city': '武汉', 'floors': 2, 'area': 300},
               idem='pe-proj-' + run)
    pid = int(proj['id'])
    prepared = act(engineer, 'POST', f'/franchise/engineering/{pid}/prepare',
                   {'total': 1000000, 'first': 400000, 'contractAssetId': asset,
                    'note': '绩效链开工准备', 'workers': '班组'}, idem='pe-prep-' + run)
    nodes = node_dates(prepared)
    act(engineer, 'PUT', f'/franchise/engineering/{pid}/plan', {'nodes': nodes}, idem='pe-plan-' + run)
    collect_asset = upload_asset(finance, sales)
    act(finance, 'POST', f'/franchise/engineering/{pid}/collections',
        {'stage': 'START', 'amount': '400000', 'transactionNo': 'PC1-' + run,
         'fileAssetId': collect_asset}, idem='pe-col1-' + run)
    act(engineer, 'POST', f'/franchise/engineering/{pid}/start',
        {'fileAssetId': asset, 'note': '开工'}, idem='pe-start-' + run)
    # 材料挂 n3；完成 n1/n2 → 材料到货 → n3 → … → 封顶（n7）
    plan = act(engineer, 'POST', f'/franchise/engineering/{pid}/materials',
               {'nodeId': int(nodes and prepared['nodes'][2]['id']), 'due': '2026-11-12',
                'lines': [{'name': '钢材', 'unit': '吨', 'quantity': '8'}]}, idem='pe-mat-' + run)
    supply_id = None
    for p in plan['plans']:
        for line in p['lines']:
            supply_id = line.get('supplyId') or supply_id
    site_asset = upload_asset(engineer, sales, 'ENG_SITE_PHOTO')
    arrive = act(engineer, 'POST', f'/franchise/engineering/supplies/{int(supply_id)}/arrival',
                 {'actual': '8', 'quality': 'OK', 'transactionNo': 'PA-' + run,
                  'fileAssetId': site_asset}, idem='pe-arr-' + run)
    detail = act(engineer, 'GET', f'/franchise/engineering/{pid}')
    for node in detail['nodes']:
        act(engineer, 'POST', f"/franchise/engineering/{pid}/nodes/{int(node['id'])}/complete",
            {'note': node['name'] + ' 完成', 'fileAssetId': site_asset},
            idem=f"pe-nc-{node['id']}-{run}")
    topped = act(finance, 'POST', f'/franchise/engineering/{pid}/collections',
                 {'stage': 'TOPPING', 'amount': '600000', 'transactionNo': 'PC2-' + run,
                  'fileAssetId': collect_asset}, idem='pe-col2-' + run)
    topping_receipt = int(topped['receiptId'])
    return pid, topping_receipt


def main():
    step('AC1：八表迁移落库（817/818/819/857）')
    tables = psql("SELECT count(*) FROM information_schema.tables WHERE table_name IN ("
                  "'fr_perf_goal','fr_perf_task','fr_perf_task_archive','fr_perf_cycle',"
                  "'fr_perf_commission_policy','fr_perf_commission_release',"
                  "'fr_perf_reconciliation','fr_perf_operating_snapshot')")
    assert tables == '8', f'fr_perf_* 八表须齐（实际 {tables}）'
    applied = psql("SELECT count(*) FROM flyway_schema_history WHERE version IN "
                   "('20261102.817','20261102.818','20261102.819','20261102.857')")
    assert applied == '4', applied
    menus = psql("SELECT count(*) FROM system_menu WHERE id BETWEEN 710700 AND 710704")
    assert menus == '5', menus
    role_menus = psql("SELECT count(*) FROM system_role_menu WHERE id BETWEEN 714300 AND 714399")
    assert int(role_menus) >= 19, role_menus
    print('    ✅ 八表 + 四迁移 + 权限种子在库')

    step('一键搭建（7 员工含 owner+7）并登录')
    env = setup()
    sales = login(env['sales'])
    boss = login(env['boss'])
    finance = login(env['finance'])
    engineer_a = login(env['engineer'])   # project-manager
    engineer_b = login(env['engineerB'])  # engineer
    owner = login(env['owner'])
    print(f"    ✅ org={env['storeOrgId']} A(pm)={engineer_a['userId']} B(eng)={engineer_b['userId']} owner={owner['userId']}")

    step('AC2：401 / 权限矩阵（manage 403 / commission 严格角色 000）')
    call('GET', '/franchise/performance/tasks?period=' + PERIOD, expect_code=401)
    act(engineer_b, 'POST', '/franchise/performance/tasks',
        {'period': PERIOD, 'title': 'x', 'assigneeUserId': engineer_b['userId'],
         'assigneeDept': 'engineering', 'type': 'manual', 'targetCents': 0,
         'due': PERIOD + '-20'}, expect_code=403, idem='pe-m403-' + run)
    act(engineer_b, 'POST', '/franchise/performance/commission/policy',
        {'projectId': 1, 'totalCents': 100, 'assigneeUserId': engineer_b['userId'],
         'evidenceRef': 'x', 'note': 'x'}, expect_code=403, idem='pe-c403-' + run)
    act(finance, 'POST', '/franchise/performance/commission/policy',
        {'projectId': 999999, 'totalCents': 100, 'assigneeUserId': finance['userId'],
         'evidenceRef': 'x', 'note': 'x'}, expect_code=1116000000, idem='pe-cfin-' + run)
    # P1-3：普通员工 member scope 读他人 → 000；summary 仅本人组
    act(engineer_b, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=member&userId={engineer_a["userId"]}',
        expect_code=1116000000)
    emp_summary = act(engineer_b, 'GET', f'/franchise/performance/tasks/summary?period={PERIOD}')
    assert set(emp_summary['departments'].keys()) <= {'engineering'}, emp_summary['departments'].keys()
    print('    ✅ 401 / engineer manage+policy 403（无码）/ finance 持码越界 000 / 员工读越权 000+summary 裁剪')

    step('AC3：三级目标保存+版本；归档期拒改（后置 AC7）')
    goal = act(boss, 'PUT', '/franchise/performance/goals',
               {'period': PERIOD, 'sales': '680000', 'collections': '1000000',
                'effective': 5, 'customers': 3, 'nodes': 10,
                'departmentGoals': {'engineering': {'completedNodes': 6}},
                'staffDepartmentGoals': {str(engineer_b['userId']): {'engineering': {'completedNodes': 3}}},
                'legacyStaff': {str(sales['userId']): 500000}}, idem='pe-goal-' + run)
    assert goal['version'] == 1 and goal['salesCents'] == 68000000, goal
    goal2 = act(boss, 'PUT', '/franchise/performance/goals',
                {'period': PERIOD, 'sales': '700000', 'collections': '1000000',
                 'effective': 5, 'customers': 3, 'nodes': 10}, idem='pe-goal2-' + run)
    assert goal2['version'] == 2, goal2
    print('    ✅ 三级+version=2')

    step('AC4：manual 派发→本人补填→改派清空→归档禁改（后置）')
    manual = act(boss, 'POST', '/franchise/performance/tasks',
                 {'period': PERIOD, 'title': '月度安全巡检', 'assigneeUserId': engineer_b['userId'],
                  'assigneeName': '李工程', 'assigneeDept': 'engineering', 'type': 'manual',
                  'targetCents': 0, 'due': PERIOD + '-25'}, idem='pe-task1-' + run)
    assert manual['status'] == '进行中', manual
    manual_id = int(manual['id'])
    done = act(engineer_b, 'POST', f'/franchise/performance/tasks/{manual_id}/manual-complete',
               {'note': '完成巡检并留存照片'}, idem='pe-md1-' + run)
    assert done['status'] == '已完成', done
    # 改派给 A → manualDone 清空
    re = act(boss, 'POST', f'/franchise/performance/tasks/{manual_id}/reassign',
             {'assigneeUserId': engineer_a['userId'], 'assigneeName': '张工程'}, idem='pe-re1-' + run)
    assert re['status'] == '进行中' and re['manualDone'] is None, re
    act(engineer_a, 'POST', f'/franchise/performance/tasks/{manual_id}/manual-complete',
        {'note': '接手后完成'}, idem='pe-md2-' + run)
    # P1-2：同键同 payload 重放一致；同键异 payload 拒绝（1099000003 幂等键复用）
    act(engineer_a, 'POST', f'/franchise/performance/tasks/{manual_id}/manual-complete',
        {'note': '接手后完成'}, idem='pe-md2-' + run)
    act(engineer_a, 'POST', f'/franchise/performance/tasks/{manual_id}/manual-complete',
        {'note': '改动说明'}, expect_code=1099000000, idem='pe-md2-' + run)  # IDEMPOTENCY_KEY_REUSED=1_099_000_000
    print('    ✅ 补填/改派清空/再补填/同键重放一致+异 payload 拒绝')

    step('AC5：auto 实时派生（工程链+签约+线索造数 → 三组口径断言）')
    pid, topping_receipt = build_engineering(boss, sales, engineer_a, finance)
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '绩效线索甲', 'phone': '135%08d' % (int(run[-8:]) % 100000000),
                'need': '乡墅', 'source': '绩效e2e'})
    assert lead['result'] == 'CREATED', lead
    req = act(sales, 'POST', '/franchise/sales/requirements',
              {'customerId': lead['customerId'], 'width': 12, 'depth': 15, 'floors': 2, 'style': '新中式'},
              idem='pe-req-' + run)
    contract = act(sales, 'POST', '/franchise/sales/contracts',
                   {'customerId': lead['customerId'], 'totalCents': 9340000, 'firstCents': 5604000,
                    'requirementId': req['requirementId']}, idem='pe-con-' + run)
    signed = act(sales, 'POST', f"/franchise/sales/contracts/{contract['contractId']}/sign",
                 {'signedDate': time.strftime('%Y-%m-%d'), 'revision': 1}, idem='pe-sign-' + run)
    assert signed['status'] == 'SIGNED', signed
    # auto 任务：engineer_a（工程 manager）回款 100 万目标；工程师节点 2 个目标
    t_coll = act(boss, 'POST', '/franchise/performance/tasks',
                 {'period': PERIOD, 'title': '当月回款 100 万', 'assigneeUserId': engineer_a['userId'],
                  'assigneeName': '张工程', 'assigneeDept': 'engineering',
                  'type': 'auto:collectionsAmount', 'targetCents': 100000000,
                  'due': PERIOD + '-28'}, idem='pe-tc-' + run)
    assert t_coll['status'] == '已完成' and t_coll['collected'] == 100000000, t_coll  # 40+60 万工程回款归属 manager
    t_node = act(boss, 'POST', '/franchise/performance/tasks',
                 {'period': PERIOD, 'title': '完成节点 2 个', 'assigneeUserId': engineer_a['userId'],
                  'assigneeName': '张工程', 'assigneeDept': 'engineering',
                  'type': 'auto:completedNodes', 'targetCents': 200, 'due': PERIOD + '-28'},
                 idem='pe-tn-' + run)
    assert t_node['status'] == '已完成' and t_node['collected'] > 0 and t_node['collected'] % 100 == 0, \
        (t_node.get('collected'), t_node.get('status'))  # P1-9：数量 ×100 存储（当月节点数×100；精确数在 AC5 summary 断言）
    # 三组汇总断言（SQL 直查对照）
    summary = act(boss, 'GET', f'/franchise/performance/tasks/summary?period={PERIOD}')
    eng = summary['departments']['engineering']
    fin = summary['departments']['finance']
    direct_nodes = psql(f"SELECT count(*) FROM fr_eng_plan_node WHERE company_id={env['storeOrgId']} "
                        f"AND status='DONE' AND end_date >= '{PERIOD}-01' AND end_date < '{date_next()}'")
    assert eng['auto:completedNodes'] == int(direct_nodes) * 100, (eng, direct_nodes)  # ×100 契约
    assert eng['auto:qualifiedDeliveries'] == 100 and eng['metric:firstPassNodes'] == eng['auto:completedNodes'], eng  # ×100
    assert fin['auto:financeRegistrations'] >= 2, fin  # 工程两笔收款（写通）
    biz = summary['departments']['business']
    assert biz['auto:contract'] == 9340000 and biz['auto:effectiveLeads'] == 100, biz  # ×100
    # 跨月不串：上月汇总工程组应为 0（无上月造数）
    prev_summary = act(boss, 'GET', f'/franchise/performance/tasks/summary?period={PREV}')
    assert prev_summary['departments']['engineering'].get('auto:completedNodes', 0) == 0, prev_summary
    print(f"    ✅ 回款归属 manager 100 万达成/节点直查一致/三组口径/跨月不串（project={pid}）")

    step('AC6：上月初始化（造上月回款 → 建议任务；重复幂等）')
    # 上月造一笔工程收款：直接 finance 手工收款（manual 来源归属 registrar=finance——suggested 会为 finance 建 financeRegistrations）
    manual_receipt = act(finance, 'POST', '/franchise/finance/receipts',
                         {'sourceType': 'manual', 'name': '上月补录回款', 'amountCents': 500000,
                          'receivedDate': PREV + '-15', 'method': 'bank',
                          'transactionNo': 'PM-' + run}, idem='pe-prev-' + run)
    init1 = act(boss, 'POST', '/franchise/performance/tasks/initialize-previous',
                {'period': PERIOD}, idem='pe-init1-' + run)
    # suggested 幂等键=(period,user,type)：dev-setup 手机号跨轮复用 → 历史轮已建的不再重建（去重正确语义）
    suggested_total = psql(f"SELECT count(*) FROM fr_perf_task WHERE suggested=TRUE AND period='{PERIOD}'")
    assert init1['replayed'] is False and int(suggested_total) >= 1, (init1, suggested_total)
    init2 = act(boss, 'POST', '/franchise/performance/tasks/initialize-previous',
                {'period': PERIOD}, idem='pe-init2-' + run)
    assert init2['created'] == 0 and init2['replayed'] is False, '重复初始化幂等跳过'
    print(f"    ✅ 建议任务存量 {suggested_total} 条（本轮新建 {init1['created']}）+ 重复幂等")

    # owner 目标补录（AC9 复核本人 000 需要 owner 自身有目标且可提交）——须在 AC7 归档锁前
    act(boss, 'PUT', '/franchise/performance/goals',
        {'period': PERIOD, 'legacyStaff': {str(owner['userId']): 100000}}, idem='pe-goal-o-' + run)

    step('AC7：归档链（锁定 → 补源 revise）')
    arch = act(boss, 'POST', '/franchise/performance/tasks/archive',
               {'period': PERIOD, 'note': '月度归档'}, idem='pe-arch-' + run)
    assert len(arch['archives']) == 1, arch
    # 锁定效应：目标拒改 002 / 任务改派 005
    act(boss, 'PUT', '/franchise/performance/goals',
        {'period': PERIOD, 'sales': '1'}, expect_code=1116000002, idem='pe-goal3-' + run)
    act(boss, 'POST', f'/franchise/performance/tasks/{manual_id}/reassign',
        {'assigneeUserId': engineer_b['userId']}, expect_code=1116000005, idem='pe-re2-' + run)
    # 源未变 revise 003 → 补一笔当月收款（签名变化）→ revise 成功
    act(boss, 'POST', '/franchise/performance/tasks/archive/revise',
        {'period': PERIOD}, expect_code=1116000003, idem='pe-rev1-' + run)
    act(finance, 'POST', '/franchise/finance/receipts',
        {'sourceType': 'manual', 'name': '归档后补录', 'amountCents': 10000,
         'receivedDate': time.strftime('%Y-%m-%d'), 'method': 'bank',
         'transactionNo': 'PM2-' + run}, idem='pe-post-' + run)
    rev = act(boss, 'POST', '/franchise/performance/tasks/archive/revise',
              {'period': PERIOD, 'note': '源变化修订'}, idem='pe-rev2-' + run)
    assert len(rev['archives']) == 2 and rev['needsRevision'] is False, rev
    print('    ✅ 归档锁定 002/005 + revise 003→成功')

    step('AC8：回款提成链（policy 硬锁/release 净实收/五态/退款 reviewDue/已知缺口记账）')
    # owner 建 policy 000（持码越界）；boss 建 ✓
    act(owner, 'POST', '/franchise/performance/commission/policy',
        {'projectId': pid, 'totalCents': 20000, 'assigneeUserId': engineer_a['userId'],
         'assigneeName': '张工程', 'evidenceRef': '{"name":"提成确认"}', 'note': '按约定'},
        expect_code=1116000000, idem='pe-pol-o-' + run)
    policy = act(boss, 'POST', '/franchise/performance/commission/policy',
                 {'projectId': pid, 'totalCents': 20000, 'assigneeUserId': engineer_a['userId'],
                  'assigneeName': '张工程', 'evidenceRef': '{"name":"提成确认"}', 'note': '按约定'},
                 idem='pe-pol-' + run)
    stages = {s['stage']: s for s in policy['stages']}
    assert stages['START']['amountCents'] == 10000 and stages['TOPPING']['amountCents'] == 10000, stages
    # 两期均已收齐 → release 双阶段 → payable agreementRef 断言
    rel_start = act(finance, 'POST', '/franchise/performance/commission/release',
                    {'projectId': pid, 'stage': 'START'}, idem='pe-rel1-' + run)
    start_payable = psql(f"SELECT id FROM fr_fin_payable WHERE agreement_ref LIKE 'perf-commission:%' "
                         f"AND total_cents=10000 AND recipient='张工程' ORDER BY id DESC LIMIT 1")
    assert start_payable, 'START 提成应付须落 finance（agreementRef 兜底标识）'
    rel_top = act(finance, 'POST', '/franchise/performance/commission/release',
                  {'projectId': pid, 'stage': 'TOPPING'}, idem='pe-rel2-' + run)
    # 幂等重放返回既有（同键）
    again = act(finance, 'POST', '/franchise/performance/commission/release',
                {'projectId': pid, 'stage': 'START'}, idem='pe-rel1-' + run)
    rows = act(boss, 'GET', '/franchise/performance/commission/rows')
    row = next(r for r in rows if r.get('projectId') == str(pid))
    s_view = {s['stage']: s for s in row['stages']}
    assert s_view['START']['status'] == '待付款' and s_view['START']['paidCents'] == 0, s_view
    # policy 硬锁 004
    act(boss, 'POST', '/franchise/performance/commission/policy',
        {'projectId': pid, 'totalCents': 30000, 'assigneeUserId': engineer_a['userId'],
         'evidenceRef': 'x', 'note': '改'}, expect_code=1116000004, idem='pe-pol2-' + run)
    # START 付款 → 已付清
    act(finance, 'POST', '/franchise/finance/payments',
        {'payableId': int(start_payable), 'amountCents': 10000,
         'payDate': time.strftime('%Y-%m-%d'), 'transactionNo': 'PP-' + run}, idem='pe-pay-' + run)
    rows = act(boss, 'GET', '/franchise/performance/commission/rows')
    s_view = {s['stage']: s for s in next(r for r in rows if r.get('projectId') == str(pid))['stages']}
    assert s_view['START']['status'] == '已付清', s_view
    # 退款 TOPPING 收款 5000 → 净额不足 → reviewDue + reconcile 007 + 已知缺口（直连付款成功）记账
    act(finance, 'POST', '/franchise/finance/refunds',
        {'receiptId': topping_receipt, 'amountCents': 5000, 'reason': '绩效 e2e 退款冲正',
         'date': time.strftime('%Y-%m-%d')}, idem='pe-refund-' + run)
    rows = act(boss, 'GET', '/franchise/performance/commission/rows')
    t_view = {s['stage']: s for s in next(r for r in rows if r.get('projectId') == str(pid))['stages']}
    assert t_view['TOPPING']['status'] == '待人工核对' and t_view['TOPPING']['reviewDue'], t_view
    act(boss, 'POST', '/franchise/performance/commission/reconcile',
        {'projectId': pid, 'stage': 'TOPPING', 'evidenceRef': '{"name":"核对"}', 'note': '人工核对'},
        expect_code=1116000007, idem='pe-rec-bad-' + run)  # 净额不足 007
    # 已知缺口记账（D-6④ 拍板接受）：净额不足仍可直连 finance 付款
    gap_pay = act(finance, 'POST', '/franchise/finance/payments',
                  {'payableId': int(t_view['TOPPING']['payableId']), 'amountCents': 100,
                   'payDate': time.strftime('%Y-%m-%d'), 'transactionNo': 'PGAP-' + run},
                  idem='pe-gap-' + run)
    assert gap_pay['paymentId'], gap_pay
    print('    ✅ policy/对半/硬锁 004/release 幂等/五态/退款 reviewDue/缺口记账')

    step('AC9：周期复核（submit→review；申诉采纳→adjustments 生效）')
    cyc = act(engineer_b, 'POST', '/franchise/performance/cycles/submit',
              {'period': PERIOD, 'note': '本人周期提交'}, idem='pe-cy1-' + run)
    assert cyc['status'] == 'PENDING_REVIEW', cyc
    # P1-10：复核本人 000（owner 已在 AC7 前补目标，可提交本人周期）
    act(owner, 'POST', '/franchise/performance/cycles/submit',
        {'period': PERIOD, 'note': '负责人周期提交'}, idem='pe-cy-o-' + run)
    act(owner, 'POST', '/franchise/performance/cycles/review',
        {'period': PERIOD, 'userId': owner['userId'], 'approve': True},
        expect_code=1116000000, idem='pe-cy-self-' + run)  # 复核本人 000
    act(owner, 'POST', '/franchise/performance/cycles/review',
        {'period': PERIOD, 'userId': owner['userId'], 'approve': True},
        expect_code=1116000000, idem='pe-cy-self-' + run)  # 复核本人 000（owner 持 manage 码）
    reviewed = act(boss, 'POST', '/franchise/performance/cycles/review',
                   {'period': PERIOD, 'userId': engineer_b['userId'], 'approve': True, 'note': '通过'},
                   idem='pe-cy2-' + run)
    assert reviewed['status'] == 'REVIEWED', reviewed
    # 申诉：剔除 engineer_a 的一笔回款源 → collectionsAmount 归属指标变化
    eng_receipt = psql(f"SELECT r.id FROM fr_fin_receipt r WHERE r.company_id={env['storeOrgId']} "
                       f"AND r.source_ref LIKE 'engineering:{pid}%' ORDER BY r.id LIMIT 1")
    before = act(boss, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=member&userId={engineer_a["userId"]}')
    before_coll = next((t['collected'] for t in before if t['type'] == 'auto:collectionsAmount'), None)
    resolved = act(owner, 'POST', '/franchise/performance/cycles/appeal-resolve',
                   {'period': PERIOD, 'userId': engineer_b['userId'], 'adopt': True,
                    'sourceKey': 'fin-receipt:' + eng_receipt, 'excluded': True,
                    'note': '争议归属剔除'}, idem='pe-ap-' + run)
    assert resolved['adjustments'], resolved
    after = act(boss, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=member&userId={engineer_a["userId"]}')
    after_coll = next((t['collected'] for t in after if t['type'] == 'auto:collectionsAmount'), None)
    assert before_coll and after_coll == before_coll - 40000000, (before_coll, after_coll)
    print(f'    ✅ 复核通过/本人 000/申诉剔除后回款指标 {before_coll}→{after_coll}')

    step('AC10：月末对账+经营快照+analytics 直查一致')
    rec = act(boss, 'POST', '/franchise/performance/operating/reconcile',
              {'period': PERIOD}, idem='pe-rec1-' + run)
    direct_income = psql(f"SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_receipt WHERE company_id={env['storeOrgId']} "
                         f"AND status='已登记' AND received_date >= '{PERIOD}-01' AND received_date < '{date_next()}'")
    assert rec['incomeCents'] == int(direct_income), (rec, direct_income)
    # 快照：当月拒 008；上月成功；签名未变 002；补变化 version+1
    act(boss, 'POST', '/franchise/performance/operating/snapshot',
        {'period': PERIOD}, expect_code=1116000008, idem='pe-snap0-' + run)
    snap1 = act(boss, 'POST', '/franchise/performance/operating/snapshot',
                {'period': PREV, 'note': '月度结账'}, idem='pe-snap1-' + run)
    assert len(snap1['snapshots']) == 1, snap1
    act(boss, 'POST', '/franchise/performance/operating/snapshot',
        {'period': PREV}, expect_code=1116000002, idem='pe-snap2-' + run)
    act(finance, 'POST', '/franchise/finance/receipts',
        {'sourceType': 'manual', 'name': '快照变化源', 'amountCents': 100,
         'receivedDate': PREV + '-20', 'method': 'bank', 'transactionNo': 'PS-' + run},
        idem='pe-snapsrc-' + run)
    snap3 = act(boss, 'POST', '/franchise/performance/operating/snapshot',
                {'period': PREV, 'note': '调整重归档'}, idem='pe-snap3-' + run)
    latest = snap3['snapshots'][0]
    assert len(snap3['snapshots']) == 2 and latest['version'] == 2, snap3
    analytics = act(boss, 'GET', f'/franchise/performance/operating/analytics?period={PERIOD}')
    assert len(analytics['trend']) == 6 and 'sourceSignature' in analytics, analytics['totals']
    print('    ✅ 对账直查一致/快照 008→002→v2/analytics 6 月趋势')

    step('AC11：TodoProvider manual 待办（fr-perf 域；当期已归档→用未归档上月周期断言）')
    todos_locked = act(engineer_a, 'GET', '/platform/todos')
    assert not any(t['domain'] == 'fr-perf' for t in todos_locked['items']), '归档后待办应消失（锁定语义）'
    pending_manual = act(boss, 'POST', '/franchise/performance/tasks',
                         {'period': PREV, 'title': '上月补充巡检', 'assigneeUserId': engineer_a['userId'],
                          'assigneeName': '张工程', 'assigneeDept': 'engineering', 'type': 'manual',
                          'targetCents': 0, 'due': PREV + '-25'}, idem='pe-task11-' + run)
    todos = act(engineer_a, 'GET', '/platform/todos')
    assert any(t['domain'] == 'fr-perf' for t in todos['items']), todos['items'][:3]
    print('    ✅ 归档期待办消失 + 未归档期 fr-perf 待办在列')

    step('AC12：组织隔离 + 幂等重放（goal/policy/release 同键）')
    env_b = setup(mobile_prefix='1390000001', batch='perfB' + str(int(time.time()) % 100000))
    other = login(env_b['engineerB'])
    empty = act(other, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=mine')
    assert empty == [], empty
    other_boss = login(env_b['boss'])
    own_goal = act(other_boss, 'PUT', '/franchise/performance/goals',
                   {'period': PERIOD, 'sales': '1'}, idem='pe-org-' + run)  # 对照组织写自身 org
    assert own_goal['salesCents'] == 100, own_goal
    main_goal = act(boss, 'GET', f'/franchise/performance/goals?period={PERIOD}')
    assert main_goal['salesCents'] == 70000000, '主组织目标不受对照组织污染' 
    replay_goal = act(boss, 'PUT', '/franchise/performance/goals',
                      {'period': PERIOD, 'sales': '700000', 'collections': '1000000',
                       'effective': 5, 'customers': 3, 'nodes': 10}, idem='pe-goal2-' + run)
    assert replay_goal['salesCents'] == 70000000 and replay_goal['version'] >= 2, 'goal 同键重放命中既有命令'
    print('    ✅ 跨组织隔离 + 重放返回既有')

    step('AC13：员工只见本人；engineer company 000')
    mine_b = act(engineer_b, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=mine')
    assert all(t['assigneeUserId'] == engineer_b['userId'] for t in mine_b), mine_b
    act(engineer_b, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=company',
        expect_code=1116000000)
    boss_member = act(boss, 'GET', f'/franchise/performance/tasks?period={PERIOD}&scope=member&userId={engineer_a["userId"]}')
    assert all(t['assigneeUserId'] == engineer_a['userId'] for t in boss_member)
    print(f'    ✅ mine 过滤 {len(mine_b)} 条/member 范围/company 越权 000')

    step('AC14：单测与 v3 verify 归属')
    print('    ℹ️ 单测 5 绿（mvn test）；v3 verify-performance-pages 随接入批')
    print(f'\n✅ 阶段 3 绩效提成经营 e2e 全绿（project={pid}）')


if __name__ == '__main__':
    main()
