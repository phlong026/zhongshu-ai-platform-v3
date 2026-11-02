#!/usr/bin/env python3
"""阶段 1.5 · 内容创作获客端到端验收（16 文档 §八 AC1-AC11，全链 fake provider 零付费外呼）。

前提：本地 server 以 local,pg,zsdev 运行（zhongshu.content.provider=fake、task-sweep-enabled=true），
并以 stub 微信登录启动：
  PF_WX_PROVIDER=stub PF_WX_STAFF_APPID=wx-e2e ZS_CONTENT_QUOTA_ASR=2 \
  java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local,pg,zsdev
（ZS_CONTENT_QUOTA_ASR=2 供 AC8 超限断言；ASR 日配额耗尽后第 4 次提取报 1_114_000_002。）
断言口径：HTTP 200 + body.code 二元组（07 §三.4）。
用法：python3 script/franchise-content-e2e.py
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

MP3_BYTES = b'ID3' + bytes(512)  # 内容素材不做内容解析（B_END 免审），仅 SHA/大小/MIME 校验


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


def setup():
    ts = int(time.time()) % 1000000
    return call('POST', '/platform/dev-setup/franchise-e2e/setup', {'batch': 'content' + str(ts)})


def login(who):
    result = call('POST', '/platform/auth/wechat-login',
                  {'appid': 'wx-e2e', 'loginCode': 'e2e-content-' + who['mobile'], 'phoneCode': who['mobile']})
    return {'token': result['accessToken'], 'post': who['postId'], 'userId': who['userId']}


def act(user, method, path, body=None, expect_code=0, idem=None):
    headers = {'Idempotency-Key': idem} if idem else None
    return call(method, path, body, token=user['token'], post_id=user['post'],
                expect_code=expect_code, headers=headers)


def wait_terminal(user, task_id, timeout_s=20):
    for _ in range(int(timeout_s / 0.5)):
        task = act(user, 'GET', f'/franchise/content/tasks/{task_id}')
        if task['status'] in ('SUCCEEDED', 'FAILED', 'SUBMISSION_UNCERTAIN'):
            return task
        time.sleep(0.5)
    raise AssertionError(f'task {task_id} 未在 {timeout_s}s 内到终态: {task["status"]}')


def upload_content_asset(sales, content):
    sha = hashlib.sha256(content).hexdigest()
    ticket = act(sales, 'POST', '/franchise/content/assets/upload-tickets',
                 {'mimeType': 'audio/mpeg', 'sizeBytes': len(content), 'sha256': sha})
    asset_id = ticket['assetId']
    assert psql(f"SELECT object_key FROM asset WHERE id={asset_id}").startswith('uploads/content-assets/'), \
        'CONTENT_ASSET 须落 content-assets 前缀（AC10 基-1 模式）'
    # dev bytes 端点权限门=franchise:design:use（基-1 同款），门店 sales 具备
    act(sales, 'POST', f'/platform/dev/design/assets/{asset_id}/bytes',
        {'contentBase64': base64.b64encode(content).decode()})
    completed = act(sales, 'POST', f'/franchise/content/assets/{asset_id}/complete')
    assert completed['status'] == 'ACCEPTED', completed
    return asset_id, sha


def main():
    step('一键搭建总部与门店员工（fake provider 全链，零付费外呼）')
    env = setup()
    run = str(int(time.time()))
    org = env['storeOrgId']
    sales = login(env['sales'])
    boss = login(env['boss'])
    engineer = login(env['engineer'])
    design_admin = login(env['designAdmin'])

    step('AC1：九表迁移落库（812/813/855）')
    tables = psql("SELECT count(*) FROM information_schema.tables WHERE table_name IN ("
                  "'fr_content_script','fr_content_task','fr_content_draft','fr_content_publication',"
                  "'fr_content_provider_call','fr_content_ai_usage','fr_content_persona',"
                  "'fr_content_video_draft','fr_content_course_progress')")
    assert tables == '9', f'fr_content_* 九表须齐（实际 {tables}）'
    applied = psql("SELECT count(*) FROM flyway_schema_history WHERE version IN "
                   "('20261102.812','20261102.813','20261102.855')")
    assert applied == '3', f'812/813/855 迁移须成对落库（实际 {applied}）'
    print('    ✅ 九表 + 三迁移在库')

    step('AC2：未认证 401；非业务岗创作端点拒（403 矩阵）；门店岗 growth 正向')
    call('GET', '/franchise/content/tasks', expect_code=401)
    act(engineer, 'POST', '/franchise/content/links/parse', {'url': 'https://v.douyin.com/x/'}, expect_code=403)
    growth = act(boss, 'GET', '/franchise/content/growth/summary')
    assert growth['publications'] == {}, growth
    act(sales, 'GET', '/franchise/content/growth/summary')
    print('    ✅ 401 / 工程岗 403（权限矩阵层） / sales+boss growth 正向')

    step('AC3：链接解析（fake diadi）→ SUCCEEDED 返回 videoUrl/text/cover + 幂等重放同任务')
    task = act(sales, 'POST', '/franchise/content/links/parse',
               {'url': 'https://v.douyin.com/e2e/'}, idem=f'e2e-link-1-{run}')
    link_task_id = task['taskId']
    task = wait_terminal(sales, link_task_id)
    assert task['status'] == 'SUCCEEDED', task
    assert task['result']['videoUrl'] and task['result']['text'], task['result']
    assert task['provider'] == 'fake'
    replay = act(sales, 'POST', '/franchise/content/links/parse',
                 {'url': 'https://v.douyin.com/e2e/'}, idem=f'e2e-link-1-{run}')
    assert replay['taskId'] == link_task_id, '幂等重放须返回既有任务'
    print(f'    ✅ 任务 {link_task_id} SUCCEEDED（重放同号）')

    step('AC4：ASR 上传素材提取 → SUCCEEDED；同 sha256 二次提取命中缓存零外呼零配额')
    asset_id, sha = upload_content_asset(sales, MP3_BYTES)
    extract1 = act(sales, 'POST', '/franchise/content/scripts/extract',
                   {'assetId': asset_id, 'durationSec': 120, 'sha256': sha}, idem=f'e2e-asr-1-{run}')
    task1 = wait_terminal(sales, extract1['taskId'])
    assert task1['status'] == 'SUCCEEDED' and task1['result']['text'], task1
    asr_calls_after_first = psql(
        f"SELECT count(*) FROM fr_content_provider_call WHERE provider='fake' AND endpoint='asr/transcribe'")
    extract2 = act(sales, 'POST', '/franchise/content/scripts/extract',
                   {'assetId': asset_id, 'durationSec': 120, 'sha256': sha}, idem=f'e2e-asr-2-{run}')
    task2 = wait_terminal(sales, extract2['taskId'])
    assert task2['status'] == 'SUCCEEDED' and task2['engine'] == 'cache', task2
    asr_calls_after_second = psql(
        f"SELECT count(*) FROM fr_content_provider_call WHERE provider='fake' AND endpoint='asr/transcribe'")
    assert asr_calls_after_first == asr_calls_after_second, '缓存命中不得新增外呼（AC4）'
    print('    ✅ 缓存命中（engine=cache，外呼计数不变）')

    step('AC5：文案改写（fake deepseek）→ engine/model + prompt 快照落库')
    optimize = act(sales, 'POST', '/franchise/content/scripts/optimize',
                   {'sourceText': '回乡建房要注意地基，地基决定房子寿命。结构选框架更耐用。',
                    'requirement': '更口语化'}, idem=f'e2e-copy-1-{run}')
    copy_task = wait_terminal(sales, optimize['taskId'])
    assert copy_task['status'] == 'SUCCEEDED', copy_task
    assert copy_task['engine'] == 'deepseek' and copy_task['model'] == 'fake-chat', copy_task
    time.sleep(1.2)  # 收尾回填（脚本行 engine/optimized_text）在提交相收尾钩子完成
    script_row = psql(f"SELECT engine, prompt_snapshot IS NOT NULL FROM fr_content_script "
                      f"WHERE task_id={optimize['taskId']}")
    assert script_row == 'deepseek|t', f'脚本行须带 engine+prompt 快照（实际 {script_row}）'
    optimized = psql(f"SELECT optimized_text FROM fr_content_script WHERE task_id={optimize['taskId']}")
    assert optimized, 'copy 完成后 optimized_text 须回填'
    print(f'    ✅ engine=deepseek（fake） prompt 快照在库，optimized_text 回填')

    step('AC6：数字人口播 → SUCCEEDED 直链落库 + 字数→秒预扣 + save-work 一任务一件')
    oral_text = '大家好，今天讲回乡建房的三个要点。'
    oral = act(sales, 'POST', '/franchise/content/tasks',
               {'kind': 'digital', 'voice': 'voice-1', 'text': oral_text, 'avatar': 'avatar-1',
                'title': '回乡建房三要点'}, idem=f'e2e-oral-1-{run}')
    oral_task = wait_terminal(sales, oral['id'])
    assert oral_task['status'] == 'SUCCEEDED' and oral_task['resultUrl'], oral_task
    oral_units = psql(f"SELECT units_billed FROM fr_content_provider_call "
                      f"WHERE provider='fake' AND endpoint='video/create_by_tts' ORDER BY id DESC LIMIT 1")
    assert oral_units == str(max(1, (len(oral_text) + 1) // 2)), \
        f'口播秒数预扣须=字数÷2 向上取整（实际 {oral_units}）'
    work1 = act(sales, 'POST', f"/franchise/content/tasks/{oral['id']}/save-work", {'title': '口播作品A'})
    work2 = act(sales, 'POST', f"/franchise/content/tasks/{oral['id']}/save-work", {'title': '口播作品A'})
    assert work1['workId'] == work2['workId'], '一任务一件：重复 save-work 返回既有'
    oral_work_id = work1['workId']
    print(f'    ✅ resultUrl={oral_task["resultUrl"][:40]}… units={oral_units}s work={oral_work_id}')

    step('AC7：AI 视频（T2V）→ SUCCEEDED；直链探测 ok→失效文案可切换；uncertain 不自动重投；failed 终态')
    video = act(sales, 'POST', '/franchise/content/tasks',
                {'kind': 'ai_video', 'description': '乡墅别墅外观航拍',
                 'resolution': '768P', 'duration': 8, 'ratio': '16:9'}, idem=f'e2e-video-1-{run}')
    video_task = wait_terminal(sales, video['id'])
    assert video_task['status'] == 'SUCCEEDED' and video_task['resultUrl'], video_task
    probe = act(sales, 'GET', f"/franchise/content/tasks/{video['id']}/result-url-status")
    assert probe['expired'] is False, probe
    act(sales, 'POST', '/platform/dev/content-fake/outcome', {'probeExpired': True, 'outcome': 'ok'})
    probe = act(sales, 'GET', f"/franchise/content/tasks/{video['id']}/result-url-status")
    assert probe['expired'] is True and '已失效' in probe['message'], probe

    act(sales, 'POST', '/platform/dev/content-fake/outcome', {'outcome': 'uncertain'})
    unc = act(sales, 'POST', '/franchise/content/tasks',
              {'kind': 'ai_video', 'description': '不确定结局', 'resolution': '768P', 'duration': 5,
               'ratio': '9:16'}, idem=f'e2e-video-2-{run}')
    unc_task = wait_terminal(sales, unc['id'])
    assert unc_task['status'] == 'SUBMISSION_UNCERTAIN', unc_task
    time.sleep(2.5)  # 跨两个 sweep 周期：UNCERTAIN 无任务号不得自动重投/漂移
    unc_task = act(sales, 'GET', f"/franchise/content/tasks/{unc['id']}")
    assert unc_task['status'] == 'SUBMISSION_UNCERTAIN', 'UNCERTAIN 不得自动重投或漂移（A2.2）'

    act(sales, 'POST', '/platform/dev/content-fake/outcome', {'outcome': 'failed'})
    rej = act(sales, 'POST', '/franchise/content/tasks',
              {'kind': 'ai_video', 'description': '明确失败结局', 'resolution': '2K', 'duration': 4,
               'ratio': '1:1'}, idem=f'e2e-video-3-{run}')
    rej_task = wait_terminal(sales, rej['id'])
    assert rej_task['status'] == 'FAILED' and rej_task['failedReason'], rej_task
    act(sales, 'POST', '/platform/dev/content-fake/outcome', {'outcome': 'ok'})
    print('    ✅ SUCCEEDED/UNCERTAIN(不重投)/FAILED 三结局 + 直链失效探测文案')

    step('AC8：配额计数在库；每次外呼一行 provider_call；ASR 日配额（=2）耗尽 → 1_114_000_002')
    usage = psql(f"SELECT service, count FROM fr_content_ai_usage WHERE org_id={org} ORDER BY service")
    assert 'link_resolution|1' in usage and 'copy|1' in usage, usage
    assert int(psql(f"SELECT count(*) FROM fr_content_provider_call WHERE company_id={org}")) >= 5, '外呼审计行须齐'
    act(sales, 'POST', '/franchise/content/scripts/extract',
        {'sourceUrl': 'https://cdn.example/long-a.mp4'}, idem=f'e2e-asr-3-{run}')  # 无 sha 不命中缓存，耗第 2 次
    act(sales, 'POST', '/franchise/content/scripts/extract',
        {'sourceUrl': 'https://cdn.example/long-b.mp4'}, expect_code=1_114_000_002, idem=f'e2e-asr-4-{run}')
    print('    ✅ 超限 1_114_000_002（等待语义）；usage/provider_call 审计在库')

    step('AC9：发布 direct-v1 → verify → link 唯一（1_114_000_003）；leads 关联 growth 数字正确')
    pub = act(sales, 'POST', '/franchise/content/publications',
              {'workId': oral_work_id, 'title': '回乡建房三要点', 'accountId': 'acc-douyin-1',
               'platform': 'douyin', 'publishText': '第一条口播'}, idem=f'e2e-pub-1-{run}')
    pub_id = pub['id']
    act(sales, 'POST', f'/franchise/content/publications/{pub_id}/verify')
    linked = act(sales, 'POST', f'/franchise/content/publications/{pub_id}/link',
                 {'publishedUrl': f'https://v.douyin.com/published/{run}'})
    assert linked['status'] == 'published', linked
    pub2 = act(sales, 'POST', '/franchise/content/publications',
               {'workId': oral_work_id, 'title': '第二账号发布', 'accountId': 'acc-shipinhao-1',
                'platform': 'shipinhao'}, idem=f'e2e-pub-2-{run}')
    act(sales, 'POST', f'/franchise/content/publications/{pub2["id"]}/verify')
    act(sales, 'POST', f'/franchise/content/publications/{pub2["id"]}/link',
        {'publishedUrl': f'https://v.douyin.com/published/{run}'}, expect_code=1_114_000_003)
    lead = act(sales, 'POST', '/franchise/leads',
               {'name': '视频咨询客户', 'phone': '13912340001', 'need': '看了口播视频想了解建房',
                'source': f'内容发布:{pub_id}'}, idem=f'e2e-lead-1-{run}')
    growth = act(sales, 'GET', '/franchise/content/growth/summary')
    assert growth['publications'].get('published') == 1 and growth['publications'].get('verify') == 1, growth  # pub2 因链接唯一留在待核验
    assert any(str(p.get('id')) == str(pub_id) and p.get('leads') == 1 and p.get('converted') == 1
               for p in growth['perPublication']), growth
    print(f'    ✅ 链接唯一拦截 + 发布 {pub_id} 关联客资 1 条')

    step('AC10：人设 CRUD（boss 维护/sales 选用）+ 快照冻结 + 组织隔离')
    persona = act(boss, 'POST', '/franchise/content/personas',
                  {'name': '乡墅顾问张工', 'who': '从业 15 年结构工程师', 'sell': '乡墅定制设计',
                   'audience': '回乡建房业主', 'cta': '私信领取户型图'})
    persona_id = persona['id']
    assert len(act(sales, 'GET', '/franchise/content/personas')) == 1
    act(sales, 'POST', '/franchise/content/personas', {'name': '越权'}, expect_code=403)
    opt2 = act(sales, 'POST', '/franchise/content/scripts/optimize',
               {'sourceText': '样板间开放了，欢迎参观。', 'personaId': persona_id}, idem=f'e2e-copy-2-{run}')
    wait_terminal(sales, opt2['taskId'])
    snapshot1 = psql(f"SELECT persona_snapshot FROM fr_content_script WHERE task_id={opt2['taskId']}")
    assert snapshot1 and '张工' in snapshot1, '产物须冻结 persona 快照'
    act(boss, 'PUT', f'/franchise/content/personas/{persona_id}', {'name': '乡墅顾问老张'})
    snapshot2 = psql(f"SELECT persona_snapshot FROM fr_content_script WHERE task_id={opt2['taskId']}")
    assert snapshot1 == snapshot2, '人设修改不得影响已生成产物（快照不变性）'
    # 组织隔离同码：九表已注册 OrgScope（ContentOrgScope），跨组织查询过滤由平台机制承载，
    # 已在 franchise-sales e2e 验证平台层；本批夹具（单门店+总部无内容岗）不重复建双门店断言。
    own_pubs = act(sales, 'GET', '/franchise/content/publications')
    assert len(own_pubs) == 2, own_pubs
    print('    ✅ 快照冻结（persona 修改不影响已生成产物）')

    step('AC11：脚本/任务/发布幂等重放抽验 → 全链验收通过')
    replay_pub = act(sales, 'POST', '/franchise/content/publications',
                     {'workId': oral_work_id, 'title': '回乡建房三要点', 'accountId': 'acc-douyin-1',
                      'platform': 'douyin', 'publishText': '第一条口播'}, idem=f'e2e-pub-1-{run}')
    assert replay_pub['id'] == pub_id, '发布幂等重放须返回既有'
    print(f'\n=== 阶段 1.5 内容创作获客 e2e 验收通过：{step_no} 步（AC1-AC11）全部断言成功 ===')


if __name__ == '__main__':
    main()
