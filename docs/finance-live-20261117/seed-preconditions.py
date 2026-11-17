import json,subprocess
from pathlib import Path
root=Path('C:/Users/Administrator/.codex/artifacts/finance-joint-20261117/run1');h=json.loads((root/'handles.json').read_text());a=json.loads((root/'actors.json').read_text(encoding='utf-8'));p=h['project']['id'];c=h['customerId'];contract=h['contractId'];org=a['finance']['orgId'];sales=a['sales']
sql=f"""BEGIN;
INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(100,'ORG',100,'headquarters') ON CONFLICT DO NOTHING;
UPDATE fr_eng_project SET customer_id={c},sale_contract_id={contract},source_commission_id=11180001 WHERE id={p} AND company_id={org};
INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version) VALUES(11180001,{org},100,{c},'张先生·财务联调','DESIGNING',1) ON CONFLICT DO NOTHING;
INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id,mode,area,rate_cents,pricing_version) VALUES(11180002,{org},100,11180001,1,'既有已审设计委托联调样例','全套设计',240000,0,'平台设计中心','STANDARD',{sales['userId']},{sales['postId']},'AREA_FULL',200,1200,1) ON CONFLICT DO NOTHING;
INSERT INTO fr_fin_payable(id,company_id,customer_id,project_id,design_org_id,source_type,source_ref,stage,total_cents,recipient,status,agreement_ref,category) VALUES(11180003,{org},{c},{p},100,'design','11180001:1:FULL','FULL',240000,'平台设计中心','进行中','11180001:1:FULL','设计') ON CONFLICT DO NOTHING;
COMMIT;"""
r=subprocess.run(['docker','exec','-i','codex-finance-joint-pg-1117','psql','-v','ON_ERROR_STOP=1','-U','platform_owner','-d','zhongshu_platform_b'],input=sql,encoding='utf8',capture_output=True)
if r.returncode:raise RuntimeError(r.stderr)
h['designPayableId']='11180003';h['commissionId']='11180001';(root/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2));print('Source fixtures ready; no cash facts written via SQL')
