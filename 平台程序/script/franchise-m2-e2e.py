#!/usr/bin/env python3
"""加盟协同域 M2 整机端到端验收（M2 设计 §7：经 HTTP 用多名员工走一遍主链）。

前置：本地 server 以 local,pg + stub 微信登录运行（端口 48080，AppID wx-e2e）。
用法：python3 script/franchise-m2-e2e.py
"""
import json
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
APPID = 'wx-e2e'
step_no = 0


def call(method, path, body=None, token=None, post_id=None, expect_code=0):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if post_id:
        req.add_header('X-Post-Id', str(post_id))
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read().decode())
    if payload.get('code') != expect_code:
        raise AssertionError(f'{method} {path} -> code={payload.get("code")} msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def login(mobile, post_id=None):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': APPID, 'loginCode': 'e2e-' + mobile, 'phoneCode': mobile})
    posts = call('GET', '/platform/posts/mine', token=result['accessToken'])
    chosen = next((p for p in posts if p['postId'] == post_id), posts[0])
    return {'token': result['accessToken'], 'posts': posts,
            'post_id': chosen['postId'], 'user_id': chosen['userId']}


def act(user, method, path, body=None):
    return call(method, path, body, token=user['token'], post_id=user['post_id'])


def status(commission):
    return commission['commission']['status']


