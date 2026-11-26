const http = require('../../../api/http');
const engineering = require('../../../utils/platform-engineering');

const commands = new Map();
const keyFor = (page, node) => [http.sessionGeneration(), http.currentPostId(), page.id, node].join(':');

function held(page) {
  if (page.data.view !== 'delay') return null;
  return commands.get(keyFor(page, page.nodeId || page.data.node?.id));
}

function prepare(data, handlers) {
  if (data.form.delayOwnerUserId && !handlers.some(h => String(h.id) === data.form.delayOwnerUserId)) {
    data.form.delayOwnerUserId = '';
    data.form.delayOwnerName = '';
  }
}

function apply(page, data, handlers) {
  if (!data.actions.some(a => a.action === 'delay')) return;
  prepare(data, handlers);
  const command = commands.get(keyFor(page, data.node.id));
  if (!command) return;
  Object.assign(data.form, command.form);
  data.busy = command.sending;
  data.error = command.sending ? '' : '上次登记回执未确认，请重试原记录';
}

async function save(page, form, node) {
  if (page.__inactive || page.__disposed || page.data.view !== 'delay' || !node
      || String(node.id) !== page.data.node?.id || (page.nodeId && String(node.id) !== page.nodeId)
      || !page.data.actions.some(a => a.action === 'delay')) return;
  const scope = keyFor(page, node.id);
  for (const [id, command] of commands) if (!command.current()) commands.delete(id);
  let command = commands.get(scope);
  if (command?.sending) return;
  if (!command) {
    if (!form.recoveryDate || !(page.data.handlers || []).some(h => String(h.id) === form.delayOwnerUserId)) {
      page.setData({error:'请选择处理责任人及预计完成日期'}); return;
    }
    command = {
      key:'eng-delay-' + node.id + '-' + Date.now() + '-' + Math.floor(Math.random()*1e8), current:http.sessionGuard(),
      form:{delayOwnerUserId:form.delayOwnerUserId,delayOwnerName:form.delayOwnerName,recoveryDate:form.recoveryDate,
        delayReason:form.delayReason,recoveryAction:form.recoveryAction}
    };
    commands.set(scope, command);
  }
  command.sending = true;
  page.setData({busy:true,error:''});
  const visible = () => {
    const currentPage = getCurrentPages().at(-1);
    return currentPage && currentPage.data.view === 'delay' && !currentPage.__inactive && !currentPage.__disposed
      && command.current() && keyFor(currentPage,currentPage.nodeId || currentPage.data.node?.id) === scope ? currentPage : null;
  };
  try {
    await engineering.perform('delay',String(page.id),command.form,node,command.key);
  } catch (error) {
    const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600;
    command.sending = false;
    if (!uncertain || !command.current()) commands.delete(scope);
    const currentPage = visible();
    if (currentPage) currentPage.setData({busy:false,error:uncertain?'上次登记回执未确认，请重试原记录':engineering.errorText(error)});
    return;
  }
  commands.delete(scope);
  if (!command.current()) return;
  if (keyFor(page,page.nodeId || page.data.node?.id) === scope) page.initialized = false;
  const currentPage = visible();
  if (currentPage) {
    wx.showToast({title:'已在线保存',icon:'success'});
    await currentPage.refreshOnline(true);
  }
}

module.exports = { held, prepare, apply, save };
