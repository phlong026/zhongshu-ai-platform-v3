const http = require('../../../api/http');
const { projects } = require('../../../api/franchise/engineering');
const { commandKey } = require('../../../utils/platform-crm');

const empty = () => ({ managerTransferOpen: false, managerTransferLoading: false, managerTransferRows: [],
  managerTransferName: '', managerTransferSelected: false, managerTransferPending: false, managerTransferError: '' });
const text = e => String(e?.msg || e?.message || '转交未完成，请重试');
function guard(page) {
  const current = http.sessionGuard(), id = page.id, view = page.data.view;
  return () => current() && !page.__disposed && id === page.id && view === page.data.view;
}
function clear(page) {
  page.__managerCommand = null;
  page.__managerRead = null;
  page.__managerChoice = null;
  page.setData(empty());
}
function canOpen(page) {
  return page.__identity === String(http.currentPostId()) + ':' + http.sessionGeneration()
    && !page.__inactive && !page.__disposed && page.data.canTransferManager && !page.data.busy && !page.__managerCommand;
}
function validId(value) { return /^(?:[1-9][0-9]{0,18})$/.test(String(value)) && (typeof value !== 'number' || Number.isSafeInteger(value)); }
const handlers = {
  async openManagerTransfer() {
    if (!canOpen(this) || this.data.managerTransferLoading) return;
    clear(this);
    const current = guard(this), read = {};
    this.__managerRead = read;
    this.setData({ managerTransferOpen: true, managerTransferLoading: true });
    try {
      const rows = await projects.managerCandidates(this.id);
      if (!current() || this.__managerRead !== read) return;
      if (!Array.isArray(rows) || rows.some(r => !validId(r.id) || !r.name)
          || new Set(rows.map(r => String(r.id))).size !== rows.length) throw Error('同事名单未确认，请重新加载');
      this.setData({ managerTransferRows: rows.map(r => ({id: String(r.id), name: String(r.name)})), managerTransferLoading: false,
        managerTransferError: rows.length ? '' : '没有其他可接手的在岗工程同事' });
    } catch (e) {
      if (current() && this.__managerRead === read) this.setData({ managerTransferLoading: false, managerTransferError: text(e) });
    }
  },
  closeManagerTransfer() {
    if (!this.__managerCommand && !this.data.busy) clear(this);
  },
  pickManagerTransfer(e) {
    if (!canOpen(this)) return;
    const next = this.data.managerTransferRows[Number(e.detail.value)];
    if (!next) return;
    this.__managerChoice = next;
    this.setData({managerTransferName: next.name, managerTransferSelected: true});
  },
  confirmManagerTransfer() {
    const choice = this.__managerChoice;
    if (!choice || !canOpen(this)) return;
    const current = guard(this);
    wx.showModal({ title: '确认转交项目', content: '转交给 ' + choice.name + '？转交后将由对方继续处理。', success: result => {
      if (!result.confirm || !current() || this.__inactive || !canOpen(this) || this.__managerChoice !== choice) return;
      this.__managerCommand = { id: this.id, target: choice.id, key: commandKey('eng-manager'), current };
      this.retryManagerTransfer();
    } });
  },
  async retryManagerTransfer() {
    const cmd = this.__managerCommand;
    if (!cmd || this.data.busy || !cmd.current()) return;
    this.setData({ busy: true, managerTransferPending: true, managerTransferError: '' });
    try {
      const result = await projects.changeManager(cmd.id, cmd.target, '', cmd.key);
      if (!cmd.current() || this.__managerCommand !== cmd) return;
      if (String(result?.id || '') !== String(cmd.id)) throw Error('转交结果还没确认，请重试核对');
      clear(this);
      this.setData({ busy: false, canTransferManager: false, actions: [], own: false });
      if (!this.__inactive) {
        wx.showToast({title: '已确认转交', icon: 'none'});
        this.refresh(true);
      }
    } catch (e) {
      if (!cmd.current() || this.__managerCommand !== cmd) return;
      const code = Number(e?.code);
      const uncertain = !Number.isFinite(code) || code <= 0 || code >= 500 && code < 600 || code === 1099000000;
      if (!uncertain) clear(this);
      this.setData({ busy: false, managerTransferPending: uncertain,
        managerTransferError: uncertain ? '转交结果还没确认，请用原请求重试核对' : text(e) });
      if (!uncertain && !this.__inactive) {
        this.setData({managerTransferOpen: true});
        this.refresh(false);
      }
    }
  }
};

module.exports = {clear, handlers};
