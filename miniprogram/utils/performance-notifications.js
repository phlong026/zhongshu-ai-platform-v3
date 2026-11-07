const Access = require('./access');
const clone = require('./clone');
const keys = require('./storage-keys');
const Order = require('./task-order');

const localTime = value => {
  const time = Date.parse(value || '');
  return Number.isFinite(time) ? new Date(time + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ') : '';
};

function assignment(task) {
  const history = task.history || [];
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const row = history[index];
    if (['create', 'reassign'].includes(row.action)) return { row, index };
  }
  return { row:null, index:-1 };
}

function assignedPerson(task) {
  return task.assigneePersonId || assignment(task).row?.toPersonId || task.assigneeId;
}

function assignedAt(task) {
  return assignment(task).row?.at || task.createdAt || '';
}

function companyCycles(companyId) {
  if (typeof wx === 'undefined') return [];
  const value = wx.getStorageSync(keys.performanceTasks);
  return Object.entries(value?.companies?.[companyId]?.periods || {});
}

function ownTasks(actor) {
  if (!actor.active) return [];
  return companyCycles(actor.companyId).flatMap(([period, cycle]) => (cycle.tasks || [])
    .filter(task => assignedPerson(task) === actor.personId)
    .map(task => ({ task, period, cycle })));
}

function list(account, options = {}) {
  const actor = options.actor || Access.context(account);
  const reads = new Set(account.messageReads?.[require('./design-notifications').readKey(actor)] || []);
  return Order.newest(ownTasks(actor).map(({ task, period }) => {
    const current = assignment(task), reassigned = current.row?.action === 'reassign';
    const id = ['performance-assignment', task.id, current.index].join(':');
    const target = task.type === 'manual' ? '需由本人提交完成说明与凭证' : `目标 ${task.target ?? ''}，系统按真实业务记录自动采集`;
    return {
      id, companyId:actor.companyId, userId:actor.userId, personId:actor.personId,
      type:'业务', title:reassigned ? '绩效任务已改派给你' : '新绩效任务已派发',
      description:`${task.title} · 截止 ${task.due} · ${target}`,
      date:localTime(assignedAt(task)), read:reads.has(id), route:'personal-performance', objectId:period,
      triggeredAt:assignedAt(task), derived:true
    };
  })).map(({ triggeredAt, ...message }) => message);
}

function todos(account, options = {}) {
  const actor = options.actor || Access.context(account);
  return Order.newest(ownTasks(actor).flatMap(({ task, period, cycle }) => {
    if (task.type !== 'manual' || task.manualDone || (cycle.archives || []).length) return [];
    const id = 'performance:' + task.id, triggeredAt = assignedAt(task);
    return [{
      id, key:id, name:task.title, title:'绩效任务待提交凭证',
      state:`截止 ${task.due} · 待提交完成说明与凭证`, status:`截止 ${task.due} · 待提交完成凭证`, detail:`截止 ${task.due} · 待提交完成凭证`,
      action:'提交凭证', button:'提交凭证', label:'提交凭证', actionable:true,
      url:'/pages/admin/tasks/index?view=mine&period=' + encodeURIComponent(period), actionUrl:'/pages/admin/tasks/index?view=mine&period=' + encodeURIComponent(period),
      triggeredAt, group:'performance', tag:'绩效任务', amount:'', date:task.due,
      image:'/assets/management-schedule.jpg', icon:'/assets/management-schedule.jpg'
    }];
  }));
}

function markRead(account, id, options = {}) {
  const actor = options.actor || Access.context(account);
  const message = list(account, { ...options, actor }).find(row => row.id === id);
  if (!message) throw Error('消息不存在或当前岗位无权查看');
  account.messageReads = account.messageReads || {};
  const key = require('./design-notifications').readKey(actor);
  account.messageReads[key] = [...new Set([...(account.messageReads[key] || []), id])];
  return clone(message);
}

function markAllRead(account, options = {}) {
  const actor = options.actor || Access.context(account), ids = list(account, { ...options, actor }).map(row => row.id);
  if (!ids.length) return;
  account.messageReads = account.messageReads || {};
  const key = require('./design-notifications').readKey(actor);
  account.messageReads[key] = [...new Set([...(account.messageReads[key] || []), ...ids])];
}

module.exports = { list, todos, markRead, markAllRead, assignment, assignedAt };
