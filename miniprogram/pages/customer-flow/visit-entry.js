const model = require('./model');
const { toDate } = require('../../utils/platform-crm');

const feedback = {
  INTRODUCTION: ['想进一步了解', '需要调整方案', '稍后再联系'],
  SITE_VISIT: ['认可施工质量', '想再看其他工地', '有问题需跟进']
};
const supported = ['INVITATION', 'INTRODUCTION', 'SITE_VISIT'];
const text = value => typeof value === 'string' ? value.trim() : '';
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001].includes(Number(error?.code));
const fresh = step => step.canWrite && step.status === 'TODO' && step.revision === 0 && !step.occurredOn && !step.completedAt
  && !step.note && !step.attachments?.length && !Object.values(step.fields || {}).some(v => v !== '' && v != null);

function defaultDate(step, form, now = Date.now()) {
  if (supported.includes(step.code) && fresh(step)) form.occurredOn = toDate(now);
}
function appointmentTimes(now = Date.now()) {
  const today = toDate(now), tomorrow = toDate(now + 86400000);
  const clock = new Date(now + 8 * 3600000).toISOString().slice(11, 16);
  const options = [];
  for (const date of [today, tomorrow]) {
    for (const time of ['09:00', '14:00', '16:00']) {
      if (date !== today || time > clock) options.push({ date, time, label: (date === today ? '今天 ' : '明天 ') + date.slice(5) + ' ' + time });
    }
  }
  return options.slice(0, 3);
}
function visitSites(records, context, existing = []) {
  const sites = existing.slice();
  for (const row of records || []) {
    if (!row || row.stepCode !== 'SITE_VISIT' || row.type !== 'COMPLETE' || !model.id(row.id)) continue;
    if (row.customerId != null && model.id(row.customerId) !== context.customer) continue;
    if (row.companyId != null && model.id(row.companyId) !== context.company) continue;
    const name = text(row.fields?.siteName), address = text(row.fields?.address);
    if (name && !sites.some(site => site.name === name && site.address === address)) {
      sites.push({ id: model.id(row.id), name, address, label: [name, address].filter(Boolean).join(' · ') });
    }
  }
  return sites;
}
function current(page, context) {
  return page._visitEntry === context && page._visible && page.alive(context.scope) && page.customerId === context.customer
    && page.selectedCode === context.code && page.data.workflow?.revision === context.revision && page.data.step?.canWrite;
}
function applySource(page, context, key, value) {
  if (!current(page, context) || page._request !== context.read || !page.editable() || page.data.dirty || page._pending || !value || page.data.form.fields[key]) return;
  const form = { ...page.data.form, fields: { ...page.data.form.fields, [key]: value } };
  page.setData({ form, fields: model.fieldRows(context.code, form) });
}
function prepare(page, step, draft, actor, user, api) {
  const context = page._visitEntry = { customer: page.customerId, company: model.id(page.data.workflow.companyId), scope: page._scope, read: page._request, code: step.code, revision: page.data.workflow.revision, page: 1, api };
  const show = step.canWrite && supported.includes(step.code);
  page.setData({ visitTimes: show && step.code === 'INVITATION' ? appointmentTimes() : [], visitFeedback: show ? feedback[step.code] || [] : [],
    visitSites: show && step.code === 'SITE_VISIT' ? visitSites(page.data.workflow.events, context) : [],
    visitSiteMore: show && step.code === 'SITE_VISIT' && page.data.workflow.hasMoreEvents === true, visitSiteLoading: false, visitSiteError: '' });
  if (!show || step.code !== 'INTRODUCTION' || draft || !fresh(step)) return;
  // A profile is usable only for the same account and active appointment as this authorized customer workflow.
  if (model.id(user) && model.id(actor?.userId) === model.id(user) && context.company && model.id(actor?.orgId) === context.company) {
    api.entryProfile().then(profile => {
      if (model.id(profile?.id) === model.id(user)) applySource(page, context, 'receptionist', text(profile.nickname));
    }).catch(() => {});
  }
  api.entryCustomer(context.customer).then(detail => {
    const customer = detail?.customer;
    if (model.id(customer?.id) !== context.customer || (customer.companyId != null && model.id(customer.companyId) !== context.company)) return;
    applySource(page, context, 'requirements', text(customer.summary));
  }).catch(error => {
    if (current(page, context) && page._request === context.read && revoked(error)) page.clear(model.errorText(error));
  });
}
const actions = {
  chooseVisitEntry(event) {
    const context = this._visitEntry;
    if (!context || !current(this, context) || !this.editable()) return;
    const { kind, index } = event.currentTarget.dataset;
    const form = { ...this.data.form, fields: { ...this.data.form.fields } };
    if (kind === 'time' && context.code === 'INVITATION') {
      const choice = this.data.visitTimes[index]; if (!choice) return;
      form.appointmentDate = choice.date; form.appointmentTime = choice.time;
    } else if (kind === 'site' && context.code === 'SITE_VISIT') {
      const site = this.data.visitSites[index]; if (!site) return;
      form.fields.siteName = site.name; form.fields.address = site.address;
    } else if (kind === 'feedback' && feedback[context.code]) {
      const choice = this.data.visitFeedback[index]; if (!choice) return;
      const previous = String(form.fields.feedback || '');
      if (previous.split('\n').includes(choice)) return;
      const next = previous ? previous + '\n' + choice : choice;
      if (next.length > 4000) return wx.showToast({ title: '反馈最多4000字', icon: 'none' });
      form.fields.feedback = next;
    } else return;
    this.change(form);
  },
  async moreVisitSites() {
    const context = this._visitEntry;
    if (!context || !current(this, context) || context.code !== 'SITE_VISIT' || !this.data.visitSiteMore || this.data.visitSiteLoading) return;
    const next = context.page + 1;
    this.setData({ visitSiteLoading: true, visitSiteError: '' });
    try {
      const result = await context.api.records(context.customer, next);
      if (!current(this, context)) return;
      if (!Array.isArray(result?.list)) throw Error('带看记录暂不可用，请重试');
      context.page = next;
      this.setData({ visitSites: visitSites(result.list, context, this.data.visitSites), visitSiteMore: result.list.length > 0 && next * 20 < Number(result.total), visitSiteLoading: false });
    } catch (error) {
      if (!current(this, context)) return;
      if (revoked(error)) return this.clear(model.errorText(error));
      this.setData({ visitSiteLoading: false, visitSiteError: model.errorText(error) });
    }
  }
};
module.exports = { defaultDate, appointmentTimes, prepare, actions };
