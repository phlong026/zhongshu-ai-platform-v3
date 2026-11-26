const http = require('../../../api/http');
const auth = require('../../../api/auth');
const apiScope = require('../../../utils/api-scope');

function identity() {
  const post = auth.currentApiPost();
  if (!http.token() || !post || !http.currentPostId() || String(post.postId) !== String(http.currentPostId())) return '';
  return JSON.stringify([apiScope(), post.brandId || '']);
}

function discard(filePath) {
  if (!filePath) return;
  try { wx.getFileSystemManager().unlink({ filePath, fail() {} }); } catch (_) { }
}

function create(customerId, quoteId, clear) {
  let active;
  const files = new Set();

  function close(error = '') {
    active = null;
    for (const file of files) discard(file);
    files.clear();
    clear(error);
  }

  function open() {
    close();
    const scope = identity();
    if (!scope) return close('请先选择有效工作身份');
    if (![customerId, quoteId].every(id => /^[1-9][0-9]{0,18}$/.test(id || ''))) return close('客户报价链接无效，请返回客户记录');
    active = { scope };
    return active;
  }

  function current(ticket) {
    if (!ticket || active !== ticket) return false;
    if (ticket.scope === identity()) return true;
    close('工作身份已变更，请重新打开客户报价');
    return false;
  }

  async function read(ticket) {
    if (!current(ticket)) throw Error('请重新打开客户报价');
    try {
      const sheet = await http.get('/franchise/customers/' + customerId + '/workflow/quotes/' + quoteId + '/export-sheet');
      if (!current(ticket)) throw Error('工作身份已变更');
      if (String(sheet?.customerId) !== customerId || String(sheet?.quoteId) !== quoteId) throw Error('当前报价与客户记录不匹配');
      return sheet;
    } catch (error) {
      if (current(ticket)) close(error.message || error.msg || '无法查看这位客户的报价');
      throw error;
    }
  }

  function keep(ticket, file) {
    if (current(ticket)) files.add(file);
    else discard(file);
  }

  return { open, close, current, read, keep, ticket: () => current(active) ? active : null };
}

module.exports = { create };
