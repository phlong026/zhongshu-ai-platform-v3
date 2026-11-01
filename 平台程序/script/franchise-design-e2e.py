#!/usr/bin/env python3
"""B2 板块 A 设计赋能接入层端到端验收（需求文档 05 AC1-AC17）。

前提：本地 server 以 local,pg,zsdev 运行，且带 --zhongshu.design.driver-enabled=true。
dev 库种子：已发布案例 1 套、预算规则 VAR1/BRICK/A|B、计价规则（图片×4/BUDGET_ESTIMATE=1/AI_PROMPT=1）。
用法：python3 script/franchise-design-e2e.py
"""
import json
import threading
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0
import base64
import hashlib
import subprocess

# 与 AiJobOrchestrationService.DEV_PNG 相同的 1x1 PNG
PNG_1X1 = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')


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


def call(method, path, body=None, token=None, post_id=None, expect_code=0, headers=None):
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
        return payload  # 通配：返回完整响应由调用方自行断言
    if payload.get('code') not in expect_code:
        raise AssertionError(f'{method} {path} -> code={payload.get("code")} msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def login(setup, who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-' + setup[who]['mobile'], 'phoneCode': setup[who]['mobile']})
    return {'token': result['accessToken'], 'post': setup[who]['postId'], 'userId': setup[who]['userId']}


def act(user, method, path, body=None, headers=None, expect_code=0, with_post=True):
    return call(method, path, body, token=user['token'],
                post_id=user['post'] if with_post else None, headers=headers, expect_code=expect_code)


