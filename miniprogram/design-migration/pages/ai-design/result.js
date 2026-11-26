const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const view = require('../../utils/record-view');

protectedPage({
  data: { projectName: '', planFacts: [], versions: [], latest: null, versionLabel: '', imageLabel: '', imageUrl: '', loading: false, error: '', imageError: '', readOnly: false, canRegenerate: false,
    resolution: '', orientation: '', outputPixels: '', specLabel: '历史规格未记录', resultImages: [], activeImageKey: '', optionsReady: false, optionsError: false },
  onLoad(options) {
    this._projectId = view.id(options.projectId || getDesignApp().globalData.projectId);
    this._versionId = options.resultVersionId == null ? null : view.id(options.resultVersionId);
    this._invalidVersion = options.resultVersionId != null && !this._versionId;
    this.loadVersions();
  },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  onShow() { if (!(http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) this.loadVersions(); },
  // 下拉刷新（UX 2026-10）
  onPullDownRefresh() {
    Promise.resolve(this.loadVersions()).catch(() => {}).then(() => wx.stopPullDownRefresh());
  },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this.setData({ latest: null, versions: [], imageUrl: '', resultImages: [], activeImageKey: '', loading: false, error: '登录状态已更新，请重新进入' });
    return false;
  },
  loadVersions() {
    const seq = this._seq = (this._seq || 0) + 1; this._token = http.captureSession ? http.captureSession() : http.getToken();
    this.setData({ projectName: '', planFacts: [], loading: false, error: '', latest: null, versions: [], imageUrl: '', imageError: '',
      resolution: '', orientation: '', outputPixels: '', specLabel: '历史规格未记录', resultImages: [], activeImageKey: '', optionsReady: false, optionsError: false });
    if (!this._projectId || this._invalidVersion || !this._token) { this.setData({ error: '方案信息无效，请从「我的方案」重新进入' }); return; }
    this.setData({ loading: true });
    return api.getResultVersions(this._projectId).then(page => {
      if (!this.current(seq)) return;
      if (!page || !Array.isArray(page.list)) throw Error('方案版本读取失败');
      const list = page.list;
      const selected = this._versionId ? list.find(item => item.versionId === this._versionId)
        : list.find(item => item.superseded === false) || list[0];
      if (!selected || !view.id(selected.versionId)) throw Error('该方案版本不可用或尚未完成');
      this.setData({ versions: list, latest: selected, loading: false, versionLabel: '版本 v' + selected.version,
        readOnly: selected.superseded === true });
      this.loadPlanInfo(seq, selected.versionId);
      return this.loadImages();
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(error) }); });
  },
  loadPlanInfo(seq, versionId) {
    api.getProject(this._projectId).then(project => {
      if (this.current(seq) && project && project.projectId === this._projectId) {
        this.setData({ projectName: project.name || '' });
      }
    }).catch(() => {});
    api.getBudgetInputs(this._projectId, versionId).then(snapshot => {
      if (!this.current(seq) || !snapshot || snapshot.projectId !== this._projectId || snapshot.resultVersionId !== versionId) return;
      const input = snapshot.importedValues || {};
      const fields = [['faceWidth', '面宽', ' m'], ['depth', '进深', ' m'], ['floorCount', '建筑层数', ' 层'],
        ['footprintArea', '占地面积', ' ㎡'], ['buildingArea', '建筑面积', ' ㎡']];
      const planFacts = fields.filter(([key]) => Number(input[key]) > 0)
        .slice(0, 3).map(([key, label, unit]) => ({ label, value: input[key] + unit }));
      this.setData({ planFacts });
    }).catch(() => {});
  },
  imageFailed(event) {
    if (this.current(this._seq) && event.currentTarget.dataset.url === this.data.imageUrl) {
      this.setData({ imageUrl: '', imageError: '图纸暂时无法显示' });
    }
  },
  moreActions() {
    if (!this.current(this._seq) || !this.data.latest) return;
    const seq = this._seq;
    const actions = [{ label: '公司方案', run: () => this.save() }];
    if (!this.data.readOnly) actions.push({ label: '保存至客户', run: () => this.publish() });
    wx.showActionSheet({ itemList: actions.map(item => item.label), success: result => {
      if (this.current(seq) && actions[result.tapIndex]) actions[result.tapIndex].run();
    } });
  },
  loadGenerationOptions() {
    if (!this.current(this._seq) || !this.data.latest) return;
    const key = this.data.activeImageKey;
    const info = this.data.latest[key === 'FLAT' ? 'flatImageInfo' : 'elevationImageInfo'] || {};
    const resolution = ['2K', '4K'].includes(info.resolution) ? info.resolution : '';
    const orientation = ['LANDSCAPE', 'PORTRAIT'].includes(info.orientation) ? info.orientation : '';
    const pixels = Number.isInteger(info.width) && info.width > 0 && Number.isInteger(info.height) && info.height > 0
      ? info.width + ' × ' + info.height : '';
    const known = !!resolution && !!orientation;
    this.setData({ resolution, orientation, outputPixels: pixels, optionsReady: known, optionsError: false,
      specLabel: (known ? resolution + ' ' + (orientation === 'PORTRAIT' ? '竖屏' : '横屏') : '历史规格未记录')
        + (pixels ? ' · 实际像素 ' + pixels : ' · 实际像素未记录') });
  },
  loadImages() {
    if (!this.current(this._seq) || !this.data.latest) return;
    const selected = this.data.latest;
    const images = [
      { key: 'ELEVATION', label: '立面方案', assetId: view.id(selected.selectedElevationAssetId) },
      { key: 'FLAT', label: '平面方案', assetId: view.id(selected.selectedFlatAssetId) }
    ].filter(item => item.assetId);
    const active = images.find(item => item.key === this.data.activeImageKey) || images[0];
    this.setData({ resultImages: images, imageUrl: '', imageError: '' });
    if (!active) { this.setData({ imageError: '本版本图片暂不可用', canRegenerate: false }); return; }
    return this.loadVersionImage(active);
  },
  selectVersionImage(event) {
    const image = this.data.resultImages.find(item => item.key === event.currentTarget.dataset.key);
    if (image && this.current(this._seq)) return this.loadVersionImage(image);
  },
  loadVersionImage(image) {
    const seq = this._seq, request = this._imageRequest = (this._imageRequest || 0) + 1;
    const versionId = this.data.latest.versionId;
    this.setData({ activeImageKey: image.key, imageLabel: image.label, imageUrl: '', imageError: '',
      canRegenerate: false });
    this.loadGenerationOptions();
    const current = () => this.current(seq) && request === this._imageRequest
      && this.data.latest && this.data.latest.versionId === versionId && this.data.activeImageKey === image.key;
    return assets.fetchProfileAvatar(image.assetId).then(url => { if (current()) this.setData({ imageUrl: url }); })
      .catch(() => { if (current()) this.setData({ imageError: '图片加载失败，可重试' }); });
  },
  // 点击方案图放大预览
  previewCurrentImage(event) {
    const url = event.currentTarget.dataset.url || this.data.imageUrl;
    if (url) assets.previewImages([url], url);
  },
  // 保存方案：把当前展示的方案图存入手机相册（data URL 先落临时文件，签名 https 走 downloadFile）。
  saveToAlbum() {
    if (!this.current(this._seq)) return;
    const url = this.data.imageUrl;
    if (!url) { wx.showToast({ title: '方案图还没加载好', icon: 'none' }); return; }
    const filePromise = /^(wxfile:|https?:\/\/tmp\/)/.test(url) ? Promise.resolve(url)
      : /^data:/.test(url) ? assets.toPreviewable(url)
      : new Promise((resolve, reject) => wx.downloadFile({ url, success: r => r.statusCode === 200 ? resolve(r.tempFilePath) : reject(new Error('图片下载失败')), fail: () => reject(new Error('图片下载失败')) }));
    filePromise.then(path => new Promise((resolve, reject) => wx.saveImageToPhotosAlbum({ filePath: path, success: resolve, fail: reject })))
      .then(() => wx.showToast({ title: '已保存到相册', icon: 'success' }))
      .catch(err => {
        const msg = String((err && err.errMsg) || err || '');
        if (/auth deni|authorize|permission/.test(msg)) {
          wx.showModal({ title: '需要相册权限', content: '请在设置中允许保存图片到相册', confirmText: '去设置',
            success: r => { if (r.confirm) wx.openSetting(); } });
        } else wx.showToast({ title: '保存失败，请重试', icon: 'none' });
      });
  },
  budgetHistory() {
    if (this.current(this._seq) && this.data.latest) wx.navigateTo({url:'/design-migration/pages/budget/history?projectId='+this._projectId});
  },
  publish() {
    if (!this.current(this._seq) || !this.data.latest || this.data.readOnly) return;
    wx.navigateTo({url:'/design-migration/pages/customer/archive?projectId='+this._projectId+'&resultVersionId='+this.data.latest.versionId+'&kind='+this.data.activeImageKey});
  },
  save() { wx.navigateTo({ url: '/design-migration/pages/profile/records?type=projects' }); }
});
