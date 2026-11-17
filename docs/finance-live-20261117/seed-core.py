import sys,json
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'));h={}
try:
 lead=j.api('POST','/franchise/leads',{'name':'张先生·财务联调','phone':'19900111766','need':'乡墅建设财务联调','source':'联调测试'},a['sales'],'finance-joint-customer')
 print('lead',lead,flush=True)
 h['customerId']=lead['customerId']
 contract=j.api('POST','/franchise/sales/contracts',{'customerId':h['customerId'],'totalCents':60000000,'signAfterCents':20000000,'baseDoneCents':20000000,'capDoneCents':20000000},a['sales'],'finance-joint-contract')
 h['contractId']=contract['contractId'];h['stages']=contract['stages'];print('contract',h['contractId'],flush=True)
 rows=j.api('GET','/franchise/sales/contracts?customerId='+str(h['customerId']),actor=a['sales']);c=next(r['contract'] for r in rows if str(r['contract']['id'])==str(h['contractId']))
 j.api('POST',f"/franchise/sales/contracts/{h['contractId']}/sign",{'signedDate':'2026-11-17','revision':c['revision']},a['sales'],'finance-joint-contract-sign')
 project=j.api('POST','/franchise/engineering/projects',{'name':'张先生乡墅·财务联调','customer':'张先生·财务联调','city':'武汉市','phone':'19900111766','area':200,'floors':2},a['engineer'],'finance-joint-project');h['project']=project;print('projectKeys',list(project),flush=True)
finally:
 if h:(j.PRIVATE/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2))
 (j.OUT/'fixture-http-trace.json').write_text(json.dumps(j.trace,ensure_ascii=False,indent=2))

