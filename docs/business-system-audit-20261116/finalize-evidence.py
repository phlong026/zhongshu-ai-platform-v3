import csv
import hashlib
import json
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / 'run-20261116-054514'
QA = ROOT / 'qa/business-system-audit-20261116/run-20261116-054514'
BASE = ROOT / '.tools/business-system-audit-20261116/run-20261116-054514/miniprogram'
AFTER = ROOT / '.tools/business-system-audit-20261116/run-20261116-054514-after/miniprogram'
LOCAL = ROOT / '.tools/business-system-audit-20261116/run-20261116-054514-local-b/miniprogram'


def hashes(folder):
    return {p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(folder.rglob('*')) if p.is_file()}


before, current, native = hashes(BASE), hashes(ROOT / 'miniprogram'), hashes(AFTER)
changed = [p for p in sorted(set(before) | set(current)) if before.get(p) != current.get(p)]
assert current == native, 'Native after-copy differs from final mini-program source'
assert current == hashes(LOCAL), 'Real local-B native project differs from final source'
expected = {'api/company-points.js','api/franchise/crm.js','pages/business/index.js',
            'pages/customers/index.js','pages/customers/index.wxss','utils/platform-crm.js',
            'pages/workflow/quote-export/index.js','pages/creation/index.js','utils/creation-link-draft.js',
            'design-migration/pages/library/index.js','design-migration/pages/library/index.wxml'}
assert set(changed) == expected, 'Unexpected concurrent source changes: ' + repr(changed)
manifest = {'createdAt': datetime.now(timezone(timedelta(hours=8))).isoformat(),
            'phase': 'ten confirmed fixes; real local-B partial acceptance complete; matching A/Design and real WeChat/phone acceptance pending',
            'miniProgramSourceFiles': len(current), 'changedMiniProgramFiles': changed,
            'nativeAfterCopyMatchesSource': True, 'realLocalBCopyMatchesSource': True,
            'sha256FileMapDigest': hashlib.sha256(json.dumps(current, sort_keys=True).encode()).hexdigest(),
            'changedFileHashes': {p: {'before': before.get(p), 'after': current.get(p)} for p in changed},
            'productAppId': json.loads((ROOT / 'project.config.json').read_text(encoding='utf-8-sig'))['appid'],
            'nativeAuditAppId': 'touristappid', 'privateTestPostgres': '127.0.0.1:55439, isolated cluster',
            'realLocalB': {'api':'127.0.0.1:18086','redis':'127.0.0.1:56389','securityMock':False,
                           'realAuthAndDatabase':True,'onlyWechatSmsProvidersStubbed':True},
            'logs': sorted(p.name for p in QA.glob('*.log'))}
backend = Path('E:/众墅之家AI赋能平台V3版-后端/平台程序')
manifest['backendFilesChangedByThisAudit'] = {}
for name in ['CustomerMapper.java','CrmIntegrationTest.java','SaleQuoteService.java','SaleQuoteBudgetIntegrationTest.java']:
    files = list((backend / 'domains/franchise').rglob(name))
    assert len(files) == 1, name
    manifest['backendFilesChangedByThisAudit'][files[0].relative_to(backend).as_posix()] = hashlib.sha256(files[0].read_bytes()).hexdigest()
