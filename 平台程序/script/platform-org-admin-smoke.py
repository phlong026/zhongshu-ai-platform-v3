#!/usr/bin/env python3
"""组织成员管理冒烟（管理员指派制）：建部门→录员工→新员工登录发现任职→撤销→停用→越权拒绝。

前置：本地 server 以 local,pg + stub 短信运行（端口 48080）。
用法：python3 script/platform-org-admin-smoke.py
"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:48080/admin-api'
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


def sms_login(mobile):
    call('POST', '/platform/auth/sms/send', {'mobile': mobile})
    captcha = call('GET', f'/platform/dev-setup/sms-captcha?mobile={mobile}')
    result = call('POST', '/platform/auth/sms/login', {'mobile': mobile, 'captcha': captcha})
    assert result.get('accessToken'), result
    return result['accessToken']


def main():
    step('dev-setup 一键搭建总部与门店五名员工，老板登录并对准门店岗位')
    setup = call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': 'org' + str(int(time.time()) % 100000)})
    boss_login = call('POST', '/platform/auth/wechat-login',
                      {'appid': 'wx-e2e', 'loginCode': 'e2e-' + setup['boss']['mobile'], 'phoneCode': setup['boss']['mobile']})
    boss = {'token': boss_login['accessToken'], 'post': setup['boss']['postId']}
    org_id = setup['storeOrgId']

    step('老板建部门（工程二部）')
    team = call('POST', '/platform/org/teams', {'orgId': org_id, 'name': '工程二部', 'line': 'engineering'},
                token=boss['token'], post_id=boss['post'])
    assert team['deptId'] and team['name'] == '工程二部', team

    step('老板查看部门列表与本店成员')
    teams = call('GET', f'/platform/org/teams?orgId={org_id}', token=boss['token'], post_id=boss['post'])
    assert any(t['name'] == '工程二部' for t in teams), teams
    staff = call('GET', f'/platform/org/staff?orgId={org_id}', token=boss['token'], post_id=boss['post'])
    assert len(staff) >= 4, staff  # 门店四人（设计行政在总部）

    step('老板录入新员工并指派业务岗位（管理员指派制核心动作）')
    mobile = '137' + '%08d' % (int(time.time()) % 100_000_000)
    row = call('POST', '/platform/org/staff',
               {'orgId': org_id, 'name': '陈新', 'mobile': mobile, 'roleCode': 'sales', 'teamId': setup['salesTeamId']},
               token=boss['token'], post_id=boss['post'])
    assert row['postId'] and row['roleCode'] == 'sales', row
    post_id = row['postId']

    step('新员工短信登录 → posts/mine 自动发现本店任职（登录发现闭环）')
    token = sms_login(mobile)
    posts = call('GET', '/platform/posts/mine', token=token)
    assert any(str(p['postId']) == str(post_id) for p in posts), posts
    print(f'    新员工发现 {len(posts)} 个任职，含刚指派的业务岗')

    step('老板给新员工追加工程岗位，任职列表即时更新')
    call('POST', '/platform/org/posts',
         {'orgId': org_id, 'userId': row['userId'], 'roleCode': 'engineer', 'teamId': team['deptId']},
         token=boss['token'], post_id=boss['post'])
    posts = call('GET', '/platform/posts/mine', token=token)
    codes = {p['roleCode'] for p in posts}
    assert {'sales', 'engineer'} <= codes, codes

    step('老板撤销业务岗位 → 新员工该任职立即消失')
    call('DELETE', f'/platform/org/posts/{post_id}', token=boss['token'], post_id=boss['post'])
    posts = call('GET', '/platform/posts/mine', token=token)
    assert all(not (str(p['postId']) == str(post_id)) for p in posts), posts

    step('老板停用新员工 → 令牌失效（后续请求被拒）')
    call('POST', '/platform/org/staff-status', {'userId': row['userId'], 'active': False},
         token=boss['token'], post_id=boss['post'])
    r = call('GET', '/platform/posts/mine', token=token, expect_code=401)
    print('    已停用员工的旧令牌请求返回 401')

    step('业务员工越权调用成员管理接口被拒（403，权限码未授权）')
    sales_login = call('POST', '/platform/auth/wechat-login',
                       {'appid': 'wx-e2e', 'loginCode': 'e2e-' + setup['sales']['mobile'], 'phoneCode': setup['sales']['mobile']})
    call('GET', f'/platform/org/staff?orgId={org_id}',
         token=sales_login['accessToken'], post_id=setup['sales']['postId'], expect_code=403)

    step('老板跨组织管理被拒（113000004 范围校验）')
    call('GET', f'/platform/org/staff?orgId={setup["headquartersOrgId"]}',
         token=boss['token'], post_id=boss['post'], expect_code=1_103_000_004)

    print('冒烟通过：部门/录入指派/登录发现/追加/撤销/停用/越权拒绝/跨组织拒绝 全链符合预期')


if __name__ == '__main__':
    main()
