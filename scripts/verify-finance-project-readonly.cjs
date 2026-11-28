const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const dir=path.resolve(__dirname,'../miniprogram/pages/admin/finance-desk');
function harness(role='boss',orgType='store',id='9007199254740999',view='project'){
 let spec,gen=1,post={roleCode:role,orgType,orgName:'TEST ONLY'},postId='p1';const calls=[];
 const http={token:()=> 'TEST ONLY',currentPostId:()=>postId,sessionGeneration:()=>gen,requestGuard:()=>{const g=gen;return()=>g===gen;},get:async()=>({})};
 const api=new Proxy({projectLedger:async(...args)=>{calls.push(['projectLedger',...args]);return{summary:{receivedCents:48000000},entries:{list:[],total:0}};}},{get:(o,k)=>o[k]||(()=>{calls.push([k]);return Promise.resolve(k==='reconciliation'?{}:[]);})});
 const req=n=>n==='../accounting/model'?require('../miniprogram/pages/admin/accounting/model'):n.endsWith('/api-scope')?()=>JSON.stringify([gen,postId,post.roleCode,post.orgType]):n.endsWith('/api/http')?http:n.endsWith('/api/auth')?{currentApiPost:()=>post}:n.endsWith('/api/finance')?api:n.endsWith('/finance-keyboard')?{start(){},stop(){}}:n.endsWith('/platform-todos-page')?{init(){},refresh(){calls.push(['todos']);}}:n.endsWith('/view-container')?{routeValue:v=>v||''}:n.endsWith('/finance-workbench-view')?require('../miniprogram/utils/finance-workbench-view'):n.endsWith('/finance-date')?require('../miniprogram/utils/finance-date'):n.endsWith('/platform-finance')?{...require('../miniprogram/utils/platform-finance'),metrics:()=>({}),shortcuts:()=>[],ledgerRows:()=>({rows:[]})}:n.endsWith('/access')?{context:()=>({})}:n.endsWith('/finance-desk')?{url:()=>''}:n==='./journey'?()=>{}:n==='./workbench'?p=>{p.loadWorkbench=()=>{};p.loadBookLedger=()=>{calls.push(['bookLedger']);};p.installWorkbenchActions=()=>{};}:n==='./platform-actions'?p=>{p.resumePayment=()=>{};p.openRegister=()=>calls.push(['openRegister']);}:n==='./design-finance'?attach:{};
 let attach;const mod={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(dir,'design-finance.js'),'utf8'),{module:mod,require:req,wx:{showToast(){}}});attach=mod.exports;
 vm.runInNewContext(fs.readFileSync(path.join(dir,'index.js'),'utf8'),{Page:p=>spec=p,require:req,wx:{showToast(){}}});
 const page={...spec,data:{...spec.data},setData(p,cb){Object.assign(this.data,p);if(cb)cb();}};page.onLoad({view,id});
 return{page,calls,api,async show(){page.onShow();await new Promise(r=>setImmediate(r));},switchPost(r='finance',org='store'){post={roleCode:r,orgType:org};postId='p2';gen++;}};
}
for(const role of ['boss','owner'])test(role+' loads only the same project ledger, with no registration attachment',async()=>{const h=harness(role);await h.show();assert.deepEqual(h.calls,[['projectLedger','9007199254740999',1,20]]);assert.equal(h.page.data.projectLedgerReadOnly,true);assert.equal(h.page.data.projectLedgerSummary.length,4);assert.equal(h.page.openRegister,undefined);});
test('non-store, missing/invalid id and non-project boss routes never load finance APIs',async()=>{for(const args of [['boss','headquarters'],['boss','store',''],['boss','store','0'],['boss','store','1/2'],['boss','store','123','flows']]){const h=harness(...args);await h.show();assert.deepEqual(h.calls,[]);}});
test('switching identity cannot paginate or reopen the old boss project',async()=>{const h=harness();await h.show();h.switchPost();await h.page.loadProjectLedger(true);await h.show();assert.equal(h.calls.length,1);assert.equal(h.page.data.projectLedgerEntries.length,0);assert.equal(h.page.data.projectLedgerSummary.length,0);});
test('switching from finance to boss cannot reuse the original route project',async()=>{const h=harness('finance');await h.show();const count=h.calls.length;h.switchPost('boss');await h.show();assert.equal(h.calls.length,count);assert.equal(h.page.data.platformApiMode,false);assert.equal(h.page.data.projectLedgerSummary.length,0);});
test('late ledger response from the previous identity never refills a cleared page',async()=>{const h=harness();let resolve;h.api.projectLedger=()=>new Promise(r=>{resolve=r;});await h.show();h.switchPost();await h.show();resolve({summary:{receivedCents:48000000},entries:{list:[],total:0}});await new Promise(r=>setImmediate(r));assert.equal(h.page.data.projectLedgerSummary.length,0);assert.equal(h.page.data.projectLedgerEntries.length,0);});
test('finance and lead retain full finance and project loading',async()=>{for(const role of ['finance','finance-lead']){const h=harness(role);await h.show();assert.equal(h.page.data.projectLedgerReadOnly,false);for(const kind of ['receipts','payments','refunds','designFees','bookLedger','todos'])assert.ok(h.calls.some(c=>c[0]===kind),kind);assert.equal(typeof h.page.openRegister,'function');}});
test('project write actions are guarded by the read-only parent, and no raw source references appear in ledger rows',()=>{
 const xml=fs.readFileSync(path.join(dir,'project-books.wxml'),'utf8');
 assert.ok(xml.includes('wx:if="{{!projectLedgerReadOnly&&!ledgerProjectError}}"'));
 for(const view of ['collection','materials','expenses'])assert.ok(xml.includes('&view='+view));
 assert.ok(!xml.includes('item.sourceRef'));assert.ok(!xml.includes('item.source_ref'));
});

