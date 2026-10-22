const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

function page() {
  let definition, listener, navigated = '', pending = false, stops = 0;
  const client = {
    baseUrl: () => '', pending: () => pending, start: callback => { listener = callback; }, stop: () => { stops += 1; },
    connected: () => true, snapshot: () => snapshot,
    command: async () => ({ synchronized: false, refreshError: '服务端已保存，正在重新同步' })
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/collaboration/index.js'), 'utf8'), {
    require: () => ({ createClient: () => client }), Page: value => { definition = value; },
    getApp: () => ({ globalData: { windowInfo: { statusBarHeight: 20 } } }),
    wx: { navigateTo: options => { navigated = options.url; } }
  });
  definition.data = JSON.parse(JSON.stringify(definition.data));
  definition.setData = changes => Object.assign(definition.data, changes);
  return { value: definition, emit: event => listener(event), destination: () => navigated, setPending: value => { pending = value; }, stops: () => stops };
}

const snapshot = {
  actor: { name: '模拟行政', role: 'hq-design-admin' }, tasks: [], messages: [],
  projects: [{ id: 'allowed-project', name: '模拟项目', action: 'dispatch' }], wechat: { deliveries: [], jobs: [] }
};

(async () => {
  const valid = page();
  valid.value.onLoad({ customerId: 'allowed-project' });
  valid.value.apply(snapshot);
  assert.equal(valid.value.data.screen, 'project', '通知深链须在服务端作用域快照就绪后打开项目');
  assert.equal(valid.value.data.activeProject.id, 'allowed-project');
  const denied = page();
  denied.value.onLoad({ customerId: 'not-authorized' });
  denied.value.apply(snapshot);
  assert.equal(denied.value.data.screen, 'workspace');
  assert.match(denied.value.data.error, /无权|不存在/);
  console.log('PASS 通知深链恢复本机服务端会话，只打开授权项目');

  const markup = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/collaboration/index.wxml'), 'utf8');

  valid.value.data.deliveries = [{ id: 'delivery', customerId: 'allowed-project', page: '/pages/collaboration/index?customerId=allowed-project' }];
  valid.value.openDelivery({ currentTarget: { dataset: { id: 'delivery' } } });
  assert.equal(valid.destination(), '/pages/collaboration/index?customerId=allowed-project', '模拟通知必须走网关实际返回的页面');
  console.log('PASS 点击模拟通知沿投递深链进入办理页');

  const ui = page();
  ui.value.onLoad({});
  ui.value.follow();
  ui.emit({ error: '网络未同步' });
  assert.equal(ui.value.data.syncError, true);
  ui.emit({ snapshot });
  assert.equal(ui.value.data.error, '');
  ui.value.data.error = '未填写接收人';
  ui.emit({ snapshot });
  assert.equal(ui.value.data.error, '未填写接收人', '正常轮询不得抹去表单业务错误');
  await ui.value.execute('/subscriptions', { accepted: true });
  assert.equal(ui.value.data.syncError, true, '已保存但未同步的提示应在后续同步恢复后清除');
  ui.emit({ snapshot });
  assert.equal(ui.value.data.error, '');
  console.log('PASS 网络恢复清除同步提示，轮询保留业务校验错误');

  ui.setPending(true);
  ui.value.onShow();
  ui.value.tab({ currentTarget: { dataset: { tab: 'mine' } } });
  assert(ui.value.data.pending, '重开页面或切换标签须保留待核实状态');
  assert(markup.includes('wx:if="{{pending}}" class="error pending"'), '核实入口须独立于业务错误始终展示');
  ui.value.onHide();
  assert.equal(ui.stops(), 1);
  ui.value.onShow();
  ui.emit({ snapshot });
  assert.equal(ui.value.data.connected, true);
  console.log('PASS 重开、切换标签和前后台恢复保留核实入口并恢复同步');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
