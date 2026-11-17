import sys,json
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text(encoding='utf-8'));h=json.loads((j.PRIVATE/'handles.json').read_text());p=h['project']['id'];f=a['finance']
def call(method,path,body=None,actor=f,key=None):return j.api(method,path,body,actor,key)
try:
 result=call('POST',f"/franchise/sales/collection-declarations/{h['declarationId']}/confirm",{},key='joint-owner-confirm');h['receipt']=result;assert str(call('POST',f"/franchise/sales/collection-declarations/{h['declarationId']}/confirm",{},key='joint-owner-confirm')['receiptId'])==str(result['receiptId'])
 receipts=call('GET','/franchise/finance/receipts');assert sum(r['amountCents'] for r in receipts if r['status']=='已登记')==10000000
 h['supplyId']=h['plan']['plans'][0]['lines'][0]['supplyId'];terms=call('POST',f"/franchise/engineering/supplies/{h['supplyId']}/terms",{'supplier':'城南建材·联调','unitPrice':5000,'freight':0,'contact':'张师傅','promiseDate':'2026-11-18','basis':'采购合同·联调'},f,'joint-material-terms');h['terms']=terms;print('terms',terms,flush=True)
 payables=call('GET','/franchise/finance/payables');q=next(q for q in payables if str(q['id'])==str(terms['payableId']));h['materialPayableId']=q['id'];assert q['totalCents']==1000000
 rejected=False
 try:call('POST','/franchise/finance/payments',{'payableId':q['id'],'amountCents':400000,'payDate':'2026-11-17','fundingType':'EMPLOYEE','payerUserId':a['engineer']['userId'],'payerName':'联调工程'},key='joint-engineer-advance-reject')
 except RuntimeError as e:rejected=True;print('Engineering personal advance rejected:',str(e).split(':')[-1],flush=True)
 assert rejected
 for index,amount in enumerate([400000,600000]):call('POST','/franchise/finance/payments',{'payableId':q['id'],'amountCents':amount,'payDate':'2026-11-17','fundingType':'COMPANY','accountName':'公司银行','transactionNo':f'JOINT-MAT-{index}'},key=f'joint-material-pay-{index}')
 h['designPayment']=call('POST','/franchise/finance/payments',{'payableId':h['designPayableId'],'amountCents':240000,'payDate':'2026-11-17','fundingType':'EMPLOYEE','payerUserId':a['sales']['userId'],'payerName':'联调业务','accountName':'业务个人账户','transactionNo':'JOINT-DESIGN-ADVANCE'},key='joint-design-advance');print('design payment',h['designPayment'],flush=True)
 reimb=call('GET','/franchise/finance/workbench/reimbursements');print('reimbursements',reimb,flush=True);h['reimbursementId']=reimb[0]['payable_id']
 h['reimbursePayment']=call('POST','/franchise/finance/payments',{'payableId':h['reimbursementId'],'amountCents':100000,'payDate':'2026-11-17','fundingType':'COMPANY','accountName':'公司银行','transactionNo':'JOINT-REIMBURSE-1'},key='joint-reimburse-partial')
 summary=call('GET',f'/franchise/engineering/{p}/materials/summary',actor=a['engineer']);assert summary['plans'][0]['paidCents']==1000000;assert summary['plans'][0]['remainingCents']==0;print('Engineering payment mirror: PASS',flush=True)
 boss=call('GET','/franchise/performance/operating/analytics?period=2026-11',actor=a['boss']);print('boss keys',list(boss),flush=True);(j.OUT/'boss-after-cash.json').write_text(json.dumps(boss,ensure_ascii=False,indent=2))
 ledger=call('GET',f'/franchise/finance/workbench/ledger?scope=project:{p}');(j.OUT/'project-after-cash.json').write_text(json.dumps(ledger,ensure_ascii=False,indent=2));print('project keys',list(ledger),flush=True)
finally:
 (j.PRIVATE/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2));(j.OUT/'cash-http-trace.json').write_text(json.dumps(j.trace,ensure_ascii=False,indent=2))


