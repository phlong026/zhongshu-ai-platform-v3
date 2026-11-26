/**
 * 客户跟进状态弹层（标记流失 / 暂缓跟进 / 恢复跟进）的页面行为，八步跟进页使用。
 * 客户工作区的“暂缓 / 标记流失 / 恢复”按钮带 status 参数进入本页后自动打开。
 * 页面提供两个方法：statusContext() 返回 {customerId, api}（api 含 workflow 与 workflowStatus），不可办理时返回空；
 * statusDone() 在提交成功后刷新页面。流程版本以服务端当前值为准，别人刚改过就提示刷新，不覆盖。
 */
const PF = require('../../utils/platform-crm');

/** 与后端 CustomerWorkflowModel.LOST_REASONS 一致；OTHER 由经办人自己填写原因。 */
const LOST_REASONS = [
  { code: 'PRICE', label: '价格超预算' },
  { code: 'COMPETITOR', label: '选了同行' },
  { code: 'PLAN', label: '方案不满意' },
  { code: 'DEFERRED', label: '暂不建或推迟' },
  { code: 'UNREACHABLE', label: '联系不上' },
  { code: 'OTHER', label: '其他' }
];
const SHEET_TITLE = { lost: '标记流失', paused: '暂缓跟进', resume: '恢复跟进' };
const today = () => PF.today();

const actions = {
  openStatus(e) {
    const kind = e && e.currentTarget && e.currentTarget.dataset.kind;
    if (!SHEET_TITLE[kind] || (this.data.statusSheet && this.data.statusSheet.busy)) return;
    if (typeof this.statusContext !== 'function' || !this.statusContext()) return;
    this.setData({ statusSheet: { kind, title: SHEET_TITLE[kind], reasonCode: '', reasonText: '', nextFollowOn: '', error: '', busy: false } });
  },
  statusPick(e) {
    const sheet = this.data.statusSheet;
    if (!sheet || sheet.busy) return;
    this.setData({ 'statusSheet.reasonCode': String(e.currentTarget.dataset.code || ''), 'statusSheet.error': '' });
  },
  statusInput(e) {
    const sheet = this.data.statusSheet;
    if (!sheet || sheet.busy) return;
    this.setData({ 'statusSheet.reasonText': String(e.detail.value || ''), 'statusSheet.error': '' });
  },
  statusDate(e) {
    const sheet = this.data.statusSheet;
    if (!sheet || sheet.busy) return;
    this.setData({ 'statusSheet.nextFollowOn': String(e.detail.value || ''), 'statusSheet.error': '' });
  },
  closeStatus() {
    const sheet = this.data.statusSheet;
    if (sheet && sheet.busy) return;
    this.setData({ statusSheet: null });
  },
  async submitStatus() {
    const sheet = this.data.statusSheet;
    if (!sheet || sheet.busy) return;
    const fail = error => this.setData({ 'statusSheet.busy': false, 'statusSheet.error': error });
    const text = String(sheet.reasonText || '').trim();
    const body = { status: sheet.kind === 'lost' ? 'LOST' : sheet.kind === 'paused' ? 'PAUSED' : 'ACTIVE' };
    if (sheet.kind === 'lost') {
      if (!sheet.reasonCode) return fail('请选择流失原因');
      if (sheet.reasonCode === 'OTHER' && !text) return fail('请填写流失原因');
      if (text.length > 1000) return fail('原因不超过 1000 字');
      body.reasonCode = sheet.reasonCode;
      body.reason = text;
    }
    if (sheet.kind === 'paused') {
      if (!text) return fail('请填写暂缓原因');
      if (text.length > 1000) return fail('原因不超过 1000 字');
      if (!sheet.nextFollowOn || sheet.nextFollowOn <= today()) return fail('请选择晚于今天的再联系日期');
      body.reason = text;
      body.nextFollowOn = sheet.nextFollowOn;
    }
    const context = this.statusContext();
    if (!context) return fail('当前岗位不能办理该客户');
    // 结果未知时沿用原键重试，服务端按幂等返回同一次变更。
    if (!this.__statusCommand || this.__statusCommand.kind !== sheet.kind) this.__statusCommand = { kind: sheet.kind, key: PF.commandKey('workflow-status') };
    this.setData({ 'statusSheet.busy': true, 'statusSheet.error': '' });
    try {
      const view = await context.api.workflow(context.customerId);
      await context.api.workflowStatus(context.customerId, { ...body, revision: view.revision }, this.__statusCommand.key);
    } catch (error) {
      if (error && (error.code === -1 || (error.code >= 500 && error.code < 600))) return fail('未收到保存确认，可直接重试');
      this.__statusCommand = null;
      return fail(PF.errorMessage(error));
    }
    this.__statusCommand = null;
    if (this.__unloaded || this.__disposed) return;
    this.setData({ statusSheet: null });
    wx.showToast({ title: sheet.kind === 'lost' ? '已标记流失' : sheet.kind === 'paused' ? '已暂缓跟进' : '已恢复跟进', icon: 'none' });
    await this.statusDone();
  }
};

module.exports = { LOST_REASONS, actions };
