from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parent.parent
qa=root/'qa/org-permissions-20261018'
mini=root/'miniprogram'
with zipfile.ZipFile(qa/'before-implementation.zip') as z:
    old={n.replace('\\','/'):z.read(n) for n in z.namelist() if n.replace('\\','/').startswith('miniprogram/') and not n.endswith('/')}
current={p.relative_to(root).as_posix():p.read_bytes() for p in mini.rglob('*') if p.is_file()}
new=set(current)-set(old)
expected={'miniprogram/utils/organization.js','miniprogram/utils/intercompany.js','miniprogram/utils/signoff.js','miniprogram/utils/workbench.js','miniprogram/pages/workflow/organization-views.js'}
assert new<=expected,('Unexpected new sources; inspect before restoring',new-expected)
draft=qa/'incomplete-role-refactor-draft.zip'
assert not draft.exists(),'Draft archive already exists'
changed=[n for n in old if current.get(n)!=old[n]]
with zipfile.ZipFile(draft,'w',zipfile.ZIP_DEFLATED) as z:
    for n,v in current.items():z.writestr(n,v)
for n in changed:
    p=(root/n).resolve();assert p.is_relative_to(mini.resolve());p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(old[n])
for n in new:
    p=(root/n).resolve();assert p.is_relative_to(mini.resolve());p.unlink()
assert all((root/n).read_bytes()==v for n,v in old.items())
report={'result':'restored','reason':'用户要求先重新评估动作授权及协作者；未完成的岗位重构已归档，运行目录恢复到重构前版本。','changedRestored':changed,'newDraftModulesArchived':sorted(new),'archive':str(draft),'baseline':str(qa/'before-implementation.zip')}
(qa/'draft-parked.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'result':'restored','files':len(changed),'newDraftModules':len(new),'archive':str(draft)},ensure_ascii=False))
