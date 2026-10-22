const W = require('./owner-model');
const X = require('../../utils/access');
const A = require('../../utils/account');
require('../../utils/load-local-fonts')(require('./font-sources'), 'admin');
const titles = { home:'首页', finance:'财务', performance:'绩效', profile:'我的', approvals:'待我审批', 'finance-progress':'财务办理进度', business:'业务概览', engineering:'工程概览', targets:'月度目标设置', team:'团队绩效', reviews:'结果复核', activity:'操作记录', settings:'审批与分工', company:'门店资料', handover:'项目经理交接' };
module.exports = function(kind) {
  return {
    data:{ ready:false, desk:null, title:titles[kind] || '', keyword:'', financeTab:'projects', rows:[], error:'', form:{}, saving:false },
    openDesignPolicy(){require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=policy');},
    logout(){return require('../../utils/session-actions').logout();},
    openRow(e) { if (!W.allowed()) return this.onShow(); const url = require('./owner-business').rows(this.kind, W.snapshot()).find(r => r.key === e.currentTarget.dataset.key)?.url; if (url) { if (getCurrentPages().length >= 8) wx.redirectTo({url}); else wx.navigateTo({url}); } },
    onLoad(options = {}) { this.options = options; this.kind = kind === 'detail' ? titles[options.view] ? options.view : 'business' : kind; this.setData({ title:titles[this.kind] }); },
    onShow() { if (!W.allowed()) { this.setData({ready:false,desk:null,rows:[]}); wx.switchTab({url:'/pages/profile/index'}); return; } this.refresh(); },
    refresh() {
      try {
        const desk = W.snapshot(), view = this.kind;
        Object.assign(desk, require('./owner-business').forView(view, desk));
        desk.previewApprovals = desk.approvals.slice(0,2); desk.previewQueue = desk.queue.slice(0,2); desk.previewTeam = desk.team.slice(0,5); desk.secondaryMetrics = desk.metrics.slice(1); desk.dateShort = desk.date.split(' ')[0]; desk.salesTargetLabel = desk.metrics[0].target ? '/' + desk.metrics[0].target + '万' : '目标待设置';
        const keyword = this.data.keyword.trim();
        let rows = W.rowsFor(view, desk, this.options?.person || '');
        if (keyword) rows = rows.filter(r => (r.name + ' ' + r.status).includes(keyword));
        const projects = desk.projectRows.filter(p => !keyword || (p.name + ' ' + p.customer).includes(keyword));
        const form = view === 'targets' ? this.dirty ? this.data.form : { sales:desk.goals.sales || '', customers:desk.goals.customers || '', nodes:desk.goals.nodes || '', collections:desk.goals.collections || '', effective:desk.goals.effective || '', staff:Object.fromEntries(desk.team.map(p => [p.id, desk.goals.staff?.[p.id] || ''])) } : this.data.form;
        const money=n=>n===null?'待完善':'¥ '+require('./model').money(n);const operating=desk.operating;const operatingDisplay={receivable:money(operating.receivable),payable:money(operating.payable),overdue:money(operating.overdue),cash:money(operating.cash),profit:money(operating.profit),unplanned:operating.unplanned,incompleteCost:operating.incompleteCost,overhead:money(operating.overhead),payableDue:money(operating.payableDue),payableUndated:operating.payableUndated};const display = { ...desk, operating:operatingDisplay, goals:{sales:desk.goals.sales,customers:desk.goals.customers,nodes:desk.goals.nodes,staff:desk.goals.staff || {}} };
        for (const key of ['state','customers','contracts','newCustomers','projects','nodes','completed']) delete display[key];
        this.setData({ ready:true, desk:display, rows, projects, form, view, error:'' });
      } catch(e) { this.setData({error:e.message,ready:false,desk:null,rows:[]}); }
    },
    search(e) { this.setData({keyword:e.detail.value}); this.refresh(); },
    clear() { this.setData({keyword:''}); this.refresh(); },
    tab(e) { this.setData({financeTab:e.currentTarget.dataset.tab}); },
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
        payroll:'/pages/admin/operations/index?view=payroll',receivables:'/pages/admin/operations/index?view=receivables',costs:'/pages/admin/operations/index?view=costs',funds:'/pages/admin/operations/index?view=funds',operating:'/pages/admin/operations/index',business:W.detail('business'), engineering:W.detail('engineering'), approvals:W.detail('approvals'), reports:W.admin('finance'),
        goals:W.detail('targets'), team:W.detail('team'), reviews:W.detail('reviews'), activity:W.detail('activity'),
        identity:'/pages/account/index?view=identity', orders:'/pages/account/index?view=orders', procurement:'/pages/account/index?view=selection', course:'/pages/account/index?view=course',
        receipts:W.admin('finance') + '&tab=' + encodeURIComponent('收款'), payments:W.admin('finance') + '&tab=' + encodeURIComponent('付款'),
        financeProgress:W.detail('finance-progress'), staff:W.admin('staff'), settings:W.detail('settings'), handover:W.detail('handover'), company:W.detail('company'),
        profile:'/pages/account/index?view=profile', messages:'/pages/account/index?view=messages', learning:'/pages/account/index?view=learning', services:'/pages/account/index?view=services', help:'/pages/account/index?view=help', privacy:'/pages/account/index?view=privacy', rules:W.admin('rules'), engineeringTeam:W.admin('engineering-team'), businessTeam:W.admin('business-team'), financeTeam:W.admin('finance-team'), designTeam:W.admin('design-team')
      };
      let url = actions[d.action];
      if (d.key) url = [...s.approvals, ...s.queue, ...s.focus, ...s.projectRows, ...s.team, ...s.events, ...W.rowsFor(this.kind,s,this.options?.person || '')].find(r => r.key === d.key)?.url;
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
