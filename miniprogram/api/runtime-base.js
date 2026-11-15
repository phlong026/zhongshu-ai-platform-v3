/** 仅开发者工具允许通过 storage 覆写接口基址；无法识别运行平台时使用环境默认值。 */
function platform() {
  try {
    if (typeof wx.getDeviceInfo === 'function') {
      const info = wx.getDeviceInfo();
      if (info && info.platform) return String(info.platform).toLowerCase();
    }
  } catch (e) {}

  try {
    if (typeof wx.getSystemInfoSync === 'function') {
      const info = wx.getSystemInfoSync();
      if (info && info.platform) return String(info.platform).toLowerCase();
    }
  } catch (e) {}

  return '';
}

function devtoolsStorage(key) {
  if (platform() !== 'devtools') return '';
  try {
    return wx.getStorageSync(key) || '';
  } catch (e) {
    return '';
  }
}

module.exports = { devtoolsStorage };
