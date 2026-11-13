/**
 * 老板 · 月度经营简报（P1 批6）。
 *
 * 口径（后端 PerfOperatingService / PerfOperatingController 实证）：
 * - 当月=实时简报：GET /franchise/performance/operating/analytics?period=YYYY-MM，直读不落快照；
 * - 历史月可归档：POST /franchise/performance/operating/snapshot（服务端仅允许过去月，008）；
 *   同月数据修订后再次归档 → version+1 且 supersedes 指向上一版本；签名未变 → 002（不生成新版本）；
 * - 快照列表：GET /franchise/performance/operating/snapshots?period=（version/supersedes/asOf/note）。
 *
 * 数据整形全在 utils/platform-operating-brief.js（纯函数），本页只负责取数与交互。
 */
const PP = require('../../../utils/platform-performance');
const Brief = require('../../../utils/platform-operating-brief');
const http = require('../../../api/http');

Page({
  data: {
    ready: false,
    error: '',
    period: '',
    periodLabel: '',
    brief: null,
    archiveBusy: false,
    archiveNotice: '',
    archiveError: ''
  },

  onLoad(options = {}) {
    const period = Brief.validPeriod(options.period) ? String(options.period) : PP.currentPeriod();
    this.setData({ period, periodLabel: Brief.periodLabel(period) });
  },

  onShow() {
    // P2-2：无在途归档时统一解除 busy（换岗/会话变化后回到页面不残留 loading）
    if (!this._archivePending && this.data.archiveBusy) this.setData({ archiveBusy: false });
    this.load();
  },

  /** P2-1：异步回调上下文校验（月份 + 岗位 + 会话代次三者同时一致才允许写 UI） */
  stillHere(context) {
    if (!context) return false;
    if (this.data.period !== context.period) return false;
    if (context.session !== http.sessionGeneration()) return false;
    return context.postKey === String((PP.apiPost() || {}).postId || '');
  },

  retry() {
    this.load();
  },

  load() {
    if (!PP.online()) {
      this.setData({ ready: false, error: '请先登录后端账号', brief: null });
      return;
    }
    const period = this.data.period;
    const post = PP.apiPost();
    const postKey = String((post && post.postId) || '');
    const gen = http.requestGuard('owner-brief');
    const alive = () => gen() && postKey === String((PP.apiPost() || {}).postId || '');
    this.setData({ error: '' });
    // 快照失败不拖累 analytics（独立降级，快照卡展示重试提示）
    const snapshots = PP.api.operating.snapshots(period)
      .then(result => ({ status: 'ready', rows: (result && result.snapshots) || [] }))
      .catch(() => ({ status: 'error', rows: [] }));
    const analytics = PP.api.operating.analytics(period).catch(err => {
      if (err && err.code === 401) throw err; // 会话失效交 http 层统一引导
      return null;
    });
    Promise.all([analytics, snapshots])
      .then(([operating, snapshotResult]) => {
        if (!alive()) return; // 换月/换岗/退出：迟到响应丢弃
        this.setData({
          ready: true,
          brief: Brief.briefView({
            period,
            currentPeriod: PP.currentPeriod(),
            operating,
            snapshots: snapshotResult.rows,
            snapshotsStatus: snapshotResult.status
          })
        });
      })
      .catch(err => {
        if (!alive()) return;
        this.setData({
          ready: true,
          error: err && err.code === 401 ? '登录已过期，请重新登录' : '',
          brief: Brief.briefView({
            period,
            currentPeriod: PP.currentPeriod(),
            operating: null,
            snapshots: [],
            snapshotsStatus: 'error'
          })
        });
      });
  },

  month(e) {
    const period = String((e && e.detail && e.detail.value) || '');
    if (!Brief.validPeriod(period)) return;
    this.setData({
      period,
      periodLabel: Brief.periodLabel(period),
      brief: null,
      ready: false,
      // 旧月份的挂起归档不再属于当前页：解除 busy，回调由 stillHere 丢弃
      archiveBusy: false,
      archiveNotice: '',
      archiveError: ''
    });
    this.load();
  },

  /** P2-2：上下文已变化的迟到回调释放 busy（仅当没有更新的归档接管） */
  dropArchiveBusy(seq) {
    if (this._archiveSeq !== seq) return;
    if (this.data.archiveBusy) this.setData({ archiveBusy: false });
  },

  /** 归档快照：二次确认 + 可选备注（后端只允许过去月；当月按钮已禁用） */
  archive() {
    const brief = this.data.brief;
    if (!brief || brief.archive.disabled || this.data.archiveBusy) return;
    if (!PP.online()) return this.setData({ archiveError: '请先登录后端账号' });
    const period = this.data.period;
    wx.showModal({
      title: '归档经营快照',
      content: '按 ' + brief.periodLabel + ' 的实时口径生成快照；已有快照且数据有修订时生成新版本（version+1）。',
      editable: true,
      placeholderText: '归档备注（可选，200 字以内）',
      success: res => {
        if (!res.confirm) return;
        const note = String(res.content || '').trim();
        if (note.length > 200) return this.setData({ archiveError: '归档备注请控制在 200 字以内' });
        this.setData({ archiveBusy: true, archiveError: '', archiveNotice: '' });
        const seq = this._archiveSeq = (this._archiveSeq || 0) + 1;
        this._archivePending = seq;
        // 粘性幂等键：同一次归档重试复用（后端重放返回同结果，签名未变报 002）
        if (!this._snapshotKey || this._snapshotPeriod !== period) {
          this._snapshotKey = 'brief-snap-' + period + '-' + Date.now();
          this._snapshotPeriod = period;
        }
        // P2-1：发出时冻结上下文；回调写 UI 前校验月份/岗位/会话代次，任一变化即静默丢弃
        const context = {
          period,
          postKey: String((PP.apiPost() || {}).postId || ''),
          session: http.sessionGeneration()
        };
        PP.api.operating.snapshot(period, note, this._snapshotKey)
          .then(() => {
            if (this._archivePending === seq) this._archivePending = 0;
            // r3：更新的归档（B）已接管时，A 的迟到响应完全丢弃——不得清 B 的 busy / 写提示
            if (seq !== this._archiveSeq) return;
            if (!this.stillHere(context)) { this.dropArchiveBusy(seq); return; } // 换月/换岗/退出：静默丢弃并释放 busy
            this._snapshotKey = null;
            this._snapshotPeriod = '';
            this.setData({ archiveBusy: false, archiveNotice: '已归档 ' + brief.periodLabel + ' 快照' });
            wx.showToast({ title: '快照已归档', icon: 'none' });
            this.load();
          })
          .catch(err => {
            if (this._archivePending === seq) this._archivePending = 0;
            if (seq !== this._archiveSeq) return; // r3：同上，失败分支同样不得干预新归档
            if (!this.stillHere(context)) { this.dropArchiveBusy(seq); return; }
            this.setData({ archiveBusy: false, archiveError: Brief.archiveErrorText(err) });
          });
      }
    });
  }
});
