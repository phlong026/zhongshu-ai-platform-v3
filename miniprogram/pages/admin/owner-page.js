const W = require('./owner-model');
const H = require('./owner-home');
const B = require('./owner-business');
const X = require('../../utils/access');
const A = require('../../utils/account');
const routeValue = value => { try { return decodeURIComponent(value || ''); } catch (_) { return String(value || ''); } };
require('../../utils/load-local-fonts')(require('./font-sources'), 'admin');
const titles = { home:'首页', finance:'经营', performance:'绩效', profile:'我的', todos:'全部待办', approvals:'待我审批', 'finance-progress':'财务办理进度', receivables:'应收账款', payables:'应付账款', receivable:'应收原单', payable:'应付原单', business:'业务概览', engineering:'工程概览', targets:'月度目标设置', team:'团队绩效', reviews:'结果复核', activity:'操作记录', settings:'审批与分工', company:'门店资料', handover:'项目经理交接' };
module.exports = function(kind) {
  return {
    data:{ ready:false, desk:null, title:titles[kind] || '', keyword:'', todoTab:'all', homeTodos:[], homeTodoTotal:0, rows:[], total:0, pageLimit:20, record:null, error:'', form:{}, saving:false },
    openDesignPolicy(){require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=policy');},
    logout(){return require('../../utils/session-actions').logout();},
    onLoad(options = {}) { this.options = { ...options }; for (const key of ['id','person','department','tab']) this.options[key] = routeValue(options[key]); this.kind = kind === 'detail' ? titles[options.view] ? options.view : 'business' : kind; this.setData({ title:titles[this.kind] }); },
    onShow() { if (!W.allowed()) { this.setData({ready:false,desk:null,rows:[]}); wx.switchTab({url:'/pages/profile/index'}); return; } this.refresh(); },
    refresh() {
      try {
        const desk = W.snapshot(), view = this.kind;
        this.ownerSnapshot = desk;
        desk.home = H.home(desk);
        Object.assign(desk, B.forView(view, desk));
        const keyword = this.data.keyword.trim();
        const todoTab = this.options?.tab || 'all';
        let rows = ['receivables','payables'].includes(view) ? B.rows(view, desk) : W.rowsFor(view, desk, this.options?.person || '', this.options?.department || '', todoTab);
        if (keyword) rows = rows.filter(r => (r.name + ' ' + r.status).includes(keyword));
        const total = rows.length;
        if (['todos','receivables','payables'].includes(view)) rows = rows.slice(0, this.data.pageLimit);
        const projects = desk.projectRows.filter(p => !keyword || (p.name + ' ' + p.customer).includes(keyword));
        const form = view === 'targets' ? this.dirty ? this.data.form : { sales:desk.goals.sales || '', collections:desk.goals.collections || '', effective:desk.goals.effective || '', customers:desk.goals.customers || '', nodes:desk.goals.nodes || '', staff:Object.fromEntries(desk.team.map(p => [p.id, desk.goals.staff?.[p.id] || ''])) } : this.data.form;
        const money=n=>n===null?'待完善':'¥ '+require('./model').money(n);const operating=desk.operating;const operatingDisplay={receivable:money(operating.receivable),payable:money(operating.payable),overdue:money(operating.overdue),cash:money(operating.cash),profit:money(operating.profit),unplanned:operating.unplanned,incompleteCost:operating.incompleteCost,overhead:money(operating.overhead),payableDue:money(operating.payableDue),payableUndated:operating.payableUndated};let display = { ...desk, operating:operatingDisplay, goals:{sales:desk.goals.sales,collections:desk.goals.collections,effective:desk.goals.effective,customers:desk.goals.customers,nodes:desk.goals.nodes,staff:desk.goals.staff || {}} };
        for (const key of ['state','customers','contracts','commissions','newCustomers','projects','nodes','completed']) delete display[key];
        if (view === 'home') display = { name:desk.name, company:desk.company, date:desk.date, sample:desk.sample, period:desk.period, periodLabel:desk.periodLabel, home:desk.home };
        const homeTodos = view==='home' ? H.visibleTodos(desk,this.data.todoTab) : [];
        this.setData({ ready:true, desk:display, rows, total, projects:view==='home'?[]:projects, form, view, record:B.recordFor(view,desk,this.options?.id || ''), error:'', homeTodos, homeTodoTotal:view==='home'?H.todoRows(desk,this.data.todoTab).length:0 });
      } catch(e) { this.setData({error:e.message,ready:false,desk:null,rows:[]}); }
    },
    search(e) { this.setData({keyword:e.detail.value,pageLimit:20}); this.refresh(); },
    clear() { this.setData({keyword:'',pageLimit:20}); this.refresh(); },
    loadMore() { this.setData({pageLimit:this.data.pageLimit + 20}); this.refresh(); },
    todoTab(e) { const todoTab=e.currentTarget.dataset.tab,snapshot=this.ownerSnapshot;if(snapshot)this.setData({todoTab,homeTodos:H.visibleTodos(snapshot,todoTab),homeTodoTotal:H.todoRows(snapshot,todoTab).length}); },
    openRow(e) { const key=e.currentTarget.dataset.key,row=B.rows(this.kind,W.snapshot()).find(item=>item.key===key);if(row?.url){if(getCurrentPages().length>=8)wx.redirectTo({url:row.url});else wx.navigateTo({url:row.url});} },
    month(e) {
      if (!W.allowed()) return;
      const period = e.detail.value;
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) return;
      require('./model').mutate(s => { s.period = period; });
      this.dirty = false; this.refresh();
    },
    go(e) {
      if (!W.allowed()) return this.onShow();
      const d = e.currentTarget.dataset, s = W.snapshot();
      const actions = {
        payroll:'/pages/admin/operations/index?view=payroll',receivables:W.detail('receivables'),payables:W.detail('payables'),costs:'/pages/admin/operations/index?view=costs',funds:'/pages/admin/operations/index?view=funds',operating:'/pages/admin/operations/index',business:W.detail('business'), engineering:W.detail('engineering'), approvals:W.detail('approvals'), todos:W.detail('todos',{tab:this.data.todoTab}), reports:W.admin('finance'),
        goals:W.detail('targets'), team:W.detail('team'), reviews:W.detail('reviews'), activity:W.detail('activity'),
        receipts:W.admin('finance') + '&tab=' + encodeURIComponent('收款'), payments:W.detail('payables'),
        financeProgress:W.detail('finance-progress'), staff:W.admin('staff'), settings:W.detail('settings'), handover:W.detail('handover'), company:W.detail('company'),
        profile:'/pages/account/index?view=profile', messages:'/pages/account/index?view=messages', learning:'/pages/account/index?view=learning', services:'/pages/account/index?view=services', selection:'/pages/account/index?view=selection', help:'/pages/account/index?view=help', privacy:'/pages/account/index?view=privacy', rules:W.admin('rules'), engineeringTeam:W.admin('engineering-team'), businessTeam:W.admin('business-team'), designTeam:W.admin('design-team'), financeTeam:W.admin('finance-team')
      };
      let url = actions[d.action];
      if (d.key) url = [...s.approvals, ...s.queue, ...s.focus, ...s.projectRows, ...s.team, ...s.events, ...H.todoRows(s), ...H.home(s).engineering.sites, ...B.rows(this.kind,s), ...W.rowsFor(this.kind,s,this.options?.person || '',this.options?.department || '',this.options?.tab || 'all')].find(r => r.key === d.key)?.url;
      if (url) { if (getCurrentPages().length >= 8) wx.redirectTo({url}); else wx.navigateTo({url}); }
    },
    input(e) { this.dirty = true; const d = e.currentTarget.dataset; const form = A.clone(this.data.form); if (d.person) form.staff[d.person] = e.detail.value; else form[d.field] = e.detail.value; this.setData({form,error:''}); },
    save() {
      if (this.data.saving) return;
      try { this.setData({saving:true}); W.saveGoals(this.data.desk.period,this.data.form); this.dirty = false; this.refresh(); wx.showToast({title:'目标已保存',icon:'success'}); }
      catch(e) { this.setData({error:e.message}); wx.showToast({title:e.message,icon:'none'}); }
      finally { this.setData({saving:false}); }
    }
  };
};
