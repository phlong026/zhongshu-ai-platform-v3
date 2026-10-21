from pathlib import Path
import zipfile,json,hashlib
root=Path(__file__).resolve().parent.parent
qa=root/'qa/v2-implementation-20261018';qa.mkdir(exist_ok=True)
assert not (qa/'before-source.zip').exists(),'V2 already started; inspect progress before rerunning'
mini=root/'miniprogram'
with zipfile.ZipFile(qa/'before-source.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in mini.rglob('*'):
        if p.is_file():z.write(p,p.relative_to(root).as_posix())
with zipfile.ZipFile(root/'qa/org-permissions-20261018/incomplete-role-refactor-draft.zip') as z:
    names={n.replace('\\','/'):n for n in z.namelist() if not n.endswith('/')}
    for n,key in names.items():
        p=(root/n).resolve();assert p.is_relative_to(mini.resolve());p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(key))
for asset in ['management-guest.jpg','course.jpg']:
    p=mini/'assets'/asset
    if p.exists():
        target=qa/'unused-assets'/asset;target.parent.mkdir(exist_ok=True);p.replace(target)
(qa/'progress.json').write_text(json.dumps({'phase':'implementing-v2','baseline':'before-source.zip','note':'复用归档草稿中的通用组件，V1假设会按V2及兼岗要求重新实现；尚未验收。'},ensure_ascii=False,indent=2),encoding='utf8')
print('V2 baseline backed up; reusable draft modules restored for implementation.')
