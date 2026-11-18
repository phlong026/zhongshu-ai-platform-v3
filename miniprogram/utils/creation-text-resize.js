const keyboard = require('./creation-keyboard');
function point(event) {
  const touch = (event.touches || event.changedTouches || [])[0];
  return touch ? Number(touch.clientY == null ? touch.pageY : touch.clientY) : NaN;
}
function limits(page, field) {
  let height = 667;
  try { height = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).windowHeight || height; } catch (_) {}
  return { min: field === 'editor' ? 48 : 80, max: Math.max(80, Math.min(720, Math.floor(height * .8))) };
}
function setHeight(page, field, height, normal) {
  const bounds = limits(page, field);
  const next = Math.round(Math.max(bounds.min, Math.min(bounds.max, height)));
  if (field === 'editor') keyboard.resize(page, next);
  else page.setData({ ['textHeights.' + field]: next });
  page.setData({ ['textExpanded.' + field]: next > normal + 8 });
}
const handlers = {
  startTextResize(event) {
    const { field, normal = 160 } = event.currentTarget.dataset;
    if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(field || '')) return;
    this.__textResize = { field, normal: Number(normal), y: point(event), moved: false,
      height: field === 'editor' ? this.data.editorInputHeight : (this.data.textHeights || {})[field] || Number(normal) };
  },
  moveTextResize(event) {
    const drag = this.__textResize, y = point(event);
    if (!drag || !Number.isFinite(y) || !Number.isFinite(drag.y)) return;
    if (Math.abs(y - drag.y) > 3) drag.moved = true;
    if (drag.moved) setHeight(this, drag.field, drag.height + y - drag.y, drag.normal);
  },
  endTextResize() { this.__textResizeMoved = !!(this.__textResize && this.__textResize.moved); this.__textResize = null; },
  toggleTextResize(event) {
    if (this.__textResizeMoved) { this.__textResizeMoved = false; return; }
    const { field, normal = 160 } = event.currentTarget.dataset;
    if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(field || '')) return;
    const expanded = (this.data.textExpanded || {})[field];
    setHeight(this, field, expanded ? Number(normal) : Math.max(Number(normal) * 2, 320), Number(normal));
  }
};
module.exports = { handlers, limits };
