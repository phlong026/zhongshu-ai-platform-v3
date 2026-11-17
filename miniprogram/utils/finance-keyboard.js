function stop(page) {
  page.__financeKeyboardActive = false;
  if (page.__financeKeyboardHandler && wx.offKeyboardHeightChange) {
    wx.offKeyboardHeightChange(page.__financeKeyboardHandler);
  }
  page.__financeKeyboardHandler = null;
  if (!page.__disposed && page.data.financeKeyboardOpen) page.setData({ financeKeyboardOpen: false });
}

function start(page) {
  stop(page);
  page.__financeKeyboardActive = true;
  const handler = event => {
    if (!page.__financeKeyboardActive || page.__disposed || page.__financeKeyboardHandler !== handler) return;
    const open = Number(event.height) > 0;
    if (open !== !!page.data.financeKeyboardOpen) page.setData({ financeKeyboardOpen: open });
  };
  page.__financeKeyboardHandler = handler;
  if (wx.onKeyboardHeightChange) wx.onKeyboardHeightChange(handler);
}

function dismiss(page, api = wx) {
  if (api.hideKeyboard) api.hideKeyboard({ fail() {} });
  if (!page.__disposed && page.data.financeKeyboardOpen) page.setData({ financeKeyboardOpen: false });
}

module.exports = { start, stop, dismiss };
