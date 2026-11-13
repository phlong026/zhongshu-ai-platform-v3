#!/usr/bin/env python3
"""灵感库案例同步：旧版「众墅之家AI设计小程序」线上后端 → 新平台后端。

源（只读公开 API）：    https://api.zszhj.com/app-api/design/v1/...
  - GET /cases?cursor=        游标分页列表（20/页）
  - GET /cases/{caseId}       详情（title/parameters/资产 ID）
  - GET /assets/{id}/preview  图片字节（1280px，免票据）

目标（新平台 admin API）：默认 http://127.0.0.1:48080/admin-api
  - POST /system/auth/login                     登录（--username/--password）
  - POST /design/v1/cases                       建案例草稿
  - POST /design/v1/cases/{id}/images           multipart 传图（COVER/FLOOR_PLAN/ELEVATION）
  - POST /design/v1/cases/{id}/publications     上架（--publish 时）

幂等：state 文件记录 旧caseId → {newCaseId, version, imagesDone[]}；重跑自动续传。
用法：
  python3 script/sync-inspiration-cases.py --password admin123            # 全量同步（草稿）
  python3 script/sync-inspiration-cases.py --limit 2 --dry-run            # 试跑 2 条不写
  python3 script/sync-inspiration-cases.py --publish --password xxx       # 同步并上架
"""
import argparse, json, os, sys, time, urllib.request, urllib.error, uuid

SRC = 'https://api.zszhj.com/app-api/design/v1'
TENANT = '1'

def http(url, method='GET', headers=None, data=None, raw=False, timeout=30):
    req = urllib.request.Request(url, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    body = None
    if data is not None:
        body = data if isinstance(data, bytes) else json.dumps(data).encode('utf-8')
        if not isinstance(data, bytes):
            req.add_header('Content-Type', 'application/json')
    try:
        resp = urllib.request.urlopen(req, body, timeout=timeout)
        payload = resp.read()
    except urllib.error.HTTPError as e:
        payload = e.read()
        if e.code >= 400 and not raw:
            raise RuntimeError(f'{method} {url} -> HTTP {e.code}: {payload[:200]}')
    return payload if raw else json.loads(payload.decode('utf-8'))

def src_get(path):
    d = http(SRC + path, headers={'tenant-id': TENANT})
    if d.get('code') != 0:
        raise RuntimeError(f'src {path} -> {d}')
    return d['data']

def dst_post(base, token, path, payload):
    d = http(base + path, method='POST', headers={'Authorization': 'Bearer ' + token, 'tenant-id': TENANT}, data=payload)
    if d.get('code') != 0:
        raise RuntimeError(f'dst {path} -> {d}')
    return d['data']

class Session:
    """目标后端会话：token 过期（401）自动重登重试一次。"""
    def __init__(self, base, username, password):
        self.base, self.username, self.password = base, username, password
        self.token = self._login()
    def _login(self):
        login = http(self.base + '/system/auth/login', method='POST',
                     headers={'tenant-id': TENANT}, data={'username': self.username, 'password': self.password})
        if login.get('code') != 0:
            raise RuntimeError(f'登录失败: {login}')
        return login['data']['accessToken']
    def post(self, path, payload):
        try:
            return self._post(path, payload, self.token)
        except RuntimeError as e:
            if '401' in str(e) or '账号未登录' in str(e):
                self.token = self._login()
                return self._post(path, payload, self.token)
            raise
    def _post(self, path, payload, token):
        d = http(self.base + path, method='POST', headers={'Authorization': 'Bearer ' + token, 'tenant-id': TENANT}, data=payload)
        if d.get('code') != 0:
            raise RuntimeError(f'dst {path} -> {d}')
        return d['data']
    def upload_image(self, case_id, version, role, floor_no, image_bytes):
        boundary = '----ZS' + uuid.uuid4().hex
        fields = {'version': str(version), 'role': role, 'publicDisplay': 'true', 'generationReference': 'false'}
        if floor_no is not None:
            fields['floorNo'] = str(floor_no)
        parts = []
        for k, v in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="asset.jpg"\r\n'
                      'Content-Type: image/jpeg\r\n\r\n').encode() + image_bytes + b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        body = b''.join(parts)
        def attempt(token):
            d = http(f'{self.base}/design/v1/cases/{case_id}/images', method='POST',
                     headers={'Authorization': 'Bearer ' + token, 'tenant-id': TENANT,
                              'Content-Type': f'multipart/form-data; boundary={boundary}'}, data=body, timeout=60)
            if d.get('code') != 0:
                raise RuntimeError(f'upload {role} -> {d}')
            return d['data']
        try:
            return attempt(self.token)
        except RuntimeError as e:
            if '401' in str(e) or '账号未登录' in str(e):
                self.token = self._login()
                return attempt(self.token)
            raise

def dst_upload_image(base, token, case_id, version, role, floor_no, image_bytes):
    boundary = '----ZS' + uuid.uuid4().hex
    fields = {'version': str(version), 'role': role, 'publicDisplay': 'true', 'generationReference': 'false'}
    if floor_no is not None:
        fields['floorNo'] = str(floor_no)
    parts = []
    for k, v in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="asset.jpg"\r\n'
                  'Content-Type: image/jpeg\r\n\r\n').encode() + image_bytes + b'\r\n')
    parts.append(f'--{boundary}--\r\n'.encode())
    body = b''.join(parts)
    d = http(f'{base}/design/v1/cases/{case_id}/images', method='POST',
             headers={'Authorization': 'Bearer ' + token, 'tenant-id': TENANT,
                      'Content-Type': f'multipart/form-data; boundary={boundary}'}, data=body, timeout=60)
    if d.get('code') != 0:
        raise RuntimeError(f'upload {role} -> {d}')
    return d['data']