test('confirmed original engineering costs preserve distinct source numbers without changing cash',async()=>{
 const h=harness(),numbers=['9007199254740993','9007199254740994'];h.api.projectLedger=async()=>({summary:{receivedCents:25000,paidCents:10000,refundedCents:0,expenseRefundedCents:0,payableCents:24000},entries:{list:numbers.map(originalId=>({id:Number('9007199254740992'),type:'CONFIRMED_ENGINEERING_COST',amountCents:12000,occurredOn:'2026-11-27',status:'已确认',originalExpenseId:originalId})),total:2}});
 await h.show();const rows=h.page.data.projectLedgerEntries;
 assert.equal(rows.map(row=>row.originalExpenseId).join(','),numbers.join(','));
 assert.equal(rows.map(row=>row.key).join(','),numbers.map(n=>'CONFIRMED_ENGINEERING_COST:'+n).join(','));
 for(const row of rows){assert.equal(row.typeLabel,'已确认工程费用（未挂应付）');assert.equal(row.sign,'');assert.equal(row.amountText,'120.00');}
 assert.ok(fs.readFileSync(path.join(dir,'project-books.wxml'),'utf8').includes('原费用单'));
 assert.equal(h.page.data.projectBookNet,'150.00');assert.equal(h.page.openRegister,undefined);
});

