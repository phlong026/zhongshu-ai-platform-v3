const http = require('../../../api/http');
const auth = require('../../../api/auth');
const endpoint = '/platform/org/store-profile';

Page({
  data: { profile: null, loading: true, saving: false, error: '', stale: false },
  onShow() { this.load(); },
  onHide() { this.generation++; },
  onUnload() { this.generation++; },
  async load() {
    const generation = this.generation = (this.generation || 0) + 1;
    const alive = http.sessionGuard();
    this.setData({ profile: null, loading: true, saving: false, stale: false, error: '' });
    try {
      const profile = await http.get(endpoint);
      if (alive() && this.generation === generation) this.setData({ profile, loading: false });
    } catch (e) {
      if (alive() && this.generation === generation) this.setData({ loading: false, error: e.message || '资料加载失败' });
    }
  },
  edit(e) {
    const field = e.currentTarget.dataset.field;
    if (this.data.profile && !this.data.saving && ['name', 'ownerName', 'address'].includes(field)) {
      this.setData({ ['profile.' + field]: e.detail.value });
    }
  },
  async save() {
    const profile = this.data.profile;
    if (!profile || this.data.saving || this.data.stale) return;
    const { orgId, version, name, ownerName, address } = profile;
    if (!name.trim() || !ownerName.trim()) { this.setData({ error: '请填写加盟商名称和老板姓名' }); return; }
    const generation = this.generation, alive = http.sessionGuard();
    this.setData({ saving: true, error: '' });
    try {
      const saved = await http.put(endpoint, { orgId, version, name, ownerName, address });
      if (!alive() || generation !== this.generation) return;
      auth.savePosts(auth.apiPosts().map(post => String(post.orgId) === String(saved.orgId) ? { ...post, orgName: saved.name } : post));
      this.setData({ profile: saved, saving: false });
      wx.showToast({ title: '资料已保存' });
    } catch (e) {
      if (alive() && generation === this.generation) this.setData({ saving: false, stale: Number(e.code) === 409, error: e.message || '保存失败，请重试' });
    }
  }
});