def fetch_all_cases(limit):
    cases, cursor = [], None
    while True:
        path = '/cases?pageSize=20' + (f'&cursor={cursor}' if cursor else '')
        page = src_get(path)
        cases.extend(page.get('list') or [])
        cursor = page.get('nextCursor')
        print(f'  拉取列表：累计 {len(cases)} 条（cursor={"末页" if not cursor else "…"}）')
        if not cursor or (limit and len(cases) >= limit):
            return cases[:limit] if limit else cases

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dst', default='http://127.0.0.1:48080/admin-api')
    ap.add_argument('--username', default='admin')
    ap.add_argument('--password', required=True)
    ap.add_argument('--limit', type=int, default=0, help='只同步前 N 条（0=全量）')
    ap.add_argument('--publish', action='store_true', help='传图后立即上架（默认留草稿人工核对）')
    ap.add_argument('--dry-run', action='store_true', help='只打印计划不写入')
    ap.add_argument('--state', default=os.path.join(os.path.dirname(__file__), 'sync-cases-state.json'))
    args = ap.parse_args()

    state = json.load(open(args.state)) if os.path.exists(args.state) else {}
    sess = Session(args.dst, args.username, args.password)
    print(f'目标后端登录成功（{args.dst}）')

    cases = fetch_all_cases(args.limit)
    print(f'共 {len(cases)} 条案例待处理')
    ok = skip = fail = 0
    for idx, c in enumerate(cases, 1):
        old_id = c['caseId']
        if old_id in state:
            skip += 1
            continue
        try:
            detail = src_get(f'/cases/{old_id}')
            title = detail['title']
            p = detail.get('parameters') or {}
            floor_count = int(p.get('floorCount') or 1)
            # 楼层号归一化（旧数据两层楼带 3-4 张平面图，floorNo 越界被新后端拒绝）：
            # 合法楼层原样保留；越界图依序填入 1..floorCount 未占用槽位；槽位占满则跳过并记录
            used, plan_fps = set(), []
            for a in (detail.get('floorPlans') or []):
                aid = a['assetId'] if isinstance(a, dict) else a
                no = a.get('floorNo') if isinstance(a, dict) else None
                plan_fps.append([aid, no])
            for row in plan_fps:
                no = row[1]
                if isinstance(no, int) and 1 <= no <= floor_count and no not in used:
                    used.add(no)
            free = [n for n in range(1, floor_count + 1) if n not in used]
            skipped = []
            for row in plan_fps:
                no = row[1]
                if not (isinstance(no, int) and 1 <= no <= floor_count and no not in used):
                    if free:
                        row[1] = free.pop(0)
                        used.add(row[1])
                    else:
                        skipped.append(row[0])
                        row[1] = None  # 标记跳过
            plan = [
                ('COVER', detail.get('coverAssetId'), None),
                * [('FLOOR_PLAN', aid, no) for aid, no in plan_fps if no is not None],
                ('ELEVATION', detail.get('elevationAssetId'), None),
            ]
            plan = [x for x in plan if x[1]]
            print(f'[{idx}/{len(cases)}] {title} | 风格 {p.get("styleCode")} | 图 {len(plan)} 张'
                  + (f' | 楼层重排跳过 {len(skipped)}' if skipped else ''))
            if args.dry_run:
                continue
            created = sess.post('/design/v1/cases', {
                'title': title, 'description': detail.get('description') or '',
                'styleCode': p.get('styleCode') or 'MODERN',
                'floorCount': floor_count,
                'buildingArea': int(p.get('buildingArea') or 100),
                'faceWidth': p.get('faceWidth'), 'depth': p.get('depth'),
                'dimensionBasis': detail.get('dimensionBasis') or 'UNKNOWN'})
            new_id, version = created['caseId'], int(created.get('version') or 1)
            done_images = []
            for role, asset_id, floor_no in plan:
                img = http(f'{SRC}/assets/{asset_id}/preview', headers={'tenant-id': TENANT}, raw=True, timeout=60)
                if len(img) < 1000:
                    raise RuntimeError(f'资产 {asset_id} 字节异常（{len(img)}B）')
                up = sess.upload_image(new_id, version, role, floor_no, img)
                version = int(up.get('version') or version + 1)
                done_images.append(str(asset_id))
            if args.publish:
                sess.post(f'/design/v1/cases/{new_id}/publications', {})
            state[old_id] = {'newCaseId': new_id, 'version': version, 'images': done_images,
                             'title': title, 'published': args.publish}
            json.dump(state, open(args.state, 'w'), ensure_ascii=False, indent=1)
            ok += 1
            time.sleep(0.3)
        except Exception as e:
            fail += 1
            print(f'  ✗ 失败：{str(e)[:160]}')
    print(f'\n完成：新建 {ok} · 跳过 {skip} · 失败 {fail}（state: {args.state}）')
    sys.exit(1 if fail else 0)

if __name__ == '__main__':
    main()
