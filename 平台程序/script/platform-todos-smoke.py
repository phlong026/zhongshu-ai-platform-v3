#!/usr/bin/env python3
"""平台待办冒烟（AC16）：销售提交非标准费用委托 → FEE_PENDING → boss 待办精确匹配 → fixture 固化。

前提：本地 server 以 local,pg,zsdev 运行（:48080，stub 微信），
启动示例：
  java -DsocksProxyHost= -jar yudao-server/target/yudao-server.jar \
    --spring.profiles.active=local,pg,zsdev \
    --platform.identity.wechat.provider=stub \
    --platform.identity.wechat.apps[0].appid=wx-e2e --platform.identity.wechat.apps[0].secret=stub
用法：python3 script/platform-todos-smoke.py
产出：v3 仓库 scripts/fixtures/platform-todos-sample.json（AC11 离线样例）
"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
FIXTURE = '/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3/scripts/fixtures/platform-todos-sample.json'
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
    if isinstance(expect_code, int):
        expect_code = [expect_code]
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
    return {'token': result['accessToken'], 'post': setup[who]['postId']}


def act(user, method, path, body=None):
    return call(method, path, body, token=user['token'], post_id=user['post'])


def main():
    step('dev-setup 一键搭建总部与门店五名员工')
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': 'todo' + str(int(time.time()) % 100000)})
    boss = login(setup, 'boss')
    sales = login(setup, 'sales')

    step('老板配置标准设计费用（680000 元，首款 408000）——后续委托故意不一致才产生审批待办')
    act(boss, 'POST', '/franchise/design-policies',
        {'total': 680000, 'first': 408000, 'scope': '方案、施工图与交底', 'recipient': '总部财务'})

    step('销售登记客资并保存参考预算（对齐标准，供对照）')
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '平先生', 'phone': '139%08d' % (int(time.time()) % 100000000),
                'need': '两层乡墅', 'source': '冒烟造数'})
    assert lead['result'] == 'CREATED', lead
    customer_id = lead['customerId']
    act(sales, 'POST', f'/franchise/customers/{customer_id}/schemes',
        {'kind': 'BUDGET', 'title': '参考预算', 'payload': '{"area":400}',
         'width': 13, 'depth': 16, 'floors': 2, 'area': 400, 'totalCents': 68000000})

    step('销售提交非标准费用委托（880000/首448000 ≠ 标准）→ 预期 FEE_PENDING')
    detail = act(sales, 'POST', f'/franchise/customers/{customer_id}/commission',
                 {'text': '两层乡墅，非标准费用', 'scope': '方案、施工图与交底',
                  'total': 880000, 'first': 448000, 'recipient': '总部财务', 'confirmed': True})
    status = detail['commission']['status']
    assert status == 'FEE_PENDING', f'预期 FEE_PENDING，实际 {status}'
    commission_id = detail['commission']['id']
    print(f'    commissionId={commission_id} 状态={status}')

    step('boss 查询 /platform/todos → 按 commissionId 精确匹配 fee-approve 待办')
    todos = call('GET', '/platform/todos', token=boss['token'], post_id=boss['post'])
    items = todos.get('items', [])
    match = [i for i in items if i['key'] == f'franchise:fee-approve:{commission_id}']
    assert match, f'未找到本次委托的审批待办；实际 items={[i["key"] for i in items]}'
    item = match[0]
    assert item['actionable'] is True, item
    assert item['appPath'] == '/pages/workflow/commission/index', item
    assert item['amountCents'] == 88000000, item
    print(f'    待办命中：{item["title"]} 金额={item["amountCents"]}')

    step('响应固化到 v3 fixtures（AC11 离线样例）')
    import os
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    with open(FIXTURE, 'w', encoding='utf-8') as f:
        json.dump(todos, f, ensure_ascii=False, indent=2)
    print(f'    已写入 {FIXTURE}')

    print('冒烟通过：非标准委托 → FEE_PENDING → boss 待办精确匹配（AC16）')


if __name__ == '__main__':
    main()
