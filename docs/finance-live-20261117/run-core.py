import sys,json
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'));h=json.loads((j.PRIVATE/'handles.json').read_text());p=h['project']['id'];s=h['stages'][0]['stageId']
def save():
 (j.PRIVATE/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2));(j.OUT/'core-http-trace.json').write_text(json.dumps(j.trace,ensure_ascii=False,indent=2))
try:
 declaration=j.api('POST',f'/franchise/sales/stages/{s}/collect',{'amountCents':10000000,'method':'bank','transactionNo':'JOINT-OWNER-1117','receivedDate':'2026-11-17','accountName':'公司银行'},a['sales'],'joint-owner-declaration');h['declaration']=declaration;print('declaration',declaration,flush=True)
 rows=j.api('GET','/franchise/finance/receipts',actor=a['finance']);assert not rows,'Declaration must not create cash';print('declaration creates zero cash: PASS',flush=True)
 declarations=j.api('GET','/franchise/sales/collection-declarations',actor=a['finance']);h['declarationId']=declarations[0]['id'];print('declaration keys',list(declarations[0]),flush=True)
 plan=j.api('POST',f'/franchise/engineering/{p}/materials',{'nodeId':h['project']['nodes'][0]['id'],'due':'2026-11-18','lines':[{'name':'基础钢筋·联调','spec':'HRB400','unit':'吨','quantity':2}],'note':'联调采购，应由公司付款'},a['engineer'],'joint-material-plan');h['plan']=plan;print('plan',plan,flush=True)
 summary=j.api('GET',f'/franchise/engineering/{p}/materials/summary',actor=a['engineer']);(j.PRIVATE/'material-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print('summary keys',list(summary),flush=True)
finally:save()