const { connect, render } = require('./role-page-runtime.cjs');
const realAuth = require('../miniprogram/api/auth');
const projectId = '9007199254740999', recordId = '9007199254740993', voucherId = '9007199254740995';
const ledgerRoute = '/franchise/finance/projects/' + projectId + '/ledger';
const tick = () => new Promise(resolve => setImmediate(resolve));
async function flush() { for (let i = 0; i < 8; i++) await tick(); }
const nodesText = nodes => nodes.map(node => (node.text || '') + nodesText(node.children || [])).join('');
function ledgerFixture() {
 const rows = [
  { type: 'RECEIVABLE', status: 'OPEN', details: {} },
  { type: 'PENDING_RECEIPT', status: '待登记', details: { name: '待核实合同回款', counterparty: '客户甲' } },
  { type: 'EMPLOYEE_REFUND', status: '已核实', details: { name: '交通退回员工', counterparty: '车队', voucherFileId: voucherId } },
  { type: 'PAYMENT', status: '已核实', details: { name: '运输费用', counterparty: '运输商', accountName: '公司银行', transactionNo: '原流水号', voucherFileId: voucherId, note: '已核对运输收据' }, canRefund: true, canRevise: true, canVoid: true, canCorrect: true }
 ];
 return { projectName: '甲工程', summary: { receivedCents: 100000, paidCents: 86000, refundedCents: 0, expenseRefundedCents: 0, receivableCents: 100000, payableCents: 86000 }, entries: { total: rows.length, list: rows.map(row => ({ id: Number(recordId), recordId, amountCents: 1000, occurredOn: '2026-11-28', sourceRef: 'private-source-ref', ...row })) } };
}
async function realLedger(role = 'boss', response = ledgerFixture()) {
 const runtime = await connect({ lifecycles: true });
 const env = { runtime, requests: [], downloads: [], opened: [], handler: () => undefined };
 const post = { postId: '42', userId: '701', tenantId: '1', orgId: '31', roleCode: role, orgType: 'store', orgName: '甲公司' };
 realAuth.saveLogin({ accessToken: 'ledger-token', userId: '701' }); realAuth.savePosts([post]); realAuth.switchPost('42');
 const respond = (request, data) => request.success({ statusCode: 200, data: { code: 0, data } });
 wx.request = request => {
  const route = new URL(request.url).pathname.replace('/admin-api', ''); env.requests.push({ route, request });
  if (env.handler(route, request)) return {};
  const data = route === ledgerRoute ? response : route.endsWith('/download-tickets')
   ? { downloadUrl: '/admin-api/franchise/assets/' + voucherId + '/content?token=test-ticket' }
   : route === '/platform/posts/mine' ? [post] : [];
  setImmediate(() => respond(request, data)); return {};
 };
 wx.getFileSystemManager = () => ({ statSync: () => ({ size: 20 }), readFileSync: () => '%PDF-1.4 test' });
 wx.downloadFile = request => { env.downloads.push(request); setImmediate(() => request.success({ statusCode: 200, tempFilePath: '/test/voucher.pdf' })); return { onHeadersReceived(callback) { callback({ header: { 'Content-Type': 'application/pdf' } }); } }; };
 wx.openDocument = options => env.opened.push(options);
 wx.previewImage = options => env.opened.push(options);
 env.respond = respond;
 env.switchPost = () => { realAuth.savePosts([post, { ...post, postId: '43', orgId: '32' }]); realAuth.switchPost('43'); };
 await runtime.callWxMethod('navigateTo', { url: '/pages/admin/finance-desk/index?view=project&id=' + projectId }); await flush();
 env.page = getCurrentPages().at(-1); env.ui = await runtime.currentPage();
 env.text = () => nodesText(render(path.join(dir, 'index.wxml'), env.page.data));
 return env;
}

test('真实老板及经营者项目账按原单类型打开详情与凭据，所有财务写入口均不出现', async () => {
 for (const role of ['boss', 'owner']) {
  const env = await realLedger(role), text = env.text();
  for (const label of ['合同应收', '待核实收款', '垫付退回员工', '付款']) assert.ok(text.includes(label), label);
  assert.ok(!text.includes('private-source-ref'));
  assert.equal(env.page.data.projectBookNet, '140.00');
  assert.equal(new Set(env.page.data.projectLedgerEntries.map(row => row.key)).size, 4);
  await (await env.ui.$('button[data-key="PAYMENT:' + recordId + '"]')).tap();
  for (const detail of [recordId, '运输商', '公司银行', '原流水号', '已核对运输收据']) assert.ok(env.text().includes(detail), detail);
  for (const handler of ['openRegister', 'refundBookEntry', 'openRevision', 'openPaymentVoid', 'openCorrection', 'openFinanceSupplement']) {
   assert.equal(await env.ui.$('[bindtap="' + handler + '"]'), null, handler);
   assert.equal(env.page[handler], undefined, handler);
  }
  await (await env.ui.$('[bindtap="openBookVoucher"]')).tap();
  const tickets = env.requests.filter(row => row.request.method === 'POST');
  assert.deepEqual(tickets.map(row => row.route), ['/franchise/assets/' + voucherId + '/download-tickets']);
  assert.equal(tickets[0].request.header['X-Post-Id'], '42');
  assert.equal(env.downloads[0].header['X-Post-Id'], '42');
  assert.equal(env.downloads[0].header['tenant-id'], '1');
  assert.equal(env.opened[0].filePath, '/test/voucher.pdf');
  await (await env.ui.$('[bindtap="closeBookEntry"]')).tap(); assert.equal(env.page.data.bookEntryDetail, null);
 }
});