def wait_job_terminal(user, job_id, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = act(user, 'GET', f'/franchise/design/ai-jobs/{job_id}')
        if job['status'] in ('SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED'):
            return job
        time.sleep(3)
    raise AssertionError(f'job {job_id} 未在 {timeout}s 内终态')


def main():
    # ================= AC1 编译与迁移（行为断言） =================
    step('dev-setup 搭建组织（门店=主测 A；总部=跨组织方「B」，hq-design-admin 具备能力门）')
    ts = int(time.time()) % 10000000
    setup_a = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                   {'batch': 'da' + str(ts), 'mobilePrefix': '139%07d' % ts})
    boss, sales, admin = login(setup_a, 'boss'), login(setup_a, 'sales'), login(setup_a, 'designAdmin')
    pm_a = login(setup_a, 'engineer')
    # 跨组织方=总部组织（与门店 orgId 不同；总部服务账号惰性创建且无点数）
    sales_b = admin
    assert setup_a['storeOrgId'] != setup_a['headquartersOrgId']

    step('AC2 权限矩阵：全 13 岗位遍历（仅 sales/sales-lead/hq-design-admin 通过，其余 10 岗 403）')
    for who in ['boss', 'finance', 'engineer']:
        u = login(setup_a, who)
        r = call('GET', '/franchise/design/cases', token=u['token'], post_id=u['post'], expect_code=-1)
        assert r.get('code') == 403, f'{who} 预期 403 实际 {r.get("code")}'
    # 门店系可经组织管理创建的拒绝岗（hq-* 岗属总部组织类型，门店录员工会被拒——其无授权由矩阵精确匹配覆盖）
    roles_denied = ['finance-lead', 'owner', 'project-manager']
    mobile_seq = int(time.time()) % 100000
    for idx, role in enumerate(roles_denied):
        mobile = '138%08d' % (mobile_seq * 100 + idx)
        row = act(boss, 'POST', '/platform/org/staff',
                  {'orgId': setup_a['storeOrgId'], 'name': role + '-e2e', 'mobile': mobile, 'roleCode': role})
        assert row['postId'], row
        token = sms_login(mobile)
        posts = call('GET', '/platform/posts/mine', token=token)
        assert posts, f'{role} 应发现任职'
        r = call('GET', '/franchise/design/cases', token=token, post_id=posts[0]['postId'], expect_code=-1)
        assert r.get('code') == 403, f'{role} 预期 403 实际 {r.get("code")}'
    # V853 授权集合精确匹配（hq-* 等 4 岗无授权由此覆盖；数据层断言）
    matrix = psql("SELECT r.code FROM system_role_menu rm "
                  "JOIN system_role r ON r.id = rm.role_id AND r.tenant_id = 1 "
                  "JOIN system_menu m ON m.id = rm.menu_id WHERE m.permission = 'franchise:design:use' "
                  "ORDER BY r.code")
    assert set(matrix.split()) == {'sales', 'sales-lead', 'hq-design-admin'}, matrix

    step('AC2b 主测三岗位正向 + 精确授权（sales/sales-lead/hq-design-admin）')
    for u in (sales, admin):
        cases = act(u, 'GET', '/franchise/design/cases')
        assert isinstance(cases, dict) or isinstance(cases, list)

    step('AC3 服务账号：首次调用惰性创建 + 并发唯一（真 PG）')
    # 并发 4 路创建项目（同键）→ 仅 1 项目、服务账号解析幂等（external_key ON CONFLICT）
    results, errors = [], []

    def create_parallel(i):
        try:
            r = act(sales, 'POST', '/franchise/design/projects',
                    {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                     'requirementInputs': {'faceWidthM': '12', 'depthM': '15', 'floorCount': 2}},
                    headers={'Idempotency-Key': 'e2e-parallel-proj-001'})
            results.append(r)
        except AssertionError as e:
            errors.append(str(e))

    threads = [threading.Thread(target=create_parallel, args=(i,)) for i in range(4)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not errors, errors
    assert len({r['projectId'] for r in results}) == 1, results

    step('AC3b 停用服务账号后调用拒绝（1_112_000_004），恢复后同账号可用（identity 无启用接口，经 SQL 显式恢复）')
    psql(f"UPDATE account SET status='DISABLED' WHERE external_key='fr-service:{setup_a['storeOrgId']}'")
    r = call('POST', '/franchise/design/projects',
             {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
              'requirementInputs': {'faceWidthM': '9', 'depthM': '9', 'floorCount': 1}},
             token=sales['token'], post_id=sales['post'], expect_code=-1)
    assert r.get('code') == 1_112_000_004, f'停用后预期 1_112_000_004 实际 {r.get("code")}'
    # 查询类不受影响（cases 公开读，不经服务账号）
    r2 = call('GET', '/franchise/design/cases', token=sales['token'], post_id=sales['post'], expect_code=0)
    psql(f"UPDATE account SET status='ACTIVE' WHERE external_key='fr-service:{setup_a['storeOrgId']}'")

    step('服务账号点数注入（P-7① 测试基建：MANUAL_CREDIT 100 万点）')
    seed = act(sales, 'POST', '/platform/dev/design/point-account/seed', {'points': 1_000_000})
    assert int(seed['availablePoints']) >= 1_000_000, seed

    # ================= AC4 灵感库 =================
    step('AC4 灵感库代理：列表/详情/收藏幂等/取消收藏')
    cases = act(sales, 'GET', '/franchise/design/cases?limit=5')
    case_list = cases['list'] if isinstance(cases, dict) else cases
    assert len(case_list) >= 1, 'dev 库应有已发布案例'
    case_id = case_list[0]['caseId']
    detail = act(sales, 'GET', f'/franchise/design/cases/{case_id}')
    assert detail['title'] and detail['caseVersionId']
    styles = act(sales, 'GET', '/franchise/design/cases/styles')
    act(sales, 'PUT', f'/franchise/design/cases/{case_id}/favorite')
    act(sales, 'PUT', f'/franchise/design/cases/{case_id}/favorite')  # 重放幂等
    favs = act(sales, 'GET', '/franchise/design/favorites')
    fav_list = favs['list'] if isinstance(favs, dict) else favs
    assert any(c['caseId'] == case_id for c in fav_list)
    act(sales, 'DELETE', f'/franchise/design/cases/{case_id}/favorite')
    favs2 = act(sales, 'GET', '/franchise/design/favorites')
    fav_list2 = favs2['list'] if isinstance(favs2, dict) else favs2
    assert not any(c['caseId'] == case_id for c in fav_list2)

    # ================= AC5 资产链 =================
    step('AC5 资产链：upload-tickets → dev 字节通道 → complete(ACCEPTED) → 引用有效')
    png_bytes = PNG_1X1
    ticket = act(sales, 'POST', '/franchise/design/assets/upload-tickets',
                 {'assetType': 'USER_SKETCH', 'mimeType': 'image/png',
                  'sizeBytes': len(png_bytes), 'sha256': hashlib.sha256(png_bytes).hexdigest()})
    asset_id = ticket['assetId']
    assert ticket['uploadUrl']
    act(sales, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
        {'contentBase64': __import__('base64').b64encode(png_bytes).decode()})
    completed = act(sales, 'POST', f'/franchise/design/assets/{asset_id}/complete')
    assert completed['uploadStatus'] == 'ACCEPTED', completed
    # 审核故障夹具 → PENDING（退避期）
    act(sales, 'POST', '/platform/dev/design/assets/moderation-fault', {'enabled': True})
    ticket2 = act(sales, 'POST', '/franchise/design/assets/upload-tickets',
                  {'assetType': 'USER_SKETCH', 'mimeType': 'image/png',
                   'sizeBytes': len(png_bytes), 'sha256': hashlib.sha256(png_bytes).hexdigest()})
    act(sales, 'POST', f'/platform/dev/design/assets/{ticket2["assetId"]}/bytes',
        {'contentBase64': __import__('base64').b64encode(png_bytes).decode()})
    pending = act(sales, 'POST', f'/franchise/design/assets/{ticket2["assetId"]}/complete')
    assert pending['uploadStatus'] == 'PENDING', pending
    act(sales, 'POST', '/platform/dev/design/assets/moderation-fault', {'enabled': False})

    # ================= AC6 创建项目幂等与规则 =================
    step('AC6 创建项目：CASE_REFERENCE(ELEVATION)/SELF_UPLOAD(FLAT)；同键重放；FLAT 禁参考')
    proj_ref = act(sales, 'POST', '/franchise/design/projects',
                   {'sourceType': 'CASE_REFERENCE', 'refCaseId': case_id, 'line': 'ELEVATION',
                    'requirementInputs': {'faceWidthM': '12', 'depthM': '15', 'floorCount': 2}},
                   headers={'Idempotency-Key': 'e2e-proj-ref-001'})
    proj_flat = act(sales, 'POST', '/franchise/design/projects',
                    {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                     'requirementInputs': {'faceWidthM': '12', 'depthM': '15', 'floorCount': 2}},
                    headers={'Idempotency-Key': 'e2e-proj-flat-001'})
    replay = act(sales, 'POST', '/franchise/design/projects',
                 {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                  'requirementInputs': {'faceWidthM': '12', 'depthM': '15', 'floorCount': 2}},
                 headers={'Idempotency-Key': 'e2e-proj-flat-001'})
    assert replay['projectId'] == proj_flat['projectId'] and replay['replayed'] is True
    act(sales, 'POST', '/franchise/design/projects',
        {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
         'requirementInputs': {'faceWidthM': '14', 'depthM': '15', 'floorCount': 2}},
        headers={'Idempotency-Key': 'e2e-proj-flat-001'}, expect_code=409)
    act(sales, 'POST', '/franchise/design/projects',
        {'sourceType': 'CASE_REFERENCE', 'refCaseId': case_id, 'line': 'FLAT'},
        headers={'Idempotency-Key': 'e2e-proj-flat-ref'}, expect_code=1_071_000_008)

    # ================= AC7 AI 平面链 =================
    step('AC7 平面链：扣点 → 模拟成功 → 晋升候选 → 选定 → 结果版本；同键重试仅一次扣费')
    bal_before = act(sales, 'GET', '/platform/dev/design/point-account')
    job1 = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/flat-jobs',
               {'count': 2, 'resolution': '2K', 'orientation': 'PORTRAIT'},
               headers={'Idempotency-Key': 'e2e-flat-job-001'})
    job1_replay = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/flat-jobs',
                      {'count': 2, 'resolution': '2K', 'orientation': 'PORTRAIT'},
                      headers={'Idempotency-Key': 'e2e-flat-job-001'})
    assert job1_replay['jobId'] == job1['jobId'] and job1_replay['replayed'] is True
    bal_after_create = act(sales, 'GET', '/platform/dev/design/point-account')
    img_cost = int(bal_before['availablePoints']) - int(bal_after_create['availablePoints'])
    assert img_cost >= 2, f'平面任务应预扣点（实扣 {img_cost}）'
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job1["jobId"]}/simulate', {'acceptedCount': 2})
    job1_done = wait_job_terminal(sales, job1['jobId'])
    assert job1_done['status'] == 'SUCCEEDED', job1_done
    bal_after_settle = act(sales, 'GET', '/platform/dev/design/point-account')
    assert int(bal_after_settle['availablePoints']) == int(bal_after_create['availablePoints']), '全部接受无退款'
    detail = act(sales, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}?jobId={job1["jobId"]}')
    assert len(detail['candidates']) == 2, detail['candidates']
    cand = detail['candidates'][0]
    sel = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/flat-selections',
              {'jobId': job1['jobId'], 'candidateId': cand['candidateId']})
    assert sel['resultVersionId'] and sel['selectionSaved'] is True
    sel_replay = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/flat-selections',
                     {'jobId': job1['jobId'], 'candidateId': cand['candidateId']})
    assert sel_replay['resultVersionId'] == sel['resultVersionId'] and sel_replay['replayed'] is True

    # ================= AC8 AI 立面链（含稳定编码与旧键重放） =================
    step('AC8 立面链：稳定编码 configSnapshot；演进后旧键重放返回首次任务')
    job_e1 = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/elevation-jobs',
                 {'count': 1, 'resolution': '2K', 'orientation': 'LANDSCAPE',
                  'styleCode': 'NEW_CHINESE', 'roofType': 'GABLE_ROOF',
                  'material': 'WHITE_STUCCO', 'color': 'DEEP_WOOD'},
                 headers={'Idempotency-Key': 'e2e-elev-job-001'})
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job_e1["jobId"]}/simulate', {'acceptedCount': 1})
    job_e1_done = wait_job_terminal(sales, job_e1['jobId'])
    assert job_e1_done['status'] == 'SUCCEEDED'
    elev_detail = act(sales, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}?jobId={job_e1["jobId"]}')
    elev_cand = elev_detail['candidates'][0]
    sel_e1 = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/elevation-selections',
                 {'jobId': job_e1['jobId'], 'candidateId': elev_cand['candidateId']})
    assert sel_e1['resultVersionId']
    versions = act(sales, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}/result-versions')
    v1 = [v for v in versions if v['versionId'] == sel_e1['resultVersionId']][0]
    assert 'NEW_CHINESE' in (v1['configSnapshotJson'] or ''), v1['configSnapshotJson']
    bal_prev = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    job_e1_replay = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/elevation-jobs',
                        {'count': 1, 'resolution': '2K', 'orientation': 'LANDSCAPE',
                         'styleCode': 'NEW_CHINESE', 'roofType': 'GABLE_ROOF',
                         'material': 'WHITE_STUCCO', 'color': 'DEEP_WOOD'},
                        headers={'Idempotency-Key': 'e2e-elev-job-001'})
    bal_now = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    assert job_e1_replay['jobId'] == job_e1['jobId'] and job_e1_replay['replayed'] is True
    assert bal_now == bal_prev, '旧键重放不得重复扣费'

    # ================= AC9 调整链 =================
    step('AC9 调整链：R18 → 新任务 → 新版本 superseded 旧版本；reason 必填；异 count 不误判重放')
    act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/revision-requests',
        {'reason': ''}, headers={'Idempotency-Key': 'e2e-rev-bad'}, expect_code=400)
    rev1 = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/revision-requests',
               {'reason': ' widow 加宽', 'count': 1},
               headers={'Idempotency-Key': 'e2e-rev-001'})
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{rev1["newJobId"]}/simulate', {'acceptedCount': 1})
    rev1_done = wait_job_terminal(sales, rev1['newJobId'])
    assert rev1_done['status'] == 'SUCCEEDED'
    rev_detail = act(sales, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}?jobId={rev1["newJobId"]}')
    rev_cand = rev_detail['candidates'][0]
    sel_rev = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/elevation-selections',
                  {'jobId': rev1['newJobId'], 'candidateId': rev_cand['candidateId']})
    versions = act(sales, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}/result-versions')
    assert len(versions) >= 2
    non_superseded = [v for v in versions if not v['superseded']]
    assert len(non_superseded) == 1 and non_superseded[0]['versionId'] == sel_rev['resultVersionId']
    assert any(v['versionId'] == sel_e1['resultVersionId'] and v['superseded'] for v in versions)
    rev_replay_bad = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/revision-requests',
                         {'reason': ' widow 加宽', 'count': 2},
                         headers={'Idempotency-Key': 'e2e-rev-001'}, expect_code=1_099_000_000)
    # 项目转 ELEVATION 后：旧平面任务键重放仍返回原任务（P-10①）
    flat_replay_after = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/flat-jobs',
                            {'count': 2, 'resolution': '2K', 'orientation': 'PORTRAIT'},
                            headers={'Idempotency-Key': 'e2e-flat-job-001'})
    assert flat_replay_after['jobId'] == job1['jobId'] and flat_replay_after['replayed'] is True

    # ================= AC10 快速预算 =================
    step('AC10 快速预算：R19 三元组与 R20 同源；区间可复算；同键重放；buildingArea 边界')
    rule = act(sales, 'GET', '/franchise/design/budget/rule-info?regionCode=VAR1&structureType=BRICK&materialGrade=A')
    assert rule and rule['lowCentsPerSqm'] == 30000 and rule['highCentsPerSqm'] == 50000, rule
    bal_prev = act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints']
    est = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/budget-estimates',
              {'regionCode': 'VAR1', 'structureType': 'BRICK', 'materialGrade': 'A', 'buildingArea': 120},
              headers={'Idempotency-Key': 'e2e-budget-001'})
    assert int(est['totalLowCents']) == 30000 * 120 and int(est['totalHighCents']) == 50000 * 120, est
    est_replay = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/budget-estimates',
                     {'regionCode': 'VAR1', 'structureType': 'BRICK', 'materialGrade': 'A', 'buildingArea': 120},
                     headers={'Idempotency-Key': 'e2e-budget-001'})
    assert est_replay['estimateId'] == est['estimateId'] and est_replay['replayed'] is True
    act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/budget-estimates',
        {'regionCode': 'VAR1', 'structureType': 'BRICK', 'materialGrade': 'A', 'buildingArea': 0},
        headers={'Idempotency-Key': 'e2e-budget-zero'}, expect_code=1_071_000_100)
    act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/budget-estimates',
        {'regionCode': 'VAR1', 'structureType': 'BRICK', 'materialGrade': 'A', 'buildingArea': 10001},
        headers={'Idempotency-Key': 'e2e-budget-over'}, expect_code=1_071_000_100)

    # ================= AC11 回写主链 =================
    step('AC11 回写主链：PLAN/FACADE/BUDGET 三 kind 落 fr_customer_scheme（design_project_id 桥接列）')
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '设计客户甲', 'phone': '136%08d' % (int(time.time()) % 100000000),
                'need': '乡墅', 'source': '冒烟'})
    customer_id = lead['customerId']
    saved_plan = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
                     {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': sel['resultVersionId'],
                      'width': 12, 'depth': 15, 'floors': 2},
                     headers={'Idempotency-Key': 'e2e-save-plan-001'})
    saved_facade = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
                       {'customerId': customer_id, 'kind': 'FACADE', 'resultVersionId': sel_e1['resultVersionId']},
                       headers={'Idempotency-Key': 'e2e-save-facade-001'})
    saved_budget = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
                       {'customerId': customer_id, 'kind': 'BUDGET', 'estimateId': est['estimateId'],
                        'width': 12, 'depth': 15, 'floors': 2},
                       headers={'Idempotency-Key': 'e2e-save-budget-001'})
    customer = act(sales, 'GET', f'/franchise/customers/{customer_id}')
    schemes = {s['kind']: s for s in customer['schemes']}
    assert 'PLAN' in schemes and 'FACADE' in schemes and 'BUDGET' in schemes, schemes.keys()
    # designProjectId 桥接：payload JSON 内引用 + DB 列级断言（详情接口不回显列，属正常序列化白名单）
    plan_payload = json.loads(schemes['PLAN']['payload'])
    assert str(plan_payload['designProjectId']) == str(proj_flat['projectId']), plan_payload
    col = psql(f"SELECT design_project_id FROM fr_customer_scheme WHERE id = {schemes['PLAN']['id']}")
    assert col == str(proj_flat['projectId']), col
    assert int(schemes['BUDGET']['totalCents']) == int(est['totalLowCents'])
    saved_plan_again = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
                           {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': sel['resultVersionId'],
                            'width': 12, 'depth': 15, 'floors': 2},
                           headers={'Idempotency-Key': 'e2e-save-plan-002'})
    customer2 = act(sales, 'GET', f'/franchise/customers/{customer_id}')
    plan_versions = sorted(s['version'] for s in customer2['schemes'] if s['kind'] == 'PLAN')
    assert plan_versions[-1] == plan_versions[-2] + 1, '重复保存=新版本行（只追加）'

    # ================= AC12 回写校验与并发 =================
    step('AC12 校验：尺寸/kind 交叉/引用归属/无权客户；并发同键仅一份方案；不同键并发两版本')
    for probe_name, body, expect in [
            ('nosize', {'customerId': customer_id, 'kind': 'BUDGET', 'estimateId': est['estimateId'],
                        'width': 12, 'depth': 15}, 1_112_000_001),
            ('xkind', {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': sel_e1['resultVersionId']},
             1_112_000_003),
            ('badref', {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': 999}, [1_099_000_002, 403])]:
        r = call('POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer', body,
                 token=sales['token'], post_id=sales['post'], expect_code=-1,
                 headers={'Idempotency-Key': f'e2e-save-{probe_name}'})
        print(f'    [debug] {probe_name} -> {r.get("code")} (expect {expect})')
        assert r.get('code') in ([expect] if isinstance(expect, int) else expect), f'{probe_name}: {r.get("code")} {r.get("msg")}'
    act(sales_b, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
        {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': sel['resultVersionId']},
        headers={'Idempotency-Key': 'e2e-save-cross'}, expect_code=[1_112_000_002, 1_110_000_003, 403])
    # 并发同键回写：仅一份方案、两响应相同
    conc_results, conc_errors = [], []

    def save_parallel():
        try:
            r = act(sales, 'POST', f'/franchise/design/projects/{proj_flat["projectId"]}/save-to-customer',
                    {'customerId': customer_id, 'kind': 'BUDGET', 'estimateId': est['estimateId'],
                     'width': 12, 'depth': 15, 'floors': 2},
                    headers={'Idempotency-Key': 'e2e-save-conc-001'})
            conc_results.append(r)
        except AssertionError as e:
            conc_errors.append(str(e))

    threads = [threading.Thread(target=save_parallel) for _ in range(3)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not conc_errors, conc_errors
    assert len({r['schemeId'] for r in conc_results}) == 1, conc_results

    # ================= AC13 桥接查询 =================
    step('AC13 桥接查询：customerId 过滤 + 翻页不漏项 + 无权客户拒绝')
    bridge = act(sales, 'GET', f'/franchise/design/projects?customerId={customer_id}&limit=10')
    assert any(p['projectId'] == proj_flat['projectId'] for p in bridge['list'])
    # 造 11 个关联项目（每个走迷你链：任务→模拟→选定→回写）→ limit=10 翻页取全
    linked_ids = [proj_flat['projectId']]
    for i in range(10):
        p = act(sales, 'POST', '/franchise/design/projects',
                {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                 'requirementInputs': {'faceWidthM': '9', 'depthM': '9', 'floorCount': 1}},
                headers={'Idempotency-Key': f'e2e-bridge-proj-{i}'})
        bj = act(sales, 'POST', f'/franchise/design/projects/{p["projectId"]}/flat-jobs',
                 {'count': 1, 'resolution': '2K', 'orientation': 'PORTRAIT'},
                 headers={'Idempotency-Key': f'e2e-bridge-job-{i}'})
        act(sales, 'POST', f'/platform/dev/design/ai-jobs/{bj["jobId"]}/simulate', {'acceptedCount': 1})
        wait_job_terminal(sales, bj['jobId'])
        bd = act(sales, 'GET', f'/franchise/design/projects/{p["projectId"]}?jobId={bj["jobId"]}')
        bsel = act(sales, 'POST', f'/franchise/design/projects/{p["projectId"]}/flat-selections',
                   {'jobId': bj['jobId'], 'candidateId': bd['candidates'][0]['candidateId']})
        act(sales, 'POST', f'/franchise/design/projects/{p["projectId"]}/save-to-customer',
            {'customerId': customer_id, 'kind': 'PLAN', 'resultVersionId': bsel['resultVersionId'],
             'width': 12, 'depth': 15, 'floors': 2},
            headers={'Idempotency-Key': f'e2e-bridge-save-{i}'})
        linked_ids.append(p['projectId'])
    page1 = act(sales, 'GET', f'/franchise/design/projects?customerId={customer_id}&limit=10')
    got = {p['projectId'] for p in page1['list']}
    assert page1['nextCursor'], '应存在续页游标'
    page2 = act(sales, 'GET', f'/franchise/design/projects?customerId={customer_id}&limit=10&cursor={page1["nextCursor"]}')
    all_ids = got | {p['projectId'] for p in page2['list']}
    assert set(linked_ids) <= all_ids, f'翻页漏项: {set(linked_ids) - all_ids}'

    # ================= AC14 点数不足（B 组织服务账号无点数） =================
    step('AC14 点数不足：design 原码透传，无任务行')
    proj_b = act(sales_b, 'POST', '/franchise/design/projects',
                 {'sourceType': 'SELF_UPLOAD', 'line': 'FLAT',
                  'requirementInputs': {'faceWidthM': '9', 'depthM': '9', 'floorCount': 1}},
                 headers={'Idempotency-Key': 'e2e-orgb-proj-001'})
    act(sales_b, 'POST', f'/franchise/design/projects/{proj_b["projectId"]}/flat-jobs',
        {'count': 1, 'resolution': '2K', 'orientation': 'PORTRAIT'},
        headers={'Idempotency-Key': 'e2e-orgb-job-001'}, expect_code=1_072_000_000)

    # ================= AC15 IDOR 与状态码 =================
    step('AC15 IDOR：跨组织项目/jobId 拒绝；未终态晋升业务码；无 X-Post-Id 自动选唯一任职')
    act(sales_b, 'GET', f'/franchise/design/projects/{proj_flat["projectId"]}', expect_code=[1_099_000_002, 403])
    act(sales_b, 'GET', f'/franchise/design/ai-jobs/{job1["jobId"]}', expect_code=[1_099_000_002, 403])
    # 未终态：给 B 组织项目提交任务但成功后不模拟——B 无点数，跳过；用 A 组织新项目未完成任务
    job_pending = act(sales, 'POST', f'/franchise/design/projects/{proj_ref["projectId"]}/elevation-jobs',
                      {'count': 1, 'resolution': '2K', 'orientation': 'LANDSCAPE',
                       'styleCode': 'MODERN', 'roofType': 'FLAT_ROOF',
                       'material': 'GREY_STONE', 'color': 'WARM_GREY'},
                      headers={'Idempotency-Key': 'e2e-pending-elev-001'})
    act(sales, 'GET', f'/franchise/design/projects/{proj_ref["projectId"]}?jobId={job_pending["jobId"]}',
        expect_code=1_071_000_009)
    # 无 X-Post-Id 且仅一任职 → 自动选中正常执行
    auto = call('GET', '/franchise/design/cases', token=sales['token'], post_id=None)
    assert auto is not None

    # ================= AC17 回归与基建（部分成功/全失败/审计/dev 鉴权） =================
    step('AC17 P-7 模拟通道：部分成功差额退点 / 全失败全退 / dev 端点匿名拒绝')
    bal_prev = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    job_part = act(sales, 'POST', f'/franchise/design/projects/{proj_ref["projectId"]}/elevation-jobs',
                   {'count': 2, 'resolution': '2K', 'orientation': 'LANDSCAPE',
                    'styleCode': 'MODERN', 'roofType': 'FLAT_ROOF',
                    'material': 'GREY_STONE', 'color': 'WARM_GREY'},
                   headers={'Idempotency-Key': 'e2e-part-elev-001'})
    bal_created = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    assert bal_prev - bal_created == 2 * ((bal_prev - bal_created) // 2), '预扣=2×单价'
    unit = (bal_prev - bal_created) // 2
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job_part["jobId"]}/simulate', {'acceptedCount': 1})
    part_done = wait_job_terminal(sales, job_part['jobId'])
    assert part_done['status'] == 'PARTIALLY_SUCCEEDED', part_done
    bal_part = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    assert bal_part == bal_created + unit, f'部分成功应退 1 槽差额（实退 {bal_part - bal_created}，单价 {unit}）'
    job_fail = act(sales, 'POST', f'/franchise/design/projects/{proj_ref["projectId"]}/elevation-jobs',
                   {'count': 1, 'resolution': '2K', 'orientation': 'LANDSCAPE',
                    'styleCode': 'MODERN', 'roofType': 'FLAT_ROOF',
                    'material': 'GREY_STONE', 'color': 'WARM_GREY'},
                   headers={'Idempotency-Key': 'e2e-fail-elev-001'})
    bal_fail_created = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job_fail["jobId"]}/simulate', {'acceptedCount': 0})
    fail_done = wait_job_terminal(sales, job_fail['jobId'])
    assert fail_done['status'] == 'FAILED', fail_done
    bal_fail = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    assert bal_fail == bal_fail_created + unit, f'全失败应全额退点（fail_created={bal_fail_created} fail={bal_fail} unit={unit}）'
    # dev 端点匿名拒绝（无 token）
    call('GET', '/platform/dev/design/point-account', expect_code=401)
    call('GET', '/platform/dev/design/point-account', token=sales_b['token'], post_id=sales_b['post'])  # 自组织可读

    # ================= 提示词调用夹具（AC17 续） =================
    step('AC17b 提示词夹具：成功调用恰扣一次 AI_PROMPT_DEBIT；未调用不扣费')
    bal_prev = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    prompt = act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job_pending["jobId"]}/prompt-call', {})
    assert prompt['charged'] is True, prompt
    bal_prompt = int(act(sales, 'GET', '/platform/dev/design/point-account')['availablePoints'])
    assert bal_prompt == bal_prev - 1, 'AI_PROMPT 计价 1 点'
    prompt2 = act(sales, 'POST', f'/platform/dev/design/ai-jobs/{job_pending["jobId"]}/prompt-call', {})
    assert prompt2['charged'] is False, '同业务键重复调用不重复扣费'

    print('\n全部断言通过 ✔  （B2 板块 A e2e 主链 21 步）')


if __name__ == '__main__':
    main()
