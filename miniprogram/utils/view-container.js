// 单页多视图容器的公共机制（体检复用项2）。
// 现状：routeValue 等 6 份逐字复制、通用表单处理器 5+ 份同构——本模块提供单源，
// 各容器逐步接入（接入一个删一份本地实现），titles 注册与视图 DSL 的合并随后续批次。
const routeValue = v => { try { return decodeURIComponent(v || ''); } catch (_) { return String(v || ''); } };

// 生成通用表单处理器：input/lineInput/pick/toggle，绑定到 this.setData({['form.'+key]:value,error:''})
function makeFormHandlers(lineKey = 'form') {
  return {
    input(e) {
      const key = e.currentTarget.dataset.key || e.target.dataset.key;
      if (e.type === 'picker' || e.detail.value === undefined) return;
      this.setData({ [lineKey + '.' + key]: e.detail.value, error: '' });
    },
    pick(e) {
      const key = e.currentTarget.dataset.key;
      this.setData({ [lineKey + '.' + key]: e.detail.value, error: '' });
    },
  };
}

// 页面级 fail：错误落 data.error 并 toast（与各容器现有实现一致）
function fail(message) {
  this.setData({ error: message, busy: false });
  wx.showToast({ title: message, icon: 'none' });
}

module.exports = { routeValue, makeFormHandlers, fail };