test('无法精确恢复的旧数字编号不猜测原单或凭据', async () => {
 const fixture = ledgerFixture(); delete fixture.entries.list[3].recordId;
 fixture.entries.list[3].details.voucherFileId = Number(voucherId);
 const env = await realLedger('boss', fixture), row = env.page.data.projectLedgerEntries[3];
 assert.equal(row.id, ''); assert.equal(row.voucher_file_id, '');
 await (await env.ui.$('button[data-key="' + row.key + '"]')).tap();
 assert.equal(env.page.data.bookEntryDetail, null);
 assert.equal(env.requests.filter(row => row.request.method === 'POST').length, 0);
});

test('切岗、隐藏页面或关闭原单使未完成的凭据请求失效', async () => {
 for (const stop of [env => env.switchPost(), env => env.page.onHide(), env => env.page.closeBookEntry()]) {
  const env = await realLedger();
  await (await env.ui.$('button[data-key="PAYMENT:' + recordId + '"]')).tap();
  let pending;
  env.handler = (route, request) => { if (route.endsWith('/download-tickets')) { pending = request; return true; } };
  const opening = env.page.openBookVoucher(); await flush(); assert.ok(pending);
  stop(env); env.respond(pending, { downloadUrl: '/admin-api/franchise/assets/' + voucherId + '/content?token=test-ticket' });
  await opening;
  assert.equal(env.downloads.length, 0); assert.equal(env.opened.length, 0);
 }
});

test('退给员工的款保持中性，只有公司收到的钱显示入账方向', async () => {
 const fixture = ledgerFixture();
 const common = { amountCents: 8000, occurredOn: '2026-11-28', status: '已核实' };
 const employee = fixture.entries.list.find(row => row.type === 'EMPLOYEE_REFUND');
 Object.assign(employee, common);
 const company = { ...common, type: 'EXPENSE_REFUND', recordId: '9007199254741001', details: { name: '退款退回公司' } };
 const recovery = { ...common, type: 'RECEIPT', recordId: '9007199254741002', details: { name: '员工归还公司' } };
 fixture.entries.list.push(company, recovery);
 fixture.entries.total += 2;
 fixture.summary.receivedCents += 8000;
 fixture.summary.expenseRefundedCents += 8000;
 const env = await realLedger('boss', fixture);
 const rows = env.page.data.projectLedgerEntries;
 const refund = rows.find(row => row.type === 'EMPLOYEE_REFUND');
 assert.equal(refund.typeLabel, '垫付退回员工');
 assert.equal(refund.amountText, '80.00');
 assert.equal(refund.sign, '');
 assert.equal(refund.tone, '');
 for (const row of rows.filter(row => ['RECEIPT', 'EXPENSE_REFUND'].includes(row.type))) {
  assert.equal(row.sign, '+');
  assert.equal(row.tone, 'income');
 }
 assert.equal(env.page.data.projectBookNet, '300.00', '公司汇总只反映公司实际收到的原款与退款');
 await (await env.ui.$('button[data-key="EMPLOYEE_REFUND:' + recordId + '"]')).tap();
 assert.equal(env.page.data.bookEntryDetail.id, recordId);
 assert.equal(env.page.data.bookEntryDetail.voucher_file_id, voucherId);
});
