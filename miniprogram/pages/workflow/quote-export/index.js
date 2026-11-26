const S = require('./sheet');
const R = require('./render');
const PDF = require('./pdf');
const call = (fn, options) => new Promise((resolve, reject) => fn({ ...options, success: resolve, fail: reject }));

Page({
  data: { title: '报价单导出', form: {}, image: '', busy: false, error: '', allowed: false, total: '', label: '' },
  onLoad(o) {
    this.quoteId=o['quote-id'];
    this.workflowQuote = o.source === 'customer-flow';
    if(!this.quoteId) { this.setData({error: '请从报价记录进入导出（报价单由平台快照生成）', allowed: false}); return; }
    if (this.workflowQuote) {
      this.guard = require('./workflow-access').create(o.customerId, this.quoteId, error => this.clearProtected(error));
    } else {
      return this.loadPlatform(true);
    }
  },
  onShow() {
    if (!this.guard) return;
    const ticket = this.guard.open();
    if (ticket) return this.loadPlatform(ticket);
  },
  onReady() {
    this.ready = true;
    if (this.doc) return this.renderSheet(this.ticket());
  },
  onHide() { this.guard?.close(); },
  onUnload() { this.guard?.close(); },
  clearProtected(error) {
    this.doc = null; this.jpeg = ''; this.renderJob = null;
    if (this.restrictedCanvas) {
      this.restrictedCanvas.width = this.restrictedCanvas.width;
      this.restrictedCanvas = null;
    }
    this.setData({ form: {}, image: '', imageFile: '', pdfFile: '', total: '', label: '', allowed: false,
      albumDenied: false, busy: false, error });
  },
  ticket() { return this.guard ? this.guard.ticket() : true; },
  active(ticket) { return this.guard ? this.guard.current(ticket) : ticket === true; },
  async loadPlatform(ticket) {
    try {
      const data = this.guard ? await this.guard.read(ticket)
        : await require('../../../api/http').get('/franchise/sales/quotes/' + this.quoteId + '/export-sheet');
      if (!this.active(ticket)) return;
      const lines = rows => (rows || []).map(row => ({
        label: row.label || '', name: row.label || '',
        quantity: String(row.qtyText ?? row.quantity ?? '1'),
        unitPrice: row.unitText != null ? String(row.unitText) : row.unitPriceCents != null ? S.money(row.unitPriceCents) : '',
        cents: row.valueCents ?? null, note: ''
      }));
      const main = lines(data.main), decor = lines(data.decor);
      const sum = rows => rows.reduce((value, row) => value + (row.cents || 0), 0);
      const mainTotal = sum(main), decorTotal = sum(decor), insurance = data.insurance || null, management = data.management || null;
      if (mainTotal !== data.mainTotal || decorTotal !== data.decorTotal) throw Error('报价分项与小计不一致，请核对后再导出');
      if (mainTotal + decorTotal + (insurance || 0) + (management || 0) !== data.total) throw Error('报价分项与总价不一致，请核对后再导出');
      if (main.length + decor.length > 14) throw Error('分项超出打印模板容量，请整理报价分项');
      const doc = {
        kind: 'budget', version: data.version, date: data.date || '', name: data.name || '', phone: data.phone || '',
        projectId: this.quoteId, address: data.address || '', form: S.clean(data.form || {}),
        width: String(data.width ?? ''), depth: String(data.depth ?? ''), area: String(data.area ?? ''), floors: String(data.floors ?? ''),
        main, decor, mainTotal, decorTotal, insurance, management, total: data.total, title: '建造参考预算',
        basis: data.basis || '前期预算，非施工结算依据', demo: false, priceVersion: data.priceVersion || ''
      };
      if (this.workflowQuote) {
        doc.title = '正式建造报价';
        doc.versionLabel = data.quoteVersionLabel || '报价 #' + this.quoteId;
        doc.basis = '本次报价用于客户沟通，施工结算以合同约定为准';
      }
      this.doc = doc;
      this.setData({form: doc.form, label: doc.title + ' ' + S.versionLabel(doc), total: S.money(doc.total), allowed: true, error: ''});
      if (this.ready) await this.renderSheet(ticket);
    } catch (error) {
      if (!this.active(ticket)) return;
      if (this.guard) this.guard.close(error.message || '报价数据加载失败，请重试');
      else this.setData({error: error.message || '导出数据加载失败，请重试', allowed: false});
    }
  },
  async verify(ticket) {
    if (!this.active(ticket)) return false;
    if (this.guard) await this.guard.read(ticket);
    return this.active(ticket);
  },
  async generate() {
    const ticket = this.ticket();
    try { if (await this.verify(ticket)) await this.renderSheet(ticket); }
    catch (error) { this.showError(ticket, error); }
  },
  async renderSheet(ticket) {
    if (!this.active(ticket) || this.data.busy || !this.ready || !this.doc) return;
    const job = this.renderJob = {};
    this.setData({busy: true, pdfFile: '', imageFile: ''});
    try {
      const canvas = await new Promise((resolve, reject) => wx.createSelectorQuery().in(this).select('#quote-canvas')
        .fields({node: true, size: true}).exec(rows => rows[0]?.node ? resolve(rows[0].node) : reject(Error('报价单画布未就绪，请重试'))));
      if (!this.active(ticket)) return;
      if (this.guard) this.restrictedCanvas = canvas;
      canvas.width = R.WIDTH * R.SCALE; canvas.height = R.HEIGHT * R.SCALE;
      const ctx = canvas.getContext('2d'); ctx.scale(R.SCALE, R.SCALE); R.render(ctx, this.doc);
      const options = { canvas, x: 0, y: 0, width: canvas.width, height: canvas.height, destWidth: canvas.width, destHeight: canvas.height };
      const image = await call(wx.canvasToTempFilePath, { ...options, fileType: 'png' });
      this.guard?.keep(ticket, image.tempFilePath);
      if (!this.active(ticket)) return;
      const jpeg = await call(wx.canvasToTempFilePath, { ...options, fileType: 'jpg', quality: 0.98 });
      this.guard?.keep(ticket, jpeg.tempFilePath);
      if (!this.active(ticket)) return;
      this.jpeg = jpeg.tempFilePath;
      this.setData({image: image.tempFilePath, allowed: true, error: ''});
    } catch (error) {
      if (this.active(ticket)) {
        this.doc = null; this.jpeg = '';
        this.setData({image: '', allowed: false, error: error.message || error.errMsg || '生成失败，请重试'});
      }
    } finally {
      if (this.active(ticket) && this.renderJob === job) this.setData({busy: false});
    }
  },
  async ensure(ticket) {
    if (!await this.verify(ticket)) return false;
    if (!this.data.image) await this.renderSheet(ticket);
    if (!this.active(ticket)) return false;
    if (!this.doc || !this.data.image) throw Error(this.data.error || '请先生成报价单');
    return true;
  },
  showError(ticket, error) {
    if (this.active(ticket)) this.setData({error: error.message || error.errMsg || '操作未完成，请重试'});
  },
  exportPath(extension) {
    const prefix = this.guard ? 'crm-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9) + '-' : '';
    return wx.env.USER_DATA_PATH + '/' + prefix + S.filename(this.doc, extension);
  },
  async preview() {
    const ticket = this.ticket();
    try { if (await this.ensure(ticket)) wx.previewImage({current: this.data.image, urls: [this.data.image]}); }
    catch (error) { this.showError(ticket, error); }
  },
  async exportImage() {
    const ticket = this.ticket();
    if (!ticket || this.data.busy) return;
    try {
      if (!await this.ensure(ticket)) return;
      const fs = wx.getFileSystemManager(), filePath = this.exportPath('png');
      await call(fs.copyFile.bind(fs), {srcPath: this.data.image, destPath: filePath});
      this.guard?.keep(ticket, filePath);
      if (!this.active(ticket)) return;
      this.setData({imageFile: filePath, error: ''});
      wx.showActionSheet({itemList: ['保存图片到相册', '发送图片文件'], success: async result => {
        try {
          if (!await this.verify(ticket)) return;
          if (result.tapIndex === 0) {
            await call(wx.saveImageToPhotosAlbum, {filePath});
            if (this.active(ticket)) wx.showToast({title: '已保存到相册', icon: 'success'});
          } else if (wx.shareFileMessage) {
            await call(wx.shareFileMessage, {filePath, fileName: S.filename(this.doc, 'png')});
          } else { wx.previewImage({current: filePath, urls: [filePath]}); }
        } catch (error) {
          if (!this.active(ticket)) return;
          const denied = /auth deny|auth denied|authorize|permission/.test(error.errMsg || '');
          this.setData({error: denied ? '保存相册需要授权，可在小程序设置中开启相册权限' : error.errMsg || '保存失败，请重试', albumDenied: denied});
        }
      }});
    } catch (error) { this.showError(ticket, error); }
  },
  async exportPdf() {
    const ticket = this.ticket();
    if (!ticket || this.data.busy) return;
    try {
      if (!await this.ensure(ticket)) return;
      this.setData({busy: true});
      const fs = wx.getFileSystemManager();
      const jpeg = await call(fs.readFile.bind(fs), {filePath: this.jpeg});
      if (!this.active(ticket)) return;
      const filePath = this.exportPath('pdf');
      await call(fs.writeFile.bind(fs), {filePath, data: PDF.pdf(jpeg.data, R.WIDTH * R.SCALE, R.HEIGHT * R.SCALE)});
      this.guard?.keep(ticket, filePath);
      if (!await this.verify(ticket)) return;
      this.setData({pdfFile: filePath, error: ''});
      await call(wx.openDocument, {filePath, fileType: 'pdf', showMenu: true});
    } catch (error) { this.showError(ticket, error); }
    finally { if (this.active(ticket)) this.setData({busy: false}); }
  },
  async sharePdf() {
    const ticket = this.ticket();
    try {
      if (!await this.ensure(ticket)) return;
      if (!this.data.pdfFile) return this.exportPdf();
      if (!wx.shareFileMessage) throw Error('请打开PDF后通过右上角菜单发送或保存');
      await call(wx.shareFileMessage, {filePath: this.data.pdfFile, fileName: S.filename(this.doc, 'pdf')});
    } catch (error) { this.showError(ticket, error); }
  },
  openSettings() { wx.openSetting({}); },
  back() { return require('../../../utils/page-navigation').back(); }
});
