"""Static source candidates only: controller existence is not deployed/live acceptance."""
import csv
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MINI = ROOT / 'miniprogram'
BACK = Path('E:/众墅之家AI赋能平台V3版-后端/平台程序')
OUT = Path(__file__).resolve().parent / 'run-20261116-054514'


def read(p):
    return p.read_text(encoding='utf-8-sig') if p.is_file() else ''


def first_argument(s, pos):
    depth, quote, escape = 0, '', False
    for i in range(pos, len(s)):
        c = s[i]
        if quote:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == quote:
                quote = ''
        elif c in '\"\'`':
            quote = c
        elif c in '([{':
            depth += 1
        elif c in ')]}':
            if not depth:
                return s[pos:i]
            depth -= 1
        elif c == ',' and not depth:
            return s[pos:i]
    return ''


def resolve(expr, constants):
    # Split concatenations outside quoted strings and calls. Unsupported expressions stay explicit.
    parts, start, depth, quote, escape = [], 0, 0, '', False
    for i, c in enumerate(expr):
        if quote:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == quote:
                quote = ''
        elif c in '\"\'`':
            quote = c
        elif c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == '+' and not depth:
            parts.append(expr[start:i].strip()); start = i + 1
    parts.append(expr[start:].strip())
    result = ''
    for part in parts:
        if len(part) >= 2 and part[0] in '\"\'`' and part[-1] == part[0]:
            result += re.sub(r'\$\{[^}]+\}', '{*}', part[1:-1])
        elif part in constants:
            result += constants[part]
        elif result:
            result += '{*}'
        else:
            return None
        if '?' in result:
            return result.split('?', 1)[0]
    return result


def mapping_path(args):
    named = re.search(r'\b(?:path|value)\s*=\s*"([^"]*)"', args)
    first = re.match(r'\s*"([^"]*)"', args)
    return (named or first).group(1) if named or first else ''


controllers = []
for file in sorted(BACK.rglob('*.java')):
    if 'src/main/java/' not in file.as_posix() or '/target/' in file.as_posix():
        continue
    source = read(file)
    if '@RestController' not in source:
        continue
    declaration = re.search(r'^\s*public\s+(?:abstract\s+)?class\s+\w+', source, re.M)
    if not declaration:
        continue
    header = source[:declaration.start()]
    base = re.search(r'@RequestMapping\s*\((.*?)\)', header, re.S)
    prefix = mapping_path(base.group(1)) if base else ''
    for match in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\b(?:\s*\((.*?)\))?', source[declaration.end():], re.S):
        pos = declaration.end() + match.start()
        route = '/' + (prefix.strip('/') + '/' + mapping_path(match.group(2) or '').strip('/')).strip('/')
        controllers.append({'method': match.group(1).upper(), 'path': route,
                            'file': file.relative_to(BACK).as_posix(), 'line': source[:pos].count('\n') + 1,
                            'exposure': 'B-domain/platform' if file.relative_to(BACK).parts[0] in ['domains', 'platform'] else 'shared-or-A; confirm boot scan'})

calls = []
for file in sorted(MINI.rglob('*.js')):
    source = read(file)
    aliases = [m.group(1) for m in re.finditer(r'(?:const|let|var)\s+(\w+)\s*=\s*require\(["\']([^"\']+)["\']\)', source)
               if m.group(2).endswith('/http') or m.group(2) in ['./request']]
    if not aliases:
        continue
    constants = dict(re.findall(r'(?:const|let|var)\s+(\w+)\s*=\s*["\']([^"\']+)["\']\s*;', source))
    pattern = r'\b(' + '|'.join(map(re.escape, aliases)) + r')\.(get|post|put|patch|del|delete|upload)\s*\('
    for match in re.finditer(pattern, source):
        expression = first_argument(source, match.end()).strip()
        path = resolve(expression, constants)
        method = 'DELETE' if match.group(2) == 'del' else match.group(2).upper()
        if method == 'UPLOAD':
            method = 'POST'
        candidates = []
        if path and path.startswith('/'):
            route_regex = '^' + re.escape(path).replace(re.escape('{*}'), '[^/]*') + '$'
            candidates = [c for c in controllers if c['method'] == method and re.match(route_regex, re.sub(r'\{[^}]+\}', '{*}', c['path']))]
            # A JS dynamic segment and a Java named path variable are the same source pattern.
            candidates += [c for c in controllers if c['method'] == method
                           and re.sub(r'\{[^}]+\}', '{*}', c['path']) == path and c not in candidates]
        bridge = file.is_relative_to(MINI / 'design-migration') and path and path.startswith('/app-api/design/v1')
        verdict = 'BRIDGED_DESIGN_FLOW' if bridge else 'SOURCE_CONTROLLER_CANDIDATE' if candidates else 'MANUAL_REVIEW_REQUIRED'
        calls.append({'file': file.relative_to(ROOT).as_posix(), 'line': source[:match.start()].count('\n') + 1,
                      'method': method, 'expression': expression, 'pathPattern': path, 'sourceVerdict': verdict,
                      'controllerCandidates': candidates, 'liveStatus': 'NOT_VERIFIED'})

