const faces = require('./font-sources');
let pending;
module.exports = function loadEngineeringFonts() {
  if (pending) return pending;
  const app = getApp();
  app.globalData.engineeringFonts = {};
  pending = Promise.all(faces.map(face => new Promise(resolve => {
    wx.loadFontFace({
      family: face.family, source: face.source, global: true,
      success: () => { app.globalData.engineeringFonts[face.family] = 'loaded'; resolve(true); },
      fail: error => { app.globalData.engineeringFonts[face.family] = error.errMsg || 'failed'; resolve(false); }
    });
  })));
  return pending;
};
