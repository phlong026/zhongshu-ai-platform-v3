/**
 * 生产切换一次性迁移：清理本地账簿时代的 zhongshu-* 模拟数据残留。
 *
 * 背景：小程序早期为本地存储协作模型（wx storage 自产自销 + seed 演示数据），
 * 平台化后业务数据一律走 /admin-api。老设备上残留的 zhongshu-* 键（假客户/假账簿/
 * 假工程/演示消息等）不再有任何消费方，必须清除，防止过期本地视图被旧页面读出。
 *
 * 范围：仅清 `zhongshu-*` 前缀（本地账簿体系键）；保留 `zs-*`（api 层会话/联调覆写键）。
 * 执行一次后写标记键，之后启动零开销。
 */
const MARK_KEY = 'zs-legacy-storage-cleanup-v1';

function run() {
  if (wx.getStorageSync(MARK_KEY)) return;
  const info = wx.getStorageInfoSync();
  const keys = (info && info.keys) || [];
  let removed = 0;
  for (const key of keys) {
    if (key === MARK_KEY) continue;
    if (key.indexOf('zhongshu-') === 0) {
      try { wx.removeStorageSync(key); removed += 1; } catch (_) { /* 单键失败不阻断 */ }
    }
  }
  wx.setStorageSync(MARK_KEY, { at: Date.now(), removed });
}

module.exports = { run };
