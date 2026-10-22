// Boss screens read the original business, engineering and finance records.
// Only monthly goals are stored here; approvals remain in their source flows.
const A = require('../../utils/account');
const X = require('../../utils/access');
const O = require('../../utils/organization');
const F = require('../../utils/finance-source');
const M = require('./model');
const KEY = 'zhongshu-owner-goals-v1';
const cover = '/pages/build/assets/house.jpg';
const clone = value => JSON.parse(JSON.stringify(value));
const allowed = () => { const a = X.context(); return a.active && X.isBoss(a); };
const detail = (view, extra = {}) => '/pages/admin/detail/index?view=' + view + Object.entries(extra).filter(([,v]) => v !== '' && v !== undefined).map(([k,v]) => '&' + k + '=' + encodeURIComponent(v)).join('');
const admin = (view, id = '') => '/pages/admin/index?view=' + view + (id ? '&id=' + encodeURIComponent(id) : '');
const yuan = value => M.money(Math.round(Number(value || 0) * 100));
const wan = cents => (Number(cents || 0) / 1000000).toLocaleString('en-US', { maximumFractionDigits: 2 });
const dateOf = value => String(value || '').slice(0,10);
const inMonth = (value, period) => dateOf(value).slice(0,7) === period;
function registered(c) {
  const value = c.createdAt || c.registeredAt || c.created || c.date;
  if (value) return String(value).includes('T') ? A.localDate(value) : dateOf(value);
  // Earlier registrations omitted a date field; only these known ID formats
  // contain their creation timestamp. Undated seed records remain undated.
  const stamp = /^customer(?:-from-lead)?-(\d{13})(?:-|$)/.exec(c.id || '');
  return stamp ? A.localDate(Number(stamp[1])) : '';
}
const ratio = (value, target) => target > 0 ? Math.round(value / target * 1000) / 10 : null;
const goalsStore = () => { const raw = wx.getStorageSync(KEY); return raw?.version === 1 && raw.companies ? clone(raw) : { version: 1, companies: {} }; };
function goal(period) { return goalsStore().companies[X.context().companyId]?.periods?.[period] || { sales: null, customers: null, nodes: null, staff: {} }; }
function taskUrl(t) { if(t.url)return t.url;return '/pages/' + (t.area === 'workflow' ? 'workflow' : 'admin') + '/index?view=' + t.view + '&id=' + encodeURIComponent(t.id) + (t.record ? '&record=' + encodeURIComponent(t.record) : ''); }
function snapshot() {
  X.assert(allowed(), '请使用本人已授权的老板或门店负责人身份');
  const actor = X.context(), account = A.load(), state = M.visible(M.load());
  const period = F.period(state), today = A.localDate(), d = new Date(today + 'T12:00:00');
  const customers = (wx.getStorageSync('zhongshu-business-v3')?.customers || []).filter(c => X.sameCompany(c, actor) && X.customer(c));
  const projects = (wx.getStorageSync('zhongshu-engineering-v1')?.projects || []).filter(p => X.sameCompany(p, actor) && X.project(p));
  const tasks = F.tasks(state).map(t => ({ ...t, name:t.record === 'quote' ? t.name.replace('正式报价及合同金额','正式报价审批') : t.name, url: taskUrl(t), image: cover }));
  const priority = t => t.record === 'quote' ? 0 : t.view === 'pay-approval' ? 1 : 2;
  const approvals = tasks.filter(t => t.view === 'money-review' || t.view === 'pay-approval'||t.area==='operations').sort((a,b) => priority(a) - priority(b));
  const sum = F.summary(state), goals = goal(period), staff = (state.staff || []).filter(p => p.active !== false);
  const contracts = customers.filter(c => c.contract?.file && inMonth(c.contract.date, period));
  const sales = contracts.reduce((n,c) => n + Number(c.contract.total || 0), 0);
  const newCustomers = customers.filter(c => inMonth(registered(c), period));
  // A node counts once, only after the original engineering flow explicitly completes it.
  const nodes = projects.flatMap(p => (p.plan?.nodes || []).filter(n => inMonth(n.originalEnd||n.end, period)).map(n => ({ p, n })));
  const completed = nodes.filter(({n}) => n.status === '已完成');
  const active = projects.filter(p => p.actualStart && !p.delivery && !p.archived);
  const queue = [
    ...state.receipts.filter(r => ['待核算','待登记','需补充'].includes(r.status)).map(r => ({ key: 'receipt:' + r.id, name: r.customer + ' · ' + r.name, status: r.status, amount: '¥ ' + M.money(M.amount(r)), url: admin('receipt', r.id), image: cover })),
    ...state.payments.filter(r => ['待复核','需补充'].includes(r.status)).map(r => ({ key: 'payment:' + r.id, name: r.customer + ' · ' + r.name, status: r.status, amount: '¥ ' + M.money(r.amount), url: admin('partial-payment', r.id), image: cover }))
  ];
  const linkedProjects = new Set([...state.receipts, ...state.payables, ...state.payments, ...projects].map(p => p.projectId || p.id).concat(customers.filter(c => c.contract || c.request || c.acceptance).map(c => c.projectId)));
  const projectRows = M.projects(state).filter(p => linkedProjects.has(p.id)).map(p => { const total = F.summary(state, p.id); const waiting = state.payables.filter(q => q.projectId === p.id && ['待审批','费用待老板审批'].includes(q.status)).length; return { ...p, key: 'ledger:' + p.id, image: projects.find(e => (e.projectId || e.id) === p.id)?.image || cover, income: wan(total.income), paid: wan(total.paid), waiting, pending: state.receipts.filter(r => r.projectId === p.id && ['待登记','待核算','需补充'].includes(r.status)).length, url: admin('finance', p.id) }; });
  const team = staff.filter(p => O.staffHas(p, 'sales') || O.staffHas(p, 'engineer') || false || O.staffHas(p,'finance')).map(p => {
    const business = O.staffHas(p, 'sales');
    const rows = business ? contracts.filter(c => (c.contract.actorId || X.ownerId(c)) === p.id) : completed.filter(({p:project,n}) => require('../../utils/performance').nodeOwner(project,n) === p.id);
    const facts=state.sources.filter(r=>r.person===p.id&&!r.excluded),engineering=O.staffHas(p,'engineer'),design=false,totals=require('../../utils/performance').totals(facts);const actual=business?totals.contract/100:engineering?totals.nodes:design?totals.design:totals.finance+totals.financeReview;
    const target = goals.staff?.[p.id] || null, percent = ratio(actual, target);
    return { id: p.id, key: 'member:' + p.id, name: p.name, initial: p.name.slice(0,1), kind: business ? '业务' : engineering?'工程':design?'设计':'财务', metric: business ? '业务签约' : engineering?'工程节点':design?'正式设计成果':'财务登记与复核', value: business ? wan(actual * 100) : String(actual), unit: business ? '万' : '项', target: target ? business ? wan(target * 100) : target : '', percent, width: Math.min(100, percent || 0), url: detail('team', { person: p.id }), business };
  });
  const metric = (label,value,target,unit) => { const percent = ratio(value,target); return { label, value: unit === '万' ? wan(value * 100) : value, target: target ? unit === '万' ? wan(target * 100) : target : '', unit, percent, width: Math.min(100, percent || 0) }; };
  const metrics = [metric('业务签约', sales, goals.sales, '万'), metric('新增客户', newCustomers.length, goals.customers, '位'), metric('工程计划节点', completed.length, goals.nodes, '项')];
  const reviews = (state.archives || []).filter(r => r.status === '待复核').map(r => ({ key: 'review:' + r.id, name: (staff.find(p => p.id === r.person)?.name || r.person || '历史人员') + ' · 周期复核', status: r.id + ' · 待复核', url: admin('review',r.id) })).concat((state.appeals || []).filter(r => r.status === '待复核').map(r => ({ key: 'appeal:' + r.id, name: '绩效申诉复核', status: r.period + ' · 待复核', url: admin('appeal-detail',r.id) })));
  const focus = active.map(p => { const ns = p.plan?.nodes || [], done = ns.filter(n => n.status === '已完成').length, inspection = (p.inspections || []).at(-1), rectification = (p.rectifications || []).filter(r => r.inspectionId === inspection?.id).at(-1); const risk = inspection?.result === '需整改' && !(rectification?.result === '通过' && rectification?.reviewStatus === 'passed'); return { key:p.id, name:p.name || p.customer, image:p.image || cover, stage:ns.find(n => n.status !== '已完成')?.name || '施工收尾', progress:ns.length ? Math.round(done / ns.length * 100) : 0, risk, status:risk ? '整改待复验' : '按当前施工节点推进', url:'/pages/build/index?view=overview&id=' + encodeURIComponent(p.id) }; }).sort((a,b) => Number(b.risk) - Number(a.risk));
  const operating=require('../../utils/operations').overview(state);const company = A.company(account), settingsEvents = goalsStore().companies[actor.companyId]?.events || [];
  const events = [...(state.events || []), ...settingsEvents].map((e,i) => ({ key:'event:' + i, name:e.text || ({'approve-pay':'审批付款申请','set-roles':'调整成员岗位','transfer':'办理工作交接','goals':'更新月度目标'}[e.action] || e.action || '业务操作'), status:(e.by || e.actorId || '历史经办人') + ' · ' + A.displayTime(e.at || e.effectiveAt || '').slice(0,16), at:e.at || e.effectiveAt || '', url:e.action === 'goals' ? detail('targets') : admin('transfer-history') })).sort((a,b) => b.at.localeCompare(a.at));
  return { operating, actor, state, period, periodLabel:period.replace('-','年') + '月', name:account.profile.name || actor.name, company:company?.store || company?.name || '', role:O.roles[actor.role], avatar:account.profile.avatar || '/assets/account-avatar.jpg', date:today.slice(5,7) + '月' + today.slice(8) + '日 周' + '日一二三四五六'[d.getDay()], today, sample:!!account.session.demo, income:wan(sum.income), paid:wan(sum.paid), balance:wan(sum.net), sales:wan(sales * 100), activeCount:active.length, approvals, approvalCount:approvals.length, queue, reconcileCount:state.receipts.filter(r => ['待登记','待核算'].includes(r.status)).length, reviewCount:state.payments.filter(r => r.status === '待复核').length, projectRows, metrics, team, reviews, reviewTotal:reviews.length, focus:focus.slice(0,2), customers, contracts, newCustomers, projects, nodes, completed, goals, staff, events, recentEvents:events.slice(0,2), unread:A.scoped(account, account.messages).filter(m => !m.read).length };
}
function saveGoals(period, form) {
  X.assert(allowed(), '仅本组织老板或门店负责人可以设置月度目标');
  X.assert(/^\d{4}-(0[1-9]|1[0-2])$/.test(period), '请选择有效月份');
  const s = snapshot();
  X.assert(!(s.state.archives || []).some(r => (r.id === period || r.period===period) && r.status === '已归档'), '本周期已归档，请保留原目标版本');
  const parse = (value, money = false) => { if (value === '' || value === null || value === undefined) return null; X.assert((money ? /^\d+(\.\d{1,2})?$/ : /^\d+$/).test(String(value)) && Number(value) > 0 && Number(value) <= (money ? 100000000 : 100000), '目标须为正数；人数、节点数须为整数'); return Number(value); };
  const data = { sales:parse(form.sales,true), customers:parse(form.customers), nodes:parse(form.nodes), staff:{} };
  for (const p of s.team) { const value = parse(form.staff?.[p.id], p.business); if (value !== null) data.staff[p.id] = value; }
  const store = goalsStore(), c = store.companies[s.actor.companyId] || { periods:{}, events:[] };
  const previous = c.periods[period];
  c.periods[period] = { ...data, version:(previous?.version || 0) + 1, history:[...(previous?.history || []), ...(previous ? [{ ...previous, history:undefined }] : [])], ...X.actor() };
  c.events.push({ action:'goals', text:'更新' + period + '月度目标', ...X.actor() });
  store.companies[s.actor.companyId] = c;
  wx.setStorageSync(KEY, store);
}
function rowsFor(view, s, person) {
  if (view === 'handover') return (s.state.work || []).filter(w => w.kind === 'project-responsibility').map(w => ({ key:'handover:' + w.id, name:w.customer + ' · ' + w.name, status:(s.staff.find(p => p.id === w.owner)?.name || w.owner || '待配置负责人') + ' · ' + w.stage, url:admin('transfer',w.id) }));
  if (view === 'approvals') return s.approvals;
  if (view === 'finance-progress') return s.queue;
  if (view === 'reviews') return s.reviews;
  if (view === 'activity') return s.events;
  if (view === 'business') return s.customers.map(c => ({ key:c.id, name:c.name, status:c.contract ? '已签约 · ¥ ' + yuan(c.contract.total) : '客户与设计进展', image:cover, url:'/pages/workflow/index?view=customer&id=' + encodeURIComponent(c.id) }));
  if (view === 'engineering') return s.projects.map(p => ({ key:p.id, name:p.name || p.customer, status:p.archived ? '已归档' : p.delivery ? '已交付' : p.actualStart ? '施工中' : '待开工', image:p.image || cover, url:'/pages/build/index?view=overview&id=' + encodeURIComponent(p.id) }));
  if (view === 'team' && !person) return s.team.map(p => ({ ...p, status:p.metric + ' ' + p.value + p.unit + (p.target ? ' / 目标' + p.target + p.unit : ' · 个人目标待设置') }));
  if (view === 'team') {
    const member = s.team.find(p => p.id === person); if (!member) return [];
    return s.state.sources.filter(r=>r.person===member.id&&!r.excluded&&(member.business?r.kind==='contract':member.kind==='工程'?r.kind==='node':true)).map(r=>({key:r.id,name:(r.customer||'')+' · '+r.name,status:(r.scheduled?'原计划 '+r.scheduled+' · '+(r.completed?'已完成':'待完成'):String(r.date||'')),url:r.url}));
  }
  return [];
}
module.exports = { KEY, cover, allowed, snapshot, saveGoals, rowsFor, detail, admin, ratio };
