#!/usr/bin/env python3
"""阶段 0 · 基-2 员工消息中心端到端验收（11 文档 AC2-AC10）。

前提：本地 server 以 local,pg,zsdev 运行（含 pf_message 迁移），PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e。
断言口径：HTTP 状态 + body.code 二元组（未认证为 HTTP 200 + code=401，11 文档 §四）。
消息注入经 dev-setup 种子端点走完整 MessageSender 代码路径（§四.5），非 psql 直插。
用法：python3 script/platform-message-e2e.py
"""
import json
import threading
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
step_no = 0
MESSAGE_NOT_FOUND = 1_105_000_000


def raw_call(method, path, body=None, token=None):
    """返回 (http_status, payload)；不抛业务断言。"""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('content-type', 'application/json')
    req.add_header('tenant-id', '1')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def call(method, path, body=None, token=None, expect=(200, [0])):
    status, payload = raw_call(method, path, body, token)
    ok_status = status in (expect[0] if isinstance(expect[0], (list, tuple)) else [expect[0]])
    codes = expect[1] if isinstance(expect[1], (list, tuple)) else [expect[1]]
    got = payload.get('code') if isinstance(payload, dict) else None
    if not ok_status or got not in codes:
        raise AssertionError(f'{method} {path} -> 预期{expect} 实际(HTTP {status}, code={got}) msg={payload.get("msg")}')
    return payload.get('data')


def step(title):
    global step_no
    step_no += 1
    print(f'[{step_no:02d}] {title}')


def sms_login(mobile):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile}, expect=(200, [0, None],))
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return result['accessToken']