(OUT / 'final-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

routes = json.loads((OUT / 'preliminary-page-inventory.json').read_text(encoding='utf-8'))['routes']
known = {
    ('pages/home/index',''): ('PUBLIC_UI_ONLY','01-guest-home.png'),
    ('pages/account/index','login'): ('REAL_LOCAL_B_LOGIN_CONSENT_FIRST_PASSWORD_CHANGE','30-local-b-real-login.png;31-local-b-account-match.png;32-local-b-login-consent.png;33-local-b-first-password-change.png'),
    ('pages/account/index','help'): ('REAL_LOCAL_UI_FEEDBACK_DISABLED','74-local-b-real-help.png'),
    ('pages/account/index','profile'): ('REAL_LOCAL_B_READ_ONLY_ENTRY','69-local-b-personal-profile.png'),
    ('pages/account/index','messages'): ('REAL_LOCAL_B_EMPTY_READ_ONLY','70-local-b-messages.png'),
    ('pages/account/index','identity'): ('REAL_LOCAL_B_READ_ONLY_ENTRY','71-local-b-identity.png'),
    ('pages/account/index','course'): ('REAL_LOCAL_UI_COURSE_CONTENT_PENDING','73-local-b-course.png'),
    ('pages/business/index',''): ('REAL_LOCAL_B_SUMMARY_AND_TABS','57-local-b-real-home.png'),
    ('pages/customers/index',''): ('REAL_LOCAL_B_SEARCH_REGION_PAGINATION','35-local-b-real-customer-page.png;36-local-b-real-cross-page-search.png;37-local-b-real-province-search.png'),
    ('pages/creation/index',''): ('REAL_LOCAL_UI_TABS_AND_TEMP_INPUT','54-local-b-tab-input-before-fix.png;55-local-b-tab-input-after-fix.png'),
    ('pages/design/index',''): ('REAL_LOCAL_B_CASE_SERVICE_BLOCKED','62-local-b-design-dependency-error.png'),
    ('pages/profile/index',''): ('REAL_LOCAL_B_ENTRY_POINTS_UNOPENED','56-local-b-real-profile.png'),
    ('pages/workflow/index','customers'): ('REAL_LOCAL_B_REGISTERED_CUSTOMER','46-local-b-register-customer.png;47-local-b-register-result.png'),
    ('pages/workflow/index','customer'): ('REAL_LOCAL_B_EDIT_FOLLOW_HISTORY','39-local-b-real-follow-modal.png;42-local-b-follow-persisted.png;43-local-b-follow-history.png;45-local-b-customer-edit-saved.png'),
    ('pages/workflow/index','requirement'): ('REAL_LOCAL_B_REQUIREMENT_VERSION_SAVED','48-local-b-customer-demand-entry.png;49-local-b-demand-saved.png'),
    ('pages/workflow/index','budget'): ('REAL_LOCAL_B_REFERENCE_QUOTE_SAVED','50-local-b-budget-entry-observation.png'),
    ('pages/workflow/index','budget-result'): ('REAL_LOCAL_B_FROZEN_QUOTE_READ','51-local-b-quote-result.png'),
    ('pages/workflow/index','contract'): ('REAL_LOCAL_B_WAIT_SIGN_CONTRACT_SAVED','78-local-b-contract-gate.png;80-local-b-contract-create-form.png;81-local-b-contract-record-saved.png'),
    ('pages/workflow/index','delivery'): ('REAL_LOCAL_B_SIGNED_CONTRACT_PRECONDITION','79-local-b-delivery-gate.png;82-local-b-delivery-after-contract.png'),
    ('pages/workflow/quote-export/index',''): ('REAL_LOCAL_B_CANVAS_EXPORT_VISUALLY_VERIFIED','quote-export-native-full.png;quote-export-native-after.png'),
    ('pages/workflow/commission/index','progress'): ('REAL_LOCAL_B_HQ_CONFIG_BLOCKED','77-local-b-commission-entry.png'),
    ('pages/studio/index','library'): ('REAL_LOCAL_UI_BUILTIN_ASSETS_ONLY','58-local-b-library.png'),
    ('pages/studio/index','tasks'): ('REAL_LOCAL_B_EMPTY_READ_ONLY','59-local-b-tasks.png'),
    ('pages/studio/index','records'): ('REAL_LOCAL_B_EMPTY_READ_ONLY','61-local-b-publish-records.png'),
    ('pages/studio/index','digital'): ('REAL_LOCAL_B_PERSONA_PRECONDITION','75-local-b-digital-entry.png'),
    ('pages/studio/characters/index',''): ('REAL_LOCAL_B_EMPTY_PERSONAS_SALES_READ_ONLY','60-local-b-characters.png'),
    ('pages/ai-video/index',''): ('REAL_LOCAL_B_DRAFT_ENTRY_ONLY','76-local-b-ai-video-entry.png'),
    ('pages/admin/tasks/index','mine'): ('REAL_LOCAL_B_EMPTY_MY_TASKS','72-local-b-my-tasks.png'),
    ('design-migration/pages/library/index',''): ('REAL_LOCAL_B_ERROR_AND_RETRY_VERIFIED','65-local-b-inspiration-dependency.png;67-local-b-inspiration-error-fixed.png;68-local-b-inspiration-retry.png'),
    ('design-migration/pages/ai-design/index',''): ('REAL_LOCAL_B_FLAT_ELEVATION_CONFIG_ONLY','63-local-b-ai-config.png;64-local-b-elevation-config.png'),
    ('design-migration/pages/budget/quick',''): ('REAL_LOCAL_B_DESIGN_DEPENDENCY_BLOCKED','66-local-b-budget-config.png')
}
rows = []
for page in routes:
    candidates = ['', *page['literalViewCandidates']]
    candidates += [view for route,view in known if route == page['route'] and view not in candidates]
    for view in candidates:
        status, evidence = known.get((page['route'],view), ('NOT_CLICKED',''))
        note = 'Literal candidate only; actual reachability and server-state combinations not exhaustively enumerated'
        if page['route'].startswith('design-migration/'):
            note += '; remaining data-dependent states require matched A/Design plus published case, pricebook or generated artifacts'
        if page['route'] == 'pages/account/index' and not view:
            note = 'Only named views have evidence; no default-view pass is claimed'
        if page['route'].startswith('design-migration/') and status == 'NOT_CLICKED':
            status = 'DEPENDENCY_BLOCKED_NOT_CLICKED'
        rows.append([page['route'],view,status,evidence,note])
with (OUT / '逐页面与候选视图状态.csv').open('w',encoding='utf-8-sig',newline='') as stream:
    writer=csv.writer(stream);writer.writerow(['route','view_candidate','status','evidence','limits']);writer.writerows(rows)
with (OUT / '逐页面执行清单.csv').open('w',encoding='utf-8-sig',newline='') as stream:
    writer=csv.writer(stream);writer.writerow(['route','view_candidate','status','evidence','limits']);writer.writerows(rows)

shots = [
    ('01-guest-home.png','public UI; visitor AppID'),('02-real-login-entry.png','public login UI; real policy API'),
    ('03-login-empty-validation.png','public empty-form validation'),
    ('05-fixture-before-customers.png','controlled: false partial totals'),
    ('06-fixture-before-cross-page-search.png','controlled: search did not query server'),
    ('07-fixture-before-search-skips-page-one.png','controlled: search starts at page two'),
    ('09-fixture-after-cross-page-search.png','controlled: search fixed'),
    ('10-fixture-after-region-search.png','controlled: province result retained; before layout fix'),
    ('13-fixture-final-customer-filter-layout.png','controlled: final readable filters'),
    ('14-fixture-creation-tab.png','controlled: creation entry only'),
    ('17-fixture-final-home-tab.png','controlled: home Tab and summary'),
    ('18-fixture-final-points-card.png','controlled: points card, one request pair'),
    ('19-fixture-final-design-empty.png','controlled: valid empty case catalogue'),
    ('20-fixture-help-entry.png','controlled: feedback explicitly unavailable')]
excluded = {'19-current-design-observation.png'}
shots += [(p.name,'real local B or explicitly shown configuration/precondition; scope is the pictured state only')
          for p in sorted(QA.glob('*.png')) if p.name[:2].isdigit() and int(p.name[:2]) >= 30 and p.name not in excluded]
shots += [('quote-export-native-full.png','before fixes: date and configuration absent'),
          ('quote-export-native-after.png','after fixes: actual unchanged canvas output, date/config/amount visually verified')]
shots = [(name, 'missing next-visit-date validation; NOT saved successfully' if name == '41-local-b-follow-saved.png' else scope) for name,scope in shots]
for filename, _ in shots:
    assert (QA / filename).is_file(), filename
(OUT / 'screenshots.json').write_text(json.dumps([{'file': str(QA / name), 'scope': scope} for name,scope in shots],ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'routes':len(routes),'candidateRows':len(rows),'sourceChanges':changed,'nativeMatches':True,
                  'acceptedScreenshots':len(shots)},ensure_ascii=False))
