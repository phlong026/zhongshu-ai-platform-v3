"""Create labelled local acceptance sources via real sales and engineering HTTP APIs."""
import json,sys
from pathlib import Path
sys.path.insert(0,'docs/finance-live-20261117')
import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'))
h=json.loads((j.PRIVATE/'handles.json').read_text(encoding='utf-8'))
state=j.PRIVATE/'journey-handles.json'
x=json.loads(state.read_text(encoding='utf-8')) if state.exists() else {}
p=h['project']['id'];f=a['finance']
try:
 if 'baseline' not in x:
  x['baseline']=j.api('GET',f'/franchise/finance/workbench/ledger?scope=project:{p}',actor=f)['summary']
 declaration=j.api('POST',f"/franchise/sales/stages/{h['stages'][0]['stageId']}/collect",{'amountCents':100000,'method':'bank','transactionNo':'UX-OWNER-1117','receivedDate':'2026-11-17','accountName':'公司银行'},a['sales'],'ux-owner-declaration-1117')
 x['declarationId']=str(declaration['declarationId'])
 plan=j.api('POST',f'/franchise/engineering/{p}/materials',{'nodeId':h['project']['nodes'][0]['id'],'due':'2026-11-18','lines':[{'name':'界面验收·辅材','spec':'标准件','unit':'批','quantity':1}],'note':'财务动线验收测试数据'},a['engineer'],'ux-material-plan-1117')
 supplies=[line for r in plan['plans'] for line in r['lines'] if line['name']=='界面验收·辅材']
 x['supplyId']=str(supplies[0]['supplyId'])
 terms=j.api('POST',f"/franchise/engineering/supplies/{x['supplyId']}/terms",{'supplier':'界面验收建材商','unitPrice':500,'freight':0,'contact':'验收联系人','promiseDate':'2026-11-18','basis':'本地验收采购约定'},f,'ux-material-terms-1117')
 x['payableId']=str(terms['payableId'])
 x['reimbursementId']=str(h['reimbursementId'])
 print(json.dumps({'prepared':True,'sources':['owner declaration 1000','engineering payable 500','existing design reimbursement'],'localOnly':True}))
finally:
 state.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
 Path('docs/finance-journey-20261117/prepare-http-trace.json').write_text(json.dumps(j.trace,indent=2),encoding='utf-8')
