const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
"use strict";
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');
const assets = require('../../utils/assets');
const generationOptions = require('../../utils/generation-options');
const flatDefaults = generationOptions.selection('FLAT');
const STATUS_TEXT = { QUEUED: '排队等待中', RUNNING: 'AI 正在绘制方案', VALIDATING: '正在校验生成结果', CANCEL_REQUESTED: '正在取消任务', SUCCEEDED: '生成完成', PARTIALLY_SUCCEEDED: '部分方案已生成', FAILED: '生成失败', CANCELLED: '任务已取消' };
const STEP_ORDER = ['QUEUED', 'RUNNING', 'VALIDATING'];
// 两条线路确认后进入各自正式结果，不跨线引导；
// tab=true 表示目标是 tabBar 页面，需用 switchTab 跳转
const NEXT = {
  plane: { label: '查看平面方案', url: '/design-migration/pages/ai-design/result' },
  elevation: { label: '查看最终方案', url: '/design-migration/pages/ai-design/result' }
};

protectedPage({
  data: { stage: 'plane', count: 2, progress: 0, status: 'QUEUED',
          statusText: '正在读取任务', acceptedCount: 0, canCancel: false, cancelling: false, finished: false, pollError: '', partialNotice: '',
          stepIndex: 0, elapsedText: '00:00',
          candidates: [], pendingSlots: 0, candidatesError: '',
          selectedId: '', selectDone: false, selecting: false, nextLabel: '', regenerating: false, terminal: false, targetInvalid: false, terminalNotice: '',
          resolution: flatDefaults.resolution, orientation: flatDefaults.orientation, outputPixels: flatDefaults.outputPixels },
  onLoad(query) {
    this._token = http.captureSession ? http.captureSession() : http.getToken();
    this._jobId = query.jobId == null ? getDesignApp().globalData.jobId : view.id(query.jobId);
    this._expectedProjectId = query.projectId == null ? null : view.id(query.projectId);
    if (!this._jobId || (query.projectId != null && !this._expectedProjectId)) {
      this.setData({ targetInvalid: true, statusText: '任务编号无效', pollError: '请从消息或方案记录重新进入' }); return;
    }
    this._pageStart = Date.now();
    this._statusAt = {};
    const stage = query.stage === 'elevation' ? 'elevation' : 'plane';
    const count = Number(query.count) || 2;
    // 单方案出图实测 3~4 分钟；多方案按个数累加（候选串行出图）。
    const estimateText = count > 1 ? '预计 ' + (count * 3) + '~' + (count * 4) + ' 分钟' : '预计 3~4 分钟';
    this.setData({ stage: stage, count: count, estimateText: estimateText,
      tipTitle: stage === 'plane' ? '平面方案生成中' : '立面方案生成中',
      tipSubtitle: '共' + count + '个方案' });
    this.startTicker();
    this.poll();
  },
  onUnload() {
    this._stopped = true;
    if (this._timer) clearTimeout(this._timer);
    if (this._uiTimer) clearInterval(this._uiTimer);
  },
  startTicker() {
    const self = this;
    this._uiTimer = setInterval(function () {
      if (self._stopped) return;
      self.setData({ progress: self.computeProgress(), elapsedText: self.elapsedText() });
    }, 500);
  },
  elapsedText() {
    const total = Math.max(0, Math.floor((Date.now() - this._pageStart) / 1000));
    const mm = String(Math.floor(total / 60)).padStart(2, '0'), ss = String(total % 60).padStart(2, '0');
    return mm + ':' + ss;
  },
  // 平滑进度：后端仅在单张图完成时回报一次进度，直接展示会长期停在 0%。
  // 前端按阶段推进平滑进度（渐近上限），后端回报值只用来抬高、不用来回落。
  computeProgress() {
    const self = this;
    const status = this.data.status;
    if (this.data.finished) return 100;
    if (status === 'FAILED' || status === 'CANCELLED') return this.data.progress;
    const now = Date.now();
    const since = function (key) { return (now - (self._statusAt[key] || self._pageStart)) / 1000; };
    let target;
    if (status === 'VALIDATING') target = 90 + Math.min(8, since('VALIDATING') / 2);
    else if (status === 'RUNNING') target = 14 + 76 * (1 - Math.exp(-since('RUNNING') / 45));
    else if (status === 'CANCEL_REQUESTED') target = this.data.progress;
    else target = Math.min(12, since('QUEUED') * 0.8);
    const server = Number(this._serverProgress) || 0;
    return Math.round(Math.max(Math.min(target, 98), Math.min(server, 96), 0));
  },
  poll() {
    if (this._stopped) return;
    const self = this;
    const jobId = this._jobId;
    if (!jobId) { wx.navigateBack(); return; }
    return api.getAiJob(jobId).then(function (job) {
      if (self._stopped || !http.isSameSession(self._token)) return;
      if (!job || String(job.jobId) !== String(jobId)) throw Error('任务信息不一致');
      if (self._expectedProjectId && job.projectId !== self._expectedProjectId) throw Error('任务项目不一致');
      if (!self._statusAt[job.status]) self._statusAt[job.status] = Date.now();
      self._serverProgress = Number(job.progress) || 0;
      self._projectId = job.projectId;
      const jobOptions = generationOptions.selection(job.phase === 'ELEVATION' ? 'ELEVATION' : 'FLAT', job.imageOptions || job);
      const finished = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED'].includes(job.status);
      const stepIndex = finished ? 3 : Math.max(0, STEP_ORDER.indexOf(job.status));
      // 部分失败明示：成功张数/未通过张数/自动退点，避免用户只看到"完成"而不知道有张数折损
      let partialNotice = '';
      if (job.status === 'PARTIALLY_SUCCEEDED') {
        const failed = Math.max(0, (job.requestedCount || 0) - (job.acceptedCount || 0));
        const refund = job.refundedPointCost || 0;
        partialNotice = '本次成功 ' + (job.acceptedCount || 0) + '/' + (job.requestedCount || 0) + ' 张，'
          + failed + ' 张未通过校验' + (refund > 0 ? '，' + refund + ' 点已自动退回' : '');
      }
      self.setData({ status: job.status, terminal: finished || ['FAILED', 'CANCELLED'].includes(job.status),
        stage: job.phase === 'ELEVATION' ? 'elevation' : 'plane', count: job.requestedCount,
        acceptedCount: job.acceptedCount || 0, statusText: STATUS_TEXT[job.status] || '状态待确认',
        canCancel: (job.allowedActions || []).includes('CANCEL'),
        stepIndex: stepIndex,
        finished: finished, pollError: '', resolution: jobOptions.resolution,
        orientation: jobOptions.orientation, outputPixels: jobOptions.outputPixels,
        partialNotice: partialNotice });
      // 候选是拉式晋升的：有新通过校验的方案，或任务刚到终态，都拉一次候选并内联呈现
      if ((job.acceptedCount || 0) > (self._acceptedSeen || 0) || finished) {
        self._acceptedSeen = job.acceptedCount || 0;
        self.refreshCandidates();
      }
      if (finished) {
        // 改版：完成不再跳转选择页，候选已内联呈现，用户在本页点「选它」
        self.setData({ progress: 100 });
      } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
        const refund = job.refundedPointCost;
        self.setData({ pendingSlots: 0, terminalNotice: Number.isSafeInteger(refund) && refund > 0
          ? '本次生成结算已退回 ' + refund + ' 公司积分，可在公司积分流水核对。'
          : job.billingFinalized ? '本次生成已结算，可在公司积分流水核对。' : '正在确认公司积分结算，请稍候。' });
        if (self._uiTimer) clearInterval(self._uiTimer);
      } else {
        self._timer = setTimeout(function () { self.poll(); }, 2000);
      }
      if ((finished || ['FAILED','CANCELLED'].includes(job.status)) && !job.billingFinalized) {
        self._timer=setTimeout(function(){self.poll();},3000);
      }
    }).catch(function (error) {
      if (self._stopped || !http.isSameSession(self._token)) return;
      const invalid = error && ([403, 404].includes(Number(error.code)) || ['任务信息不一致', '任务项目不一致'].includes(error.message));
      self.setData({ targetInvalid: !!invalid, pollError: invalid ? '任务已失效或无权访问，请返回消息或方案记录核对。' : '暂未取得最新进度，正在重试。' });
      if (invalid && self._uiTimer) clearInterval(self._uiTimer);
      if (!invalid) self._timer = setTimeout(function () { self.poll(); }, 3000);
    });
  },
  // 拉取本任务候选（后端先晋升再下发），差量合并进候选区；未完成槽位渲染灰占位
  refreshCandidates() {
    const self = this;
    if (!this._projectId || !this._jobId) return;
    api.getProject(this._projectId, this._jobId).then(function (project) {
      if (self._stopped || !self.current(self._token)) return;
      const selected = generationOptions.selection(self.data.stage === 'plane' ? 'FLAT' : 'ELEVATION', project);
      const list = (project.candidates || [])
        .filter(function (c) { return String(c.jobId) === String(self._jobId); });
      const merged = self.data.candidates.slice();
      let added = false;
      list.forEach(function (c) {
        if (merged.some(function (item) { return String(item.candidateId) === String(c.candidateId); })) return;
        merged.push({ candidateId: String(c.candidateId), assetId: c.assetId,
          slot: c.slotNo || (merged.length + 1), name: '方案 ' + String.fromCharCode(65 + merged.length), url: '', imageError: '', imageLoading: false });
        added = true;
      });
      const pendingSlots = self.data.finished ? 0 : Math.max(0, (self.data.count || 0) - merged.length);
      self.setData({ candidates: merged, pendingSlots: pendingSlots, candidatesError: '',
        resolution: selected.resolution, orientation: selected.orientation, outputPixels: selected.outputPixels });

      merged.forEach(function (item) { if (!item.url && !item.imageLoading) self.loadCandidateImage(item.candidateId); });
    }).catch(function () {
      // 候选拉取失败不打断进度轮询，下轮重试；但若已到终态，必须给用户显式重试出口而不是空页面
      if (self._stopped || !self.current(self._token)) return;
      if (self.data.finished && !self.data.candidates.length) self.setData({ candidatesError: '候选读取失败，可重试加载' });
    });
  },
  loadCandidateImage(candidateId) {
    const index = this.data.candidates.findIndex(item => String(item.candidateId) === String(candidateId));
    if (index < 0) return;
    const item = this.data.candidates[index], jobId = this._jobId;
    if (!item.assetId || item.imageLoading) return;
    const apply = patch => {
      if (this._stopped || !this.current(this._token) || jobId !== this._jobId) return;
      const target = this.data.candidates[index];
      if (!target || target.candidateId !== item.candidateId || target.assetId !== item.assetId) return;
      this.setData({ candidates: this.data.candidates.map((candidate, position) => position === index ? Object.assign({}, candidate, patch) : candidate) });
    };
    apply({ imageLoading: true, imageError: '' });
    return assets.fetchAssetDataUrl(item.assetId).then(url => apply({ url, imageLoading: false, imageError: '' }))
      .catch(() => apply({ url: '', imageLoading: false, imageError: '图片加载失败，点击重试' }));
  },
  retryCandidateImage(event) { return this.loadCandidateImage(event.currentTarget.dataset.id); },
  // 候选缩略图 aspectFit 展示全图，点击全屏查看细节；加载失败占位仍走重试，不触发预览
  previewCandidateImage(event) {
    const id = event.currentTarget.dataset.id;
    const cand = this.data.candidates.find(item => String(item.candidateId) === String(id));
    if (cand && cand.url) assets.previewImages([cand.url], cand.url);
  },
  candidateImageError(event) {
    const index = this.data.candidates.findIndex(item => String(item.candidateId) === String(event.currentTarget.dataset.id));
    if (index >= 0) this.setData({ candidates: this.data.candidates.map((candidate, position) => position === index ? Object.assign({}, candidate,
      { url: '', imageLoading: false, imageError: '图片已失效，点击重新加载' }) : candidate) });
  },
  current(token) { return http.isSameSession ? http.isSameSession(token) : token === http.getToken(); },
  // 页内选择：与主/恢复两路径共用（选择页已收敛到本页内联候选）
  selectCandidate(event) {
    if (!this.data.finished || this.data.selectDone || this.data.selecting) return;
    const candidateId = event.currentTarget.dataset.id;
    const cand = this.data.candidates.find(function (item) { return String(item.candidateId) === String(candidateId); });
    if (!cand) return;
    const self = this;
    this.setData({ selecting: true });
    const global = getDesignApp().globalData;
    const request = this.data.stage === 'plane'
      ? api.selectFlat(this._projectId, this._jobId, cand.candidateId).then(function (vo) {
          if (self._stopped || !self.current(self._token)) return;
          const legacySelection = vo && vo.selectionSaved === true && vo.flatDeliveryCreated === false
            && vo.stage === 'ELEVATION' && String(vo.projectId) === String(self._projectId);
          if (legacySelection) {
            global.resultVersionId = null;
            global.projectId = self._projectId;
            self._flatSelectionFollowUp = 'elevation';
            self._legacyElevationJobId = vo.resumeAction === 'POLL_JOB' ? view.id(vo.jobId) : null;
            if (vo.resumeAction === 'POLL_JOB' && !self._legacyElevationJobId) throw { msg: '立面任务编号无效，请重试' };
          } else if (vo && vo.stage !== 'ELEVATION' && vo.resultVersionId
            && (vo.selectionSaved == null || vo.flatDeliveryCreated === true)) {
            global.resultVersionId = vo.resultVersionId;
          } else {
            throw { msg: '正式方案尚未保存，请重试确认' };
          }
          global.selectedCandidateId = cand.candidateId;
          global.selectedFlatAssetId = cand.assetId;
          global.flatLabel = cand.name;
          return null;
        })
      : api.selectElevation(this._projectId, this._jobId, cand.candidateId).then(function (vo) {
          global.selectedElevationId = cand.candidateId;
          global.selectedElevationAssetId = cand.assetId;
          global.elevationLabel = cand.name;
          global.resultVersionId = (vo && vo.resultVersionId) ? vo.resultVersionId : null;
          return null;
        });
    return request.then(function () {
      if (self._stopped || !self.current(self._token)) return;
      const next = self._flatSelectionFollowUp === 'elevation'
        ? { label: self._legacyElevationJobId ? '查看立面进度' : '继续配置立面', url: '/design-migration/pages/ai-design/index', tab: true }
        : NEXT[self.data.stage];
      self.setData({ selectedId: cand.candidateId, selectDone: true, selecting: false, nextLabel: next.label });
      wx.showToast({ title: '已选定 ' + cand.name, icon: 'success' });
    }).catch(function (err) {
      if (self._stopped || !self.current(self._token)) return;
      self.setData({ selecting: false });
      wx.showToast({ title: (err && err.msg) || '选定失败，请重试', icon: 'none' });
    });
  },
  nextStep() {
    if (!this.data.selectDone || !this.current(this._token)) return;
    // 带 projectId/resultVersionId 直达，不依赖 globalData（场景恢复后内存态可能为空）
    if (this._legacyElevationJobId) {
      wx.redirectTo({ url: '/design-migration/pages/ai-design/generating?stage=elevation&projectId=' + this._projectId + '&jobId=' + this._legacyElevationJobId,
        fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
      return;
    }
    const followUp = this._flatSelectionFollowUp === 'elevation';
    const next = followUp ? { label: '继续立面设计', url: '/design-migration/pages/ai-design/index', tab: true } : NEXT[this.data.stage];
    if (next.tab) {
      // 历史立面线路选完平面：回入口页并直接打开立面 Tab（入口页 onShow 按 aiLine 归位）
      if (followUp) {
        getDesignApp().globalData.aiLine = 'ELEVATION';
        getDesignApp().globalData.resumeElevationProjectId = this._projectId;
      }
      navigation.switchTab({ url: next.url, fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
      return;
    }
    let url = next.url;
    if (this._projectId) {
      const versionId = getDesignApp().globalData.resultVersionId;
      url += '?projectId=' + this._projectId + (versionId ? '&resultVersionId=' + versionId : '');
    }
    wx.redirectTo({ url: url, fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  },
  retryCandidates() {
    this.setData({ candidatesError: '' });
    this.refreshCandidates();
  },
  leavePage() {
    wx.navigateBack({ fail: () => navigation.switchTab({ url: '/design-migration/pages/ai-design/index' }) });
  },
  // 选择页收敛后重生成入口移到这里：不满意候选可直接再生成（扣点确认在前），逻辑与原两页一致
  regenerate() {
    wx.showToast({ title: '请在客户跟进中上传外部设计成果', icon: 'none' });
  },
  cancel() {
    if (this._cancelling) return;
    const jobId = this._jobId;
    if (!jobId) return;
    this._cancelling = true;
    this.setData({ cancelling: true });
    const self = this;
    return api.cancelJob(jobId).then(function () {
      if (self._stopped || !self.current(self._token)) return;
      wx.showToast({ title: '正在取消，请稍候', icon: 'none' });
    }).catch(function (err) {
      self._cancelling = false;
      if (self._stopped || !self.current(self._token)) return;
      self.setData({ cancelling: false });
      wx.showToast({ title: (err && err.msg) || '取消失败', icon: 'none' });
    }).then(function () { /* 保留 _cancelling：取消请求已受理，无需重复提交 */ });
  },
  continueLater() {
    wx.showToast({ title: '任务将在后台继续生成', icon: 'none' });
    setTimeout(function () { navigation.switchTab({ url: '/pages/home/index' }); }, 600);
  }
});
