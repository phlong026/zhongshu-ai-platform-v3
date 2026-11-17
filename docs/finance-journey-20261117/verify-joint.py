import sys,json
from pathlib import Path
sys.path.insert(0,'docs/finance-live-20261117')
import joint_api as j
out=Path('docs/finance-journey-20261117')
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'));h=json.loads((j.PRIVATE/'handles.json').read_text(encoding='utf-8'));x=json.loads((j.PRIVATE/'journey-handles.json').read_text(encoding='utf-8'));f=a['finance'];p=h['project']['id']
def get(path,role='finance'):return j.api('GET',path,actor=a[role])
try:
 book=get(f'/franchise/finance/workbench/ledger?scope=project:{p}')['summary'];company=get('/franchise/finance/workbench/ledger?scope=company')['summary']
 expected={'received_cents':10200000,'paid_cents':1246000,'refunded_cents':10000,'expense_refunded_cents':5000,'committed_cents':1376000,'outstanding_cents':135000,'receivable_cents':49810000}
 assert book==expected,(book,expected)
 assert company['paid_cents']==8600 and company['committed_cents']==8600
 sales=get(f"/franchise/sales/contracts/{h['contractId']}/stage-summary",'sales');assert sales[0]['collectedCents']==10190000;assert sales[0]['balanceCents']==9810000
 eng=get(f'/franchise/engineering/{p}/materials/summary','engineer');line=next(r for plan in eng['plans'] for r in plan['lines'] if str(r['payableId'])==x['payableId']);assert (line['totalCents'],line['paidCents'],line['remainingCents'])==(50000,15000,35000)
 reimb=next(r for r in get('/franchise/finance/workbench/reimbursements') if str(r['payable_id'])==x['reimbursementId']);assert (reimb['eligible_cents'],reimb['reimbursed_cents'])==(240000,140000)
 boss=get(f'/franchise/finance/projects/{p}/ledger?pageNo=1&pageSize=100','boss')['summary'];assert boss['receivedCents']==book['received_cents'];assert boss['paidCents']==book['paid_cents'];assert boss['payableCents']==book['committed_cents'];assert boss['refundedCents']==book['refunded_cents'];assert boss['expenseRefundedCents']==book['expense_refunded_cents']
 analytics=get('/franchise/performance/operating/analytics?period=2026-11','boss');assert analytics['trend'][-1]['netCents']==8940400;assert analytics['totals']['payableOutstandingCents']==135000;assert analytics['totals']['receivableCents']==49810000
 receipts=get('/franchise/finance/receipts');payments=get('/franchise/finance/payments');refunds=get('/franchise/finance/refunds')
 for key,rows in [('receiptId',receipts),('paymentId',payments),('reimbursePaymentId',payments),('manualPaymentId',payments),('customerRefundId',refunds),('supplierRefundId',refunds)]:assert sum(str(r['id'])==x[key] for r in rows)==1,key
 assert not any(str(d['id'])==x['declarationId'] for d in get('/franchise/sales/collection-declarations'))
 rejected=[]
 for role in ['sales','engineer','boss']:
  try:j.api('POST','/franchise/finance/payments',{'payableId':x['payableId'],'amountCents':1,'payDate':'2026-11-17','fundingType':'COMPANY','accountName':'公司银行'},a[role],'ux-deny-'+role)
  except RuntimeError:
   assert j.trace[-1]['code']==403 or j.trace[-1]['status']==403;j.trace[-1]['role']=role;rejected.append(role)
  else:raise AssertionError('Unexpected financial write permission: '+role)
 result={'passed':True,'project':book,'companyDaily':company,'ownerNetCollectedCents':sales[0]['collectedCents'],'procurement':{k:line[k] for k in ['totalCents','paidCents','remainingCents']},'employee':{'advancedCents':240000,'reimbursedCents':140000,'remainingCents':100000},'companyCashNetCents':8940400,'bossMatchesFinance':True,'eachNativeCommandCreatedOneRecord':True,'writeDeniedRoles':rejected,'realBankTransfers':0}
 (out/'joint-consistency-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False))
finally:(out/'joint-http-trace.json').write_text(json.dumps(j.trace,ensure_ascii=False,indent=2),encoding='utf-8')
