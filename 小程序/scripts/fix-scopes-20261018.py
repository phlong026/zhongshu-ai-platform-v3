from pathlib import Path
m=Path('miniprogram')
def edit(name,a,b):
 p=m/name;s=p.read_text(encoding='utf8');assert a in s,(name,a);p.write_text(s.replace(a,b),encoding='utf8')
# Company scope: legacy examples belong to Quanzhou; writes merge other company records.
edit('utils/business.js','function load() {','function loadAll() {')
edit('utils/business.js',"function persist(state) { wx.setStorageSync(KEY, state); }", """function scope(){const a=wx.getStorageSync('zhongshu-account-v1');if(!a)return {active:true,companyId:'quanzhou',userId:'demo-zhang'};const s=a.session||{},c=(a.companies||[]).find(c=>c.id===s.companyId);return {active:!!(s.loggedIn&&c&&(c.roles||[]).includes(s.role)),companyId:s.companyId,userId:s.userId};}
function load(){const s=loadAll(),a=scope();s.customers=s.customers.filter(c=>a.active&&(c.companyId||'quanzhou')===a.companyId).map(c=>Object.assign(c,{companyId:a.companyId}));return s;}
function persist(state){const a=scope();if(!a.active)throw Error('请先登录并选择已授权公司');if(state.customers.some(c=>c.companyId&&c.companyId!==a.companyId))throw Error('不能写入其他公司的客户');const all=wx.getStorageSync(KEY)||initial();const others=(all.customers||[]).filter(c=>(c.companyId||'quanzhou')!==a.companyId);wx.setStorageSync(KEY,Object.assign({},state,{customers:others.concat(state.customers.map(c=>Object.assign({},c,{companyId:a.companyId})))}));}""")
edit('utils/business.js','KEY,clone,sample','KEY,scope,clone,sample')
edit('utils/business.js',"function positive(value)","function validDate(d){return /^\\d{4}-\\d{2}-\\d{2}$/.test(d||'')&&!Number.isNaN(Date.parse(d))&&new Date(d).toISOString().slice(0,10)===d;}\nfunction positive(value)")
edit('utils/business.js','latest,positive','latest,validDate,positive')
edit('pages/build/model.js','function load(){','function loadAll(){')
edit('pages/build/model.js','function save(s){wx.setStorageSync(KEY,s);}',"function load(){const s=loadAll(),a=B.scope();s.projects=s.projects.filter(p=>a.active&&(p.companyId||'quanzhou')===a.companyId).map(p=>Object.assign(p,{companyId:a.companyId}));return s;}\nfunction save(s){const a=B.scope();assert(a.active,'请先登录并选择已授权公司。');assert(!s.projects.some(p=>p.companyId&&p.companyId!==a.companyId),'不能写入其他公司的项目。');const all=wx.getStorageSync(KEY)||initial();const others=(all.projects||[]).filter(p=>(p.companyId||'quanzhou')!==a.companyId);wx.setStorageSync(KEY,Object.assign({},s,{projects:others.concat(s.projects.map(p=>Object.assign({},p,{companyId:a.companyId})))}));}")
# Validate persisted dates and duplicate design-payment references before any mutation.
edit('pages/workflow/extra-business.js',"const expected = view === 'payment-first' ? order.first : order.tail;", "const expected = view === 'payment-first' ? order.first : order.tail;\n    requireValue(validDate(f.paymentDate), '请选择有效实际付款日期。');\n    const reference=String(f.transaction||'').trim();requireValue(!B.load().customers.some(x=>['firstPayment','tailPayment'].some(k=>x[k]&&String(x[k].transaction||'').trim()===reference)), '该付款流水已登记，请核对原付款。');")
edit('pages/workflow/index.js',"if(!B.positive(f.receiptAmount)||", "if(!B.validDate(f.receiptDate)||!/^\\d+(\\.\\d{1,2})?$/.test(String(f.receiptAmount))||!B.positive(f.receiptAmount)||")
# Restrict employee goal access to the signed-in employee and granted company.
edit('pages/admin/index.js',"this.data.view==='targets'&&a.session.loggedIn&&this.options.person==='zhang'", "this.data.view==='targets'&&A.permitted(a,'account')&&a.session.companyId==='quanzhou'&&this.options.person===(a.session.staffId||a.session.userId.replace(/^demo-/,''))&&this.options.person==='zhang'")
# Studio storage is private to identity + company; migrate existing demo storage only to its original owner.
edit('utils/studio.js',"function read() {", "function storageKey(){const a=wx.getStorageSync('zhongshu-account-v1'),s=a&&a.session;if(!s)return KEY;return s.loggedIn&&s.userId==='demo-zhang'&&s.companyId==='quanzhou'?KEY:KEY+':'+(s.loggedIn?s.companyId+':'+s.userId:'guest');}\nfunction read() {")
edit('utils/studio.js',"wx.getStorageSync(KEY)","wx.getStorageSync(storageKey())")
edit('utils/studio.js',"wx.setStorageSync(KEY, state)","wx.setStorageSync(storageKey(), state)")
p=Path('scripts/verify-engineering.cjs');s=p.read_text(encoding='utf8');s=s.replace("note:'核对到账记录',date:'2026-11-04'","note:'核对到账记录',result:'核算通过',date:'2026-11-04'");p.write_text(s,encoding='utf8')
print('Applied tenant boundaries, private draft scope and input checks')
