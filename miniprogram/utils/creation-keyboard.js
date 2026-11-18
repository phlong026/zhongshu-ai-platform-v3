// 固定底部文案编辑器主动避让键盘；系统缩小视口时只补偿尚未避让的高度。
function windowHeight() {
  try { const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); return Number(info.windowHeight) || 667; }
  catch (_) { return 667; }
}
function layout(base, current, keyboard, desired) {
  const height = Math.max(0, Number(keyboard) || 0);
  const available = Math.max(0, Math.min(current, base - height) - 20);
  const chrome = height > 0 ? 176 : 212;
  const input = Math.max(48, Math.min(desired || 220, available - chrome));
  return { editorKeyboardOpen: height > 0, editorKeyboardBottom: Math.max(0, height - Math.max(0, base - current)),
    editorSheetHeight: Math.min(available, input + chrome), editorInputHeight: input };
}
function update(page, event) {
  if (page.data.sheet !== 'editor' || page.__closed) return;
  page.__editorKeyboardHeight = Number(event.detail ? event.detail.height : event.height) || 0;
  page.setData(layout(page.__editorWindowHeight || windowHeight(), windowHeight(), page.__editorKeyboardHeight, page.__editorDesiredHeight));
}
function open(page) {
  page.__editorWindowHeight = windowHeight();
  page.__editorKeyboardHeight = 0;
  page.setData(layout(page.__editorWindowHeight, page.__editorWindowHeight, 0, page.__editorDesiredHeight));
  stop(page);
  page.__editorKeyboardHandler = event => update(page, event);
  if (wx.onKeyboardHeightChange) wx.onKeyboardHeightChange(page.__editorKeyboardHandler);
}
function stop(page) {
  if (page.__editorKeyboardHandler && wx.offKeyboardHeightChange) wx.offKeyboardHeightChange(page.__editorKeyboardHandler);
  page.__editorKeyboardHandler = null;
}
function close(page) {
  stop(page);
  if (wx.hideKeyboard) wx.hideKeyboard({ fail() {} });
  page.setData({ editorKeyboardOpen: false, editorKeyboardBottom: 0 });
}
function resize(page, height) {
  page.__editorDesiredHeight = height;
  page.setData(layout(page.__editorWindowHeight || windowHeight(), windowHeight(), page.__editorKeyboardHeight, height));
}
module.exports = { layout, open, update, close, stop, resize };
