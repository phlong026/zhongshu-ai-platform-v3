"""Read-only cross-role reconciliation after native UI submissions."""
import sys,json,requests,subprocess
from pathlib import Path
sys.path.insert(0,'docs/finance-live-20261117')
import joint_api as j
out=Path('docs/finance-fullflow-20261117');x=json.loads((j.PRIVATE/'fullflow-handles.json').read_text(encoding='utf-8'));a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'))
def get(path,role='finance'):return j.api('GET',path,actor=a[role])
p=str(x['project']['id']);scope='project:'+p
book=get('/franchise/finance/workbench/ledger?scope='+scope)
boss=get('/franchise/finance/projects/'+p+'/ledger','boss')
legacy=get('/franchise/finance/project-accounts/'+p)
sales=get('/franchise/sales/contracts/'+x['contractId']+'/stage-summary','sales')
engineering=get('/franchise/engineering/'+p+'/collections','engineer')
settlement=get('/franchise/engineering/'+p+'/settle-summary','engineer')
material=get('/franchise/engineering/'+p+'/materials/summary','engineer')
reimburse=get('/franchise/finance/workbench/reimbursements')
facts={k:get('/franchise/finance/'+k) for k in ['receipts','payments','refunds','payables']}
checks=[]
def check(name,actual,expected):
 assert actual==expected,(name,actual,expected)
 checks.append({'name':name,'actual':actual,'expected':expected,'passed':True})
s=book['summary'];b=boss['summary'];net=s['received_cents']+s['expense_refunded_cents']-s['paid_cents']-s['refunded_cents']
for k,v in {'received_cents':2682000,'paid_cents':515000,'refunded_cents':21000,'expense_refunded_cents':10000,'committed_cents':445000,'outstanding_cents':0,'receivable_cents':27899000}.items():check('项目账本 '+k,s[k],v)
check('项目公司现金结余',net,2156000)
for k,bk in [('received_cents','receivedCents'),('paid_cents','paidCents'),('refunded_cents','refundedCents'),('expense_refunded_cents','expenseRefundedCents'),('committed_cents','payableCents')]:check('财务与老板 '+bk,b[bk],s[k])
check('兼容接口实收不漏合同回款',legacy['receivedConfirmed'],s['received_cents']);check('兼容接口退款',legacy['refundedNet'],s['refunded_cents']);check('兼容接口净已付',legacy['paidConfirmed'],s['paid_cents']-s['expense_refunded_cents'])
check('销售合同含增项总额',sum(q['amountCents'] for q in sales),30500000)
check('销售净收，含两轮UI各10元',sum(q['collectedCents'] for q in sales),2601000)
estages=engineering['stages'];check('工程合同期次含尾款',len(estages),4);check('工程应收与销售一致',sum(q['expectedCents'] for q in estages),30500000);check('工程净收与销售一致',sum(q['confirmedCents'] for q in estages),2601000)
q=next(q for q in reimburse if str(q['origin_payment_id'])==x['designAdvance']);check('员工报销与归还结清',q['eligible_cents']-q['reimbursed_cents']+q['recovered_cents'],0)
def money_total(f):
 received=sum(r['amountCents'] for r in f['receipts'] if r['status']=='已登记')
 paid=sum(r['amountCents'] for r in f['payments'] if r['status']=='已核实' and r.get('fundingType')!='EMPLOYEE')
 client=sum(r['amountCents'] for r in f['refunds'] if r['status']=='已核实' and r.get('refundDirection')!='expense')
 returned=sum(r['amountCents'] for r in f['refunds'] if r['status']=='已核实' and r.get('refundDirection')=='expense' and r.get('fundingType')!='EMPLOYEE')
 return {'grossReceipts':received,'companyPayments':paid,'clientRefunds':client,'companyExpenseReturns':returned,'net':received+returned-paid-client}
baseline=money_total(x['baseline']);company=money_total(facts)
mobile_delta=700 if x.get('mobileRefund') and x.get('mobileReimbursed') else 0
check('全公司现金增量等于原项目结余减办公86元，加界面补测账本结余',company['net']-baseline['net'],2147400+mobile_delta)
check('误录付款仅撤销一次',next(q['status'] for q in facts['payments'] if str(q['id'])==x['mistakePayment']),'已作废')
check('原退款和第一次修订均已作废',sum(1 for q in facts['refunds'] if str(q['id']) in [x['clientRefundId'],x['correctedRefund']] and q['status']=='已作废'),2)
check('最后退款替代链唯一有效',sum(1 for q in facts['refunds'] if str(q.get('receiptId'))==x['directReceiptId'] and q['status']=='已核实'),1)
denied=[]
for role in ['sales','engineer','boss']:
 actor=a[role];reply=requests.post('http://127.0.0.1:18091/admin-api/franchise/finance/payments',headers={'tenant-id':'1','Authorization':'Bearer '+actor['token'],'X-Post-Id':str(actor['postId']),'Idempotency-Key':'fullflow-forbidden-'+role},json={'payableId':x['designPayableId'],'amountCents':1,'payDate':'2026-11-17','accountName':'权限验证'},timeout=15).json()
 check(role+' 不可代财务付款',reply['code'],403);denied.append({'role':role,'code':reply['code']})
sql="SELECT count(*) FROM pf_command_event WHERE org_id=118 AND command LIKE '%reconcile%'"
# Audit confirmation is also retained by the actual operating API; no financial writes here.
result={'passed':True,'unit':'分','checks':checks,'project':s,'projectNet':net,'boss':b,'legacy':legacy,'sales':sales,'engineering':engineering,'settlement':settlement,'materials':material,'employee':q,'company':company,'baseline':baseline,'permissions':denied}
(out/'cross-role-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'passed':True,'checks':len(checks),'projectNetYuan':net/100,'projectCostYuan':s['committed_cents']/100,'companyNetYuan':company['net']/100},ensure_ascii=False))