config = json.loads(read(MINI / 'app.json'))
routes = config['pages'] + [pack['root'] + '/' + page for pack in config['subPackages'] for page in pack['pages']]


def dependencies(file, seen=None):
    seen = set() if seen is None else seen
    if file in seen or not file.is_file() or not file.is_relative_to(MINI):
        return seen
    seen.add(file)
    for required in re.findall(r'require\(["\']([^"\']+)["\']\)', read(file)):
        if not required.startswith(('.', '/')):
            continue
        target = MINI / required.lstrip('/') if required.startswith('/') else file.parent / required
        target = target.resolve()
        if not target.suffix:
            target = target.with_suffix('.js')
        dependencies(target, seen)
    return seen


page_rows = []
with (OUT / '逐页面与候选视图状态.csv').open(encoding='utf-8-sig', newline='') as stream:
    observations = list(csv.DictReader(stream))
for route in routes:
    files = dependencies(MINI / (route + '.js'))
    candidates = [call for call in calls if ROOT / call['file'] in files]
    observed = [row for row in observations if row['route'] == route and row['evidence']]
    if observed:
        status = 'PARTIAL_NATIVE_OBSERVATION: ' + '; '.join(sorted({row['status'] for row in observed}))
        evidence = ';'.join(sorted({name for row in observed for name in row['evidence'].split(';')}))
    elif route.startswith('design-migration/'):
        status, evidence = 'DEPENDENCY_BLOCKED_NOT_CLICKED', ''
    else:
        status, evidence = 'NOT_CLICKED', ''
    if route.startswith('design-migration/'):
        note = '19 migrated routes: local B identity available; matched A/Design, pricebook, cases and generated artifacts still required for remaining states'
    elif route.startswith(('pages/build/', 'pages/admin/')) or route in ['pages/engineering/index','pages/management/index']:
        note = 'Only explicitly evidenced business-facing states exercised; downstream role changes, financial transitions and handoff are not fully accepted'
    else:
        note = 'Real isolated B login and six native writes verified; full production, paid generation, real WeChat and downstream handoff remain unverified'
    page_rows.append({'route': route, 'status': status, 'evidence': evidence, 'notes': note,
                      'staticDependencyCount': len(files), 'possibleHttpCalls': len(candidates),
                      'candidateFiles': sorted({c['file'] for c in candidates})})

summary = {'registeredRoutes': len(routes), 'callExpressions': len(calls), 'controllerMappings': len(controllers),
           'verdictCounts': {key: sum(c['sourceVerdict'] == key for c in calls)
                             for key in ['SOURCE_CONTROLLER_CANDIDATE','BRIDGED_DESIGN_FLOW','MANUAL_REVIEW_REQUIRED']},
           'limits': 'Regex-assisted source candidates, not exact contracts or live coverage. Dynamic wrappers, shared/A boot scanning, authorization, deployment and response DTOs need separate proof. Conditional require reachability is not actual network traffic.'}
summary['manualResolutions'] = [
    {'candidate': 'api/company-points.js GET(path)', 'resolution': 'summary and my-usage resolve to B company-points controller; observed real profile reads, additional query/session behavior covered by tests'},
    {'candidate': 'shared /infra/file/upload', 'resolution': 'B server scans cn.iocoder.yudao.module and depends on infra module; source registration confirmed, real OS file selection/upload not accepted'}]
(OUT / 'source-contract-candidates.json').write_text(json.dumps({'summary': summary, 'calls': calls, 'controllers': controllers}, ensure_ascii=False, indent=2), encoding='utf-8')
(OUT / 'page-contract-map.json').write_text(json.dumps(page_rows, ensure_ascii=False, indent=2), encoding='utf-8')
with (OUT / '逐路由状态.csv').open('w', encoding='utf-8-sig', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=['route','status','evidence','notes','staticDependencyCount','possibleHttpCalls'])
    writer.writeheader()
    writer.writerows({k: row[k] for k in writer.fieldnames} for row in page_rows)
print(json.dumps(summary, ensure_ascii=False))
