/**
 * 客户工作区的八步视图：进度条节点与跟进时间线。客户工作区分包专用，口径与 utils/platform-crm 的客户行一致。
 */
const PF = require('../../utils/platform-crm');
const epochMsOf = require('../../utils/epoch-ms').epochMsOf;
const toDate = PF.toDate;
const TIMELINE_STEPS = { INVITATION: '邀约进店', INTRODUCTION: '店面讲解', HOUSE_SELECTION: '户型选择', SITE_VISIT: '带看工地', MEASUREMENT: '现场测量', QUOTATION: '别墅报价', CONTRACT: '合同签约', START: '开工大吉' };
const TIMELINE_TYPES = { SAVE: '保存记录', COMPLETE: '完成步骤', SKIP: '已跳过带看', REOPEN: '重新办理', STATUS: '更新状态', SUPPLEMENT: '补充记录', CONSULT: '再次咨询', LEGACY: '跟进记录', VISIT: '实际接待' };

function railView(flow) {

  if (!flow || !Array.isArray(flow.steps)) return [];
  return flow.steps.map((step, index) => {
    const skipped = step.status === 'SKIPPED', done = step.status === 'COMPLETED' || skipped;
    return { code: step.code, name: step.name, number: String(index + 1).padStart(2, '0'), done, skipped,
      active: !done && step.code === flow.currentStepCode };
  });
}

/** 客户工作区的跟进时间线：一条记录 = 时间 + 步骤 · 动作 + 经办人 + 说明 + 当时约定的下次跟进日期。 */
function timelineView(response) {
  const rows = response && Array.isArray(response.list) ? response.list : [];
  const total = Number(response && response.total);
  return {
    timeline: rows.map(row => {
      const step = TIMELINE_STEPS[row.stepCode] || '', type = TIMELINE_TYPES[row.type] || '更新记录';
      const created = new Date((epochMsOf(row.createdAt) || 0) + 8 * 3600e3).toISOString();
      return { id: String(row.id), kind: row.type === 'COMPLETE' ? 'done' : row.type === 'CONSULT' ? 'lead' : '',
        time: created.slice(5, 16).replace('T', ' '), title: step ? step + ' · ' + type : type, note: String(row.note || ''),
        actor: String(row.actorName || ''), files: Array.isArray(row.attachments) ? row.attachments.length : 0,
        next: row.nextFollowOn ? '约定 ' + toDate(row.nextFollowOn) + ' 跟进' : '' };
    }),
    timelineTotal: Number.isFinite(total) ? total : rows.length,
    timelineMore: rows.length > 0 && rows.length < (Number.isFinite(total) ? total : rows.length)
  };
}


module.exports = { railView, timelineView };
