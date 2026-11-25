const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..'), tick = () => new Promise(resolve => setImmediate(resolve));
const copy = value => JSON.parse(JSON.stringify(value));
function harness(file = 'index', options = {}) {
  let definition, session = 'one'; const emitted = [], routes = [], backs = [];
  const requires = {
    '../../utils/navigation': {}, '../../utils/context': { getApp: () => { throw Error('选择参考不能写全局设计状态'); } },
    '../../utils/access': { protectedPage: value => definition = value, openFeature: url => routes.push({ url }) },
    '../../utils/api': {
      listCases: async () => ({ list: [{ caseId: '900719925474099999', title: '庭院三层', buildingArea: 300, floorCount: 3 }], nextCursor: null }),
      getCaseDetail: async id => ({ caseId: id, title: '庭院三层', parameters: {}, allowedActions: [] }),
      listFavorites: async () => ({ list: [], nextCursor: null })
    },
    '../../utils/request': { captureSession: () => session, isSameSession: value => value === session },
    '../../utils/assets': {}, '../../utils/format': { styleLabel: () => '' },
    '../../utils/case-classifications': { floors: () => [], styles: async () => [] },
    '../../../utils/design-case-images': { watch() {} }
  };
  const wx = { navigateTo: value => routes.push(value), navigateBack: value => backs.push(value || {}), showToast() {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/design-migration/pages/library/' + file + '.js'), 'utf8'), {
    require: name => { if (!(name in requires)) throw Error(name); return requires[name]; }, wx
  });
  const page = { ...definition, data: copy(definition.data), setData(patch) { Object.assign(this.data, copy(patch)); },
    getOpenerEventChannel: () => ({ emit: (name, value) => { emitted.push({ name, value: copy(value) }); if (options.emit) options.emit(name, value); } }) };
  return { page, emitted, routes, backs, switchSession: () => session = 'two' };
}

test('灵感列表选择仅可见API案例，用事件回传客户页，普通浏览入口保留', async () => {
  const h = harness(); h.page.onLoad({ pick: 'workflow', customerId: '42' }); await tick();
  h.page.chooseReference({ currentTarget: { dataset: { id: 'not-loaded' } } }); assert.equal(h.emitted.length, 0);
  h.page.chooseReference({ currentTarget: { dataset: { id: '900719925474099999' } } });
  assert.deepEqual(h.emitted, [{ name: 'workflowReference', value: { caseId: '900719925474099999', title: '庭院三层' } }]);
  assert.equal(h.backs.length, 1);
  const normal = harness(); normal.page.onLoad({}); await tick();
  normal.page.openDetail({ currentTarget: { dataset: { id: '900719925474099999' } } });
  assert.equal(normal.routes[0].url, '/design-migration/pages/library/detail?id=900719925474099999');
  normal.page.chooseReference({ currentTarget: { dataset: { id: '900719925474099999' } } }); assert.equal(normal.emitted.length, 0);
});

test('先预览详情再选用能跨两级返回原客户，选择模式不出现生成工具', async () => {
  const list = harness(); list.page.onLoad({ pick: 'workflow' }); await tick();
  list.page.openDetail({ currentTarget: { dataset: { id: '900719925474099999' } } });
  const navigation = list.routes[0]; assert.match(navigation.url, /pick=workflow/);
  const detail = harness('detail', { emit: (_name, value) => navigation.events.workflowReference(value) });
  detail.page.onLoad({ pick: 'workflow', id: '900719925474099999' }); await tick();
  detail.page.chooseReference(); assert.equal(list.emitted[0].value.caseId, '900719925474099999');
  assert.equal(list.backs.length, 0); assert.equal(detail.backs[0].delta, 2);
  const { render } = require('./role-page-runtime.cjs');
  const flatten = nodes => nodes.flatMap(n => [n, ...flatten(n.children || [])]);
  const controls = flatten(render(path.join(root, 'miniprogram/design-migration/pages/library/detail.wxml'), { ...detail.page.data, accessReady: true }));
  assert.ok(controls.some(n => n.attrs?.bindtap === 'chooseReference'));
  assert.ok(!controls.some(n => n.attrs?.bindtap === 'useHouse'));
});

test('切岗后列表与详情均不回传旧品牌案例', async () => {
  const list = harness(); list.page.onLoad({ pick: 'workflow' }); await tick(); list.switchSession();
  list.page.chooseReference({ currentTarget: { dataset: { id: '900719925474099999' } } });
  list.page.onShow(); assert.equal(list.emitted.length, 0); assert.equal(list.page.data.cases.length, 0);
  const detail = harness('detail'); detail.page.onLoad({ pick: 'workflow', id: '900719925474099999' }); await tick();
  detail.switchSession(); detail.page.chooseReference(); assert.equal(detail.emitted.length, 0); assert.equal(detail.backs.length, 0);
});
