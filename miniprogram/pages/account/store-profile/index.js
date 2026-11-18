const http = require('../../../api/http');
const auth = require('../../../api/auth');
const endpoint = '/platform/org/store-profile';

Page({
  data: { profile: null, loading: true, saving: false, error: '', stale: false },
  onShow() {
    this.visible = true;
    if (this.session !== http.sessionGeneration()) return this.load();
  },
  onHide() { this.visible = false; },
  onUnload() { this.generation++; },
  isCurrent(generation) {
    return this.session === http.sessionGeneration() && this.generation === generation;
  },
  async load() {
    const generation = this.generation = (this.generation || 0) + 1;
    this.session = http.sessionGeneration();
    this.setData({ profile: null, loading: true, saving: false, stale: false, error: '' });
    try {
      const profile = await http.get(endpoint);
      if (this.isCurrent(generation)) this.setData({ profile, loading: false });
    } catch (e) {
      if (this.isCurrent(generation)) this.setData({ loading: false, error: e.message || '资料加载失败' });
    }
  },
  edit(e) {
    const field = e.currentTarget.dataset.field;
    if (this.data.profile && !this.data.saving && ['name', 'ownerName', 'address'].includes(field)) {
      this.setData({ ['profile.' + field]: e.detail.value });
    }
  },
  async save() {
    if (this.session !== http.sessionGeneration()) return this.load();
    const profile = this.data.profile;
    if (!profile || this.data.saving || this.data.stale) return;
    const { orgId, version, name, ownerName, address } = profile;
    if (!name.trim() || !ownerName.trim()) { this.setData({ error: '请填写加盟商名称和老板姓名' }); return; }
    const generation = this.generation;
    this.setData({ saving: true, error: '' });
    try {
      const saved = await http.put(endpoint, { orgId, version, name, ownerName, address });
      if (!this.isCurrent(generation)) return;
      auth.savePosts(auth.apiPosts().map(post => String(post.orgId) === String(saved.orgId) ? { ...post, orgName: saved.name } : post));
      this.setData({ profile: saved, saving: false });
      if (this.visible) wx.showToast({ title: '资料已保存' });
    } catch (e) {
      if (this.isCurrent(generation)) this.setData({ saving: false, stale: Number(e.code) === 409, error: e.message || '保存失败，请重试' });
    }
  }
});
