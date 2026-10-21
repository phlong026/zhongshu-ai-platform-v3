from pathlib import Path
import json
r=Path(__file__).resolve().parents[1]
def edit(f,a,b):
 p=r/f;s=p.read_text(encoding='utf-8');assert a in s,(f,a[:90]);p.write_text(s.replace(a,b),encoding='utf-8')
edit('miniprogram/utils/access.js',"memberRoles=person&&(person.roles||[person.roleId||roleIds[person.role]])||known&&known.roles||[]", "memberRoles=person?(person.roles||[person.roleId||roleIds[person.role]]):[]")
edit('miniprogram/utils/account.js',"{companyId})})))", "{companyId,role})})))")
edit('miniprogram/utils/finance-ledger.js',"if(q.status==='待审批')add(q", "if(q.status==='待审批'&&q.applicantId!==a.staffId&&(q.source?.module!=='engineering'||X.isBoss(a)))add(q")
edit('miniprogram/pages/build/model.js',"registrar:existing?existing.registrar:Access.context().name,...Access.actor(),cents:","registrar:existing?existing.registrar:Access.context().name,version:(existing?.version||0)+1,...Access.actor(),cents:")
edit('miniprogram/utils/dashboard.js',"const destination = chooseCustomer ? 'customers&destination=' + businessView : businessView;", "const delegated={ 'hq-intake':'intake','hq-dispatch':'assign','hq-designer':'deliverable','hq-release':'release',reviewer:'review-record',customer:'confirmation'}[business.scope().role];const destination=contextId&&key==='customerDetail'&&delegated?delegated:chooseCustomer?'customers&destination='+businessView:businessView;")
# Metadata-only minification creates headroom without changing any raster asset or font.
p=r/'miniprogram/assets/manifest.json';p.write_text(json.dumps(json.loads(p.read_text(encoding='utf-8')),ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('Final guards and package metadata applied')
