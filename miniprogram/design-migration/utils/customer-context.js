const drafts = require('./budget-draft');
const key = (kind, id) => 'zs_design_customer:' + drafts.sessionScope() + ':' + kind + ':' + id;
function read(kind, id) {
 if (!drafts.sessionScope() || !drafts.id(String(id || ''))) return '';
 try { return drafts.id(String(wx.getStorageSync(key(kind,id)) || '')) || ''; } catch(e) { return ''; }
}
function bind(kind, id, customerId) {
 if (!drafts.sessionScope() || !drafts.id(String(id || '')) || !drafts.id(String(customerId || ''))) return;
 try { wx.setStorageSync(key(kind,id), String(customerId)); } catch(e) { /* Explicit route still carries this context. */ }
}
function suffix(customerId) { return drafts.id(String(customerId || '')) ? '&customerId=' + encodeURIComponent(customerId) : ''; }
module.exports = {read,bind,suffix};