def main():
    step('一键搭建总部与门店五名员工')
    import time
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                 {'batch': str(int(time.time()) % 10000)})
    print('    总部 org=%s 门店 org=%s' % (setup['headquartersOrgId'], setup['storeOrgId']))
    admin = login(setup['designAdmin']['mobile'], setup['designAdmin']['postId'])
    sales = login(setup['sales']['mobile'], setup['sales']['postId'])
    boss = login(setup['boss']['mobile'], setup['boss']['postId'])
    finance = login(setup['finance']['mobile'], setup['finance']['postId'])
    engineer = login(setup['engineer']['mobile'], setup['engineer']['postId'])
    step('五名员工 stub 登录成功，岗位对准本轮门店（设计行政/业务/老板/工程负责人）')
    assert sales['post_id'] == setup['sales']['postId']

    step('老板配置标准设计费用（680000 元，首款 408000）')
    act(boss, 'POST', '/franchise/design-policies',
        {'total': 680000, 'first': 408000, 'scope': '方案、施工图与交底', 'recipient': '总部财务'})

    step('业务登记客资（同手机号直建客户）')
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '陈先生', 'phone': '13900001111', 'need': '两层乡墅，按标准费用委托设计', 'source': '视频号咨询'})
    assert lead['result'] == 'CREATED', lead
    customer_id = lead['customerId']

    step('业务保存参考预算版本')
    act(sales, 'POST', f'/franchise/customers/{customer_id}/schemes',
        {'kind': 'BUDGET', 'title': '参考预算', 'payload': '{"area":400}',
         'width': 13, 'depth': 16, 'floors': 2, 'area': 400, 'totalCents': 68000000})

    step('业务提交设计委托（与标准一致 → 免审批进入设计）')
    detail = act(sales, 'POST', f'/franchise/customers/{customer_id}/commission',
                 {'text': '两层乡墅，面宽13m进深16m', 'scope': '方案、施工图与交底',
                  'total': 680000, 'first': 408000, 'recipient': '总部财务', 'confirmed': True})
    assert status(detail) == 'DESIGNING', status(detail)
    assert detail['submissions'][0]['authKind'] == 'STANDARD'
    commission_id = detail['commission']['id']
    submission_version = detail['commission']['submissionVersion']

    step('财务模拟首款到账 → 设计行政确认设计完成')
    act(finance, 'POST', '/platform/dev-setup/franchise-e2e/fee-settlement',
        {'commissionId': commission_id, 'submissionVersion': submission_version, 'stage': 'first-ready'})
    todos = act(admin, 'GET', '/platform/todos')
    assert any(t['key'] == f'franchise:design-complete:{commission_id}:{submission_version}'
               and t['actionable'] for t in todos['items']), todos
    detail = act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-complete',
                 {'note': '线下设计与内部审核已完成', 'confirmed': True})
    assert status(detail) == 'DESIGN_COMPLETED', status(detail)

    step('财务模拟尾款到账 → 设计行政下发成果')
    act(finance, 'POST', '/platform/dev-setup/franchise-e2e/fee-settlement',
        {'commissionId': commission_id, 'submissionVersion': submission_version, 'stage': 'tail-paid'})
    act(finance, 'POST', '/platform/dev-setup/franchise-e2e/fee-settlement',
        {'commissionId': commission_id, 'submissionVersion': submission_version, 'stage': 'tail-ready'})
    detail = act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-dispatch',
                 {'note': '方案、施工图、预算书已交付', 'recipient': '总部设计行政', 'confirmed': True})
    assert status(detail) == 'DISPATCHED', status(detail)

    step('业务登记修改意见 → 回到设计中 → 重新完成与下发（不重收首尾款）')
    detail = act(sales, 'POST', f'/franchise/commissions/{commission_id}/commands/commission-feedback',
                 {'note': '立面调整：门洞改双开'})
    assert status(detail) == 'DESIGNING', status(detail)
    act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-complete',
        {'note': '按修改意见重新完成', 'confirmed': True})
    detail = act(admin, 'POST', f'/franchise/commissions/{commission_id}/commands/design-dispatch',
                 {'note': '修订版成果已交付', 'recipient': '总部设计行政', 'confirmed': True})
    assert status(detail) == 'DISPATCHED' and detail['commission']['dispatchSeq'] == 2, detail['commission']

    step('业务提交移交（指定工程负责人）→ 工程负责人退回 → 补充重提 → 核查接收')
    packet = {'dispatchSeq': 2, 'resultFile': '{"name":"成果文件"}', 'drawingFile': '{"name":"施工图"}',
              'reviewFile': '{"name":"审核依据"}', 'confirmationFile': '{"name":"客户确认"}',
              'drawingVersion': 'S2-2026', 'confirmationDate': '2026-10-28',
              'note': '已向工程负责人现场交底', 'managerUserId': engineer['user_id'], 'confirmed': True}
    detail = act(sales, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-submit', packet)
    assert status(detail) == 'HANDOVER_SUBMITTED', status(detail)
    todos = act(engineer, 'GET', '/platform/todos')
    assert any(t['key'].startswith(f'franchise:handover-review:{commission_id}') for t in todos['items'])
    detail = act(engineer, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-return',
                 {'reason': '客户确认日期与合同不符，请核对后重提'})
    assert status(detail) == 'HANDOVER_RETURNED', status(detail)
    detail = act(sales, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-submit', packet)
    assert status(detail) == 'HANDOVER_SUBMITTED', status(detail)
    detail = act(engineer, 'POST', f'/franchise/commissions/{commission_id}/commands/packet-receive',
                 {'confirmed': True})
    assert status(detail) == 'ACCEPTED', status(detail)

    step('主链闭环验证：委托相关待办清空、显示状态归位')
    # 客户首次跟进等待办与委托互不阻塞（设计 §10），只验证本委托相关待办清零
    for name, user in (('业务', sales), ('设计行政', admin), ('工程', engineer), ('老板', boss)):
        todos = act(user, 'GET', '/platform/todos')
        actionable = [t['title'] for t in todos['items'] if t['actionable'] and str(commission_id) in t['key']]
        assert not actionable, f'{name} 仍有委托相关待办: {actionable}'
    final = act(sales, 'GET', f'/franchise/commissions/{commission_id}')
    assert final['displayStatus'] == '工程已接收', final['displayStatus']
    print(f'    ✅ 主链闭环：委托 {commission_id} 已接收，各岗位待办清零')

    step('越权抽查：工程负责人不能查看老板的费用审批、业务不能审批费用')
    pending = call('POST', '/franchise/leads',
                   {'name': '越权抽查', 'phone': '13900002222', 'need': '异常费用委托', 'source': '直接来访'},
                   token=sales['token'], post_id=sales['post_id'], expect_code=0)
    act(sales, 'POST', f"/franchise/customers/{pending['customerId']}/schemes",
        {'kind': 'BUDGET', 'title': '参考预算', 'payload': '{}',
         'width': 13, 'depth': 16, 'floors': 2, 'area': 400, 'totalCents': 68000000})
    c2 = act(sales, 'POST', f"/franchise/customers/{pending['customerId']}/commission",
             {'text': '异常费用', 'scope': '方案', 'total': 900000, 'first': 500000,
              'recipient': '总部财务', 'confirmed': True})
    assert status(c2) == 'FEE_PENDING', status(c2)
    # 业务员无 franchise:commission:fee-decide 权限码，被第一道门（@post.has）拦下
    call('POST', f"/franchise/commissions/{c2['commission']['id']}/fee-decision",
         {'approve': True, 'note': '越权'}, token=sales['token'], post_id=sales['post_id'], expect_code=403)
    print('    ✅ 业务审批被拒（403 权限码层拦截：委托费用由本门店老板审批）')

    print(f'\n=== M2 整机端到端验收通过：{step_no} 步全部断言成功 ===')


if __name__ == '__main__':
    main()
