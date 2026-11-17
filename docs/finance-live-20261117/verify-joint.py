import sys,json
sys.path.insert(0,'docs/finance-live-20261117');import joint_api as j
a=json.loads((j.PRIVATE/'actors.json').read_text());h=json.loads((j.PRIVATE/'handles.json').read_text());f=a['finance'];p=h['project']['id']
try:
 receipts=j.api('GET','/franchise/finance/receipts',actor=f);deposit=next(r for r in receipts if r['name']=='前期定金补记·联调');h['depositReceiptId']=deposit['id']
 j.api('POST',f"/franchise/sales/stages/{h['stages'][0]['stageId']}/allocate-receipt/{deposit['id']}",{},f,'joint-allocate-deposit');j.api('POST',f"/franchise/sales/stages/{h['stages'][0]['stageId']}/allocate-receipt/{deposit['id']}",{},f,'joint-allocate-deposit')
 stages=j.api('GET',f"/franchise/sales/contracts/{h['contractId']}/stage-summary",actor=a['sales']);assert stages[0]['collectedCents']==10100000
 book=j.api('GET',f'/franchise/finance/workbench/ledger?scope=project:{p}',actor=f);linked=j.api('GET','/franchise/finance/workbench/ledger?scope='+h['earlyBookScope'],actor=f);assert book['summary']==linked['summary']
 boss=j.api('GET','/franchise/performance/operating/analytics?period=2026-11',actor=a['boss']);bossledger=j.api('GET',f'/franchise/finance/projects/{p}/ledger?pageNo=1&pageSize=100',actor=a['boss']);eng=j.api('GET',f'/franchise/engineering/{p}/materials/summary',actor=a['engineer']);reim=j.api('GET','/franchise/finance/workbench/reimbursements',actor=f)
 expected={'received_cents':10100000,'paid_cents':1186000,'committed_cents':1326000,'outstanding_cents':140000,'receivable_cents':49900000}
 for k,v in expected.items():assert book['summary'][k]==v,(k,book['summary'][k],v)
 assert boss['trend'][-1]['incomeCents']==10100000;assert boss['trend'][-1]['paidCents']==1186000;assert boss['trend'][-1]['netCents']==8914000
 assert boss['totals']['receivableCents']==49900000;assert boss['totals']['payableOutstandingCents']==140000
 assert bossledger['summary']['receivedCents']==10100000;assert bossledger['summary']['paidCents']==1186000;assert bossledger['summary']['payableCents']==1326000
 assert eng['plans'][0]['paidCents']==1000000;assert eng['plans'][0]['remainingCents']==0
 assert reim[0]['eligible_cents']==240000;assert reim[0]['reimbursed_cents']==100000
 todos=j.api('GET','/platform/todos',actor=f);(j.OUT/'todos-verified.json').write_text(json.dumps(todos,ensure_ascii=False,indent=2))
 result={'passed':True,'cashReceivedCents':10100000,'companyPaidCents':1186000,'netCents':8914000,'projectCostCents':1326000,'employeeAdvancedCents':240000,'employeeReimbursedCents':100000,'employeeRemainingCents':140000,'materialPaidCents':1000000,'materialRemainingCents':0,'contractOutstandingCents':49900000,'linkedEarlyBookSameSummary':True,'depositAllocationDidNotCreateCash':True,'physicalPhoneTested':False}
 (j.OUT/'joint-consistency-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
finally:
 (j.PRIVATE/'handles.json').write_text(json.dumps(h,ensure_ascii=False,indent=2));(j.OUT/'verify-http-trace.json').write_text(json.dumps(j.trace,ensure_ascii=False,indent=2))
