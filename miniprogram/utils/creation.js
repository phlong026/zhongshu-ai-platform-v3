const assets = [
{ id: 'house', name: '二层乡墅', type: 'image', path: '/assets/creation-house.jpg' },
{ id: 'courtyard', name: '庭院生活', type: 'image', path: '/assets/creation-courtyard.jpg' },
{ id: 'villa', name: '山间别墅', type: 'image', path: '/assets/creation-ai-cover.jpg' }
];
function unavailable(title) {
wx.showModal({ title, content: '服务暂未接入，当前可编辑内容、选择素材并保存本地草稿。', showCancel: false, confirmColor: '#824019' });
}
function back() {
if (getCurrentPages().length > 1) wx.navigateBack();
else wx.switchTab({ url: '/pages/business/index' });
}
function persistMedia(path) {
if (!path) return Promise.reject(new Error('未能读取素材，请重新选择'));
if (path.indexOf('/assets/') === 0) return Promise.resolve(path);
return new Promise((resolve, reject) => {
wx.getFileSystemManager().saveFile({ tempFilePath: path, success: result => resolve(result.savedFilePath), fail: () => reject(new Error('素材保存失败，请检查本机空间后重新选择')) });
});
}
module.exports = { assets, unavailable, back, persistMedia };
