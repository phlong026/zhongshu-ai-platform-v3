const MAX_BYTES = 20 * 1024 * 1024;

function selectVoucher({ alive = () => true } = {}) {
  return new Promise((resolve, reject) => {
    const fail = error => {
      if (!alive() || /cancel/i.test(error?.errMsg || '')) return resolve(null);
      reject(new Error(/auth|denied|permission/i.test(error?.errMsg || '')
        ? '无法访问相机或相册，请在微信权限设置中开启后重试'
        : '凭证选择失败，请重试'));
    };
    const selected = (file, media) => {
      if (!alive() || !file) return resolve(null);
      const path = file.tempFilePath || file.path;
      const name = file.name || ('凭证图片.' + (/\.png$/i.test(path || '') ? 'png' : 'jpg'));
      if (!path) return reject(new Error('文件无法读取，请重新选择'));
      if (!media && !/\.(pdf|png|jpe?g)$/i.test(name)) return reject(new Error('请选择 PDF、PNG 或 JPG 凭证'));
      if (Number(file.size) > MAX_BYTES) return reject(new Error('凭证不能超过20MB'));
      resolve({ path, name });
    };
    wx.showActionSheet({
      itemList: ['拍照或从相册选择', '从聊天选择文件'],
      success: result => {
        if (!alive()) return resolve(null);
        if (result.tapIndex === 0) {
          wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
            success: result => selected(result.tempFiles?.[0], true), fail });
        } else if (result.tapIndex === 1) {
          wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'png', 'jpg', 'jpeg'],
            success: result => selected(result.tempFiles?.[0], false), fail });
        } else resolve(null);
      }, fail
    });
  });
}

module.exports = { selectVoucher, MAX_BYTES };
