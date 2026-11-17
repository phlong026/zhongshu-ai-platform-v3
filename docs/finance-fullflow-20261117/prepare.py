"""Prepare isolated source documents only; all finance transactions are exercised in the mini-program."""
import sys,json,subprocess
from pathlib import Path
sys.path.insert(0,'docs/finance-live-20261117')
import joint_api as j
out=Path('docs/finance-fullflow-20261117');state=j.PRIVATE/'fullflow-handles.json'
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'));logins=json.loads((j.PRIVATE/'test-logins.json').read_text(encoding='utf-8'))
for role in ['finance-lead']:
 a[role]=j.session(j.login(logins[role]['account'],logins[role]['password']),role)
(j.PRIVATE/'actors.json').write_text(json.dumps(a,ensure_ascii=False),encoding='utf-8')
x=json.loads(state.read_text(encoding='utf-8')) if state.exists() else {}
def save():state.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
try:
 if 'baseline' not in x:
  x['baseline']={k:j.api('GET','/franchise/finance/'+k,actor=a['finance']) for k in ['receipts','payments','payables','refunds']};save()
 if 'customerId' not in x:
  lead=j.api('POST','/franchise/leads',{'name':'全流程验收·李先生','phone':'19900111788','need':'乡墅财务全流程测试','source':'隔离验收'},a['sales'],'fullflow-customer-1117');x['customerId']=str(lead['customerId']);save()
 if 'contractId' not in x:
  c=j.api('POST','/franchise/sales/contracts',{'customerId':x['customerId'],'totalCents':30000000,'signAfterCents':10000000,'baseDoneCents':10000000,'capDoneCents':10000000},a['sales'],'fullflow-contract-1117');x['contractId']=str(c['contractId']);x['stages']=c['stages'];save()
 rows=j.api('GET','/franchise/sales/contracts?customerId='+x['customerId'],actor=a['sales']);c=next(r['contract'] for r in rows if str(r['contract']['id'])==x['contractId'])
 if not x.get('contractSigned'):
  j.api('POST',f"/franchise/sales/contracts/{x['contractId']}/sign",{'signedDate':'2026-11-17','revision':c['revision']},a['sales'],'fullflow-sign-1117');x['contractSigned']=True;save()
 if 'project' not in x:
  x['project']=j.api('POST','/franchise/engineering/projects',{'name':'李先生乡墅·全流程验收','customer':'全流程验收·李先生','city':'武汉市','phone':'19900111788','area':180,'floors':2},a['engineer'],'fullflow-project-1117');save()
 p=x['project']['id'];org=a['finance']['orgId'];sales=a['sales'];commission='190117001';submission='190117002';payable='190117003'
 # Known approved design source fixture. No receipt, payment or refund is inserted by SQL.
 sql=f"""BEGIN;
 UPDATE fr_eng_project SET customer_id={x['customerId']},sale_contract_id={x['contractId']},source_commission_id={commission} WHERE id={p} AND company_id={org};
 INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version) VALUES({commission},{org},100,{x['customerId']},'全流程验收·李先生','DESIGNING',1) ON CONFLICT DO NOTHING;
 INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id,mode,area,rate_cents,pricing_version) VALUES({submission},{org},100,{commission},1,'全流程验收：已审设计委托前置资料','全套设计',240000,0,'平台设计中心','STANDARD',{sales['userId']},{sales['postId']},'AREA_FULL',200,1200,1) ON CONFLICT DO NOTHING;
 INSERT INTO fr_fin_payable(id,company_id,customer_id,project_id,design_org_id,source_type,source_ref,stage,total_cents,recipient,status,agreement_ref,category) VALUES({payable},{org},{x['customerId']},{p},100,'design','{commission}:1:FULL','FULL',240000,'平台设计中心','进行中','{commission}:1:FULL','设计') ON CONFLICT DO NOTHING;
 COMMIT;"""
 result=subprocess.run(['docker','exec','-i','codex-finance-joint-pg-1117','psql','-v','ON_ERROR_STOP=1','-U','platform_owner','-d','zhongshu_platform_b'],input=sql,encoding='utf-8',capture_output=True)
 if result.returncode:raise RuntimeError(result.stderr)
 x['designPayableId']=payable;x['commissionId']=commission;save()
 if 'materialPlanId' not in x:
  plans=j.api('POST',f'/franchise/engineering/{p}/materials',{'nodeId':x['project']['nodes'][0]['id'],'due':'2026-11-18','lines':[{'name':'全流程验收·砌筑材料','spec':'标准批次','unit':'批','quantity':1}],'note':'工程申报，财务登记采购金额并付款'},a['engineer'],'fullflow-material-1117')
  for plan in plans['plans']:
   for line in plan['lines']:
    if line['name']=='全流程验收·砌筑材料':x['supplyId']=str(line['supplyId']);x['materialPlanId']=str(plan['materialPlanId']);x['materialLineId']=str(line['materialId'])
  save()
 print(json.dumps({'prepared':True,'project':x['project']['name'],'sources':['已签销售合同','工程材料计划','已审设计委托前置资料'],'financeFactsInserted':False},ensure_ascii=False))
finally:
 save();(out/'prepare-http-trace.json').write_text(json.dumps(j.trace,indent=2),encoding='utf-8')