def psql(sql):
    import subprocess
    out = subprocess.run(['docker', 'exec', 'zhongshu-backend-dev-postgres-1', 'psql', '-U', 'zhongshu',
                          '-d', 'zhongshu_design', '-tAc', sql], capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


BATCH = None
SEED_TS = int(time.time() * 1000)


def seed(tokenless_body, expect=(200, [0])):
    """经 dev-setup 种子端点发消息（走完整 MessageSender 校验+幂等路径）。"""
    return call('POST', '/platform/dev-setup/message/seed', tokenless_body, expect=expect)


def msg_body(user_id, org_id, n, type_='BUSINESS', route='customer', dedupe=None, object_id=None):
    return {'userId': user_id, 'orgId': org_id, 'senderDomain': 'e2e', 'type': type_, 'route': route,
            'objectId': object_id if object_id is not None else 900000 + n,
            'title': f'e2e 消息 {n}', 'description': f'第 {n} 条 e2e 注入',
            'dedupeKey': dedupe or f'e2e:msg:{SEED_TS}:{n}'}


def main():
    global BATCH
    # ================= 组织与账号 =================
    step('dev-setup 搭组织（唯一 batch/mobilePrefix）+ 双账号登录')
    ts = int(time.time()) % 10000000
    BATCH = 'msg' + str(ts)
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup',
                 {'batch': BATCH, 'mobilePrefix': '136%07d' % ts})
    sales, boss = setup['sales'], setup['boss']
    org_id = setup['storeOrgId']
    sales_token, boss_token = sms_login(sales['mobile']), sms_login(boss['mobile'])

    # ================= AC2 未认证与空起步 =================
    step('AC2 未认证四端点均 (HTTP 200, code=401)')
    for method, path, body in (('GET', '/platform/messages', None),
                               ('GET', '/platform/messages/unread-count', None),
                               ('POST', '/platform/messages/1/read', {}),
                               ('POST', '/platform/messages/read-all', {})):
        status, payload = raw_call(method, path, body)
        assert status == 200 and payload.get('code') == 401, (method, path, status, payload)

    step('AC2 登录后 GET list 空数组起步，unread-count=0')
    assert call('GET', '/platform/messages', token=sales_token) == [], '新用户应无消息'
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 0

    # ================= AC3 三类消息注入与字段 =================
    step('AC3 种子注入 BUSINESS/LEARNING/SYSTEM 三类，list 字段齐、unread=3、id 倒序')
    sent = []
    for n, (type_, route) in enumerate([('BUSINESS', 'customer'), ('LEARNING', 'course'), ('SYSTEM', 'privacy')]):
        result = seed(msg_body(sales['userId'], org_id, n, type_, route))
        row = result['message']
        assert result['deduplicated'] is False, result
        assert row['type'] == type_ and row['route'] == route, row
        assert row['read'] is False, row
        assert row['title'] and row['description'] and row['createTime'], row
        sent.append(row)

    rows = call('GET', '/platform/messages', token=sales_token)
    assert [r['id'] for r in rows] == sorted((r['id'] for r in rows), reverse=True), '应 id 倒序'
    assert {r['type'] for r in rows} == {'BUSINESS', 'LEARNING', 'SYSTEM'}, rows
    assert all(r['read'] is False for r in rows), rows
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 3
    oldest = rows[-1]  # id 倒序，最早一条在末位
    assert psql(f"SELECT read FROM pf_message WHERE id={oldest['id']}") == 'f'

    # ================= AC4 幂等重发 =================
    step('AC4 同 senderDomain+dedupeKey 重发：deduplicated=true、id 不变、列表与未读数不变')
    body = msg_body(sales['userId'], org_id, 0, dedupe=f'e2e:msg:{SEED_TS}:0')
    again = seed(body)
    assert again['deduplicated'] is True, again
    assert again['message']['id'] == sent[0]['id'], (again, sent[0])
    rows_after = call('GET', '/platform/messages', token=sales_token)
    assert len(rows_after) == 3, rows_after
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 3

    # ================= AC5 单条已读 =================
    step('AC5 单条已读：视图 read=true、未读数减 1、查库 read/read_time；重复已读幂等保持首次时间')
    target = rows_after[0]
    marked = call('POST', f"/platform/messages/{target['id']}/read", {}, token=sales_token)
    assert marked['read'] is True and marked['id'] == target['id'], marked
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 2
    db = psql(f"SELECT read, read_time IS NOT NULL FROM pf_message WHERE id={target['id']}").split('|')
    assert db == ['t', 't'], db
    first_read_time = psql(f"SELECT read_time FROM pf_message WHERE id={target['id']}")
    replay = call('POST', f"/platform/messages/{target['id']}/read", {}, token=sales_token)
    assert replay['read'] is True, replay
    assert psql(f"SELECT read_time FROM pf_message WHERE id={target['id']}") == first_read_time, '重放不得覆盖首次 read_time'

    # ================= AC6 不存在与跨用户 =================
    step('AC6 不存在/他人消息一律 1_105_000_000；boss 列表与未读数与 sales 互不可见')
    call('POST', '/platform/messages/999999999/read', {}, token=sales_token, expect=(200, [MESSAGE_NOT_FOUND]))
    call('POST', f"/platform/messages/{rows_after[1]['id']}/read", {}, token=boss_token,
         expect=(200, [MESSAGE_NOT_FOUND]))
    boss_rows = call('GET', '/platform/messages', token=boss_token)
    assert boss_rows == [], boss_rows
    assert call('GET', '/platform/messages/unread-count', token=boss_token)['count'] == 0

    # ================= AC7 read-all =================
    step('AC7 再注 2 条未读 → read-all updated 一致、未读清零、查库全已读；重复 read-all updated=0')
    for n in (10, 11):
        seed(msg_body(sales['userId'], org_id, n))
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 4  # 2 未读 + 再注 2
    updated = call('POST', '/platform/messages/read-all', {}, token=sales_token)['updated']
    assert updated == 4, updated
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 0
    cnt = psql(f"SELECT count(*) FROM pf_message WHERE user_id={sales['userId']} AND (read AND read_time IS NOT NULL)")
    assert cnt == '5', cnt  # 1(AC5) + 4(AC7)
    assert call('POST', '/platform/messages/read-all', {}, token=sales_token)['updated'] == 0

    # ================= AC8 游标分页 =================
    step('AC8 补注至 24 条：缺省 limit=最新 20 条；limit=3 逐页取完不重不漏；clamp 0→1、1000→全量')
    current = len(call('GET', '/platform/messages', token=sales_token, expect=(200, [0])))
    for n in range(100, 100 + (24 - current)):
        seed(msg_body(sales['userId'], org_id, n))
    ids = [r['id'] for r in call('GET', '/platform/messages', token=sales_token)]
    assert len(ids) == 20 and ids == sorted(ids, reverse=True), '缺省 limit 应返回最新 20 条且倒序'
    all_ids, cursor = [], None
    while True:
        path = '/platform/messages?limit=3' + (f'&beforeId={cursor}' if cursor else '')
        page = call('GET', path, token=sales_token)
        all_ids.extend(r['id'] for r in page)
        if len(page) < 3:
            break
        cursor = page[-1]['id']
    assert len(all_ids) == len(set(all_ids)) == 24, (len(all_ids), len(set(all_ids)))
    assert all_ids == sorted(all_ids, reverse=True), '翻页合并后应整体倒序且不重不漏'
    one = call('GET', '/platform/messages?limit=0', token=sales_token)
    assert len(one) == 1, one
    full = call('GET', '/platform/messages?limit=1000', token=sales_token)
    assert len(full) == 24, len(full)

    # ================= AC9 用户隔离正面 =================
    step('AC9 boss 自己收 1 条：boss 未读=1 且列表仅本人；sales 不受扰动')
    boss_msg = seed(msg_body(boss['userId'], org_id, 50))
    assert boss_msg['deduplicated'] is False
    assert call('GET', '/platform/messages/unread-count', token=boss_token)['count'] == 1
    boss_rows = call('GET', '/platform/messages', token=boss_token)
    assert [r['id'] for r in boss_rows] == [boss_msg['message']['id']], boss_rows
    assert boss_rows[0]['title'] == boss_msg['message']['title']
    sales_ids = [r['id'] for r in call('GET', '/platform/messages?limit=1000', token=sales_token)]
    assert boss_msg['message']['id'] not in sales_ids
    assert call('GET', '/platform/messages/unread-count', token=sales_token)['count'] == 19  # 24-5 已读（AC5 一条 + AC7 四条）

    # ================= AC10 真 PG 并发 =================
    step('AC10 并发 seed 同 dedupeKey：均成功同一 id、库恰 1 行；并发 read 同一消息：均成功、read_time 单值')
    concurrent_key = f'e2e:concurrent:{SEED_TS}'
    seed_results = []

    def seed_worker():
        try:
            seed_results.append(seed(msg_body(sales['userId'], org_id, 99, dedupe=concurrent_key)))
        except Exception as e:  # noqa: 异常传播至主线程断言（URLError 等网络层失败也算）
            seed_results.append(e)

    threads = [threading.Thread(target=seed_worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(seed_results) == 2 and all(not isinstance(r, Exception) for r in seed_results), seed_results
    assert seed_results[0]['message']['id'] == seed_results[1]['message']['id'], seed_results
    assert psql(f"SELECT count(*) FROM pf_message WHERE sender_domain='e2e' AND dedupe_key='{concurrent_key}'") == '1'

    unread_id = call('GET', '/platform/messages?limit=1', token=sales_token)[0]['id']
    psql(f"UPDATE pf_message SET read=FALSE, read_time=NULL WHERE id={unread_id}")  # 复位一条未读供并发已读
    read_results = []

    def read_worker():
        try:
            read_results.append(call('POST', f'/platform/messages/{unread_id}/read', {}, token=sales_token))
        except Exception as e:  # noqa: 异常传播至主线程断言（URLError 等网络层失败也算）
            read_results.append(e)

    threads = [threading.Thread(target=read_worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(read_results) == 2 and all(not isinstance(r, Exception) for r in read_results), read_results
    assert all(r['read'] is True for r in read_results), read_results
    assert psql(f"SELECT count(*) FROM pf_message WHERE id={unread_id} AND read AND read_time IS NOT NULL") == '1'

    print(f'\n全部断言通过 ✔  （基-2 消息 e2e 共 {step_no} 步）')


if __name__ == '__main__':
    main()
