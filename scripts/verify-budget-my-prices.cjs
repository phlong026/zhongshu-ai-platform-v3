const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

// 我的当地单价（prices）页回归：平台地区与"基于武汉新建的城市"（自定义地区）两套价格来源
// 都必须在页面上显示为"我的单价"，否则保存成功也看不出来（2026-11-25 用户反馈）。
const root = path.resolve(__dirname, '../miniprogram/design-migration');
const clone = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
const settle = async (ticks = 4) => { for (let i = 0; i < ticks; i++) await flush(); };

const option = (extra) => Object.assign({
  code: 'OPT', selectionGroup: 'GROUP', unit: 'SQM', reason: '', updatedAt: '2026-11-25T00:00:00Z'
}, extra);

function group(options) {
  return [{ itemId: '207001', itemCode: 'FOUNDATION', itemName: '地基基础', category: 'BODY', options }];
}

function pricePage({ custom = false, regionCode = custom ? 'u1-abc123' : 'WUHAN', payload } = {}) {
  let page;
  let session = 'scope-a';
  const calls = [];
  const toasts = [];
  const modals = [];
  const platformRegions = custom ? [{ code: 'WUHAN', name: '武汉' }] : [{ code: 'WUHAN', name: '武汉' }];
  const myRegions = custom ? [{ code: regionCode, name: '黄冈', coefficient: 1, priceVersion: 1 }] : [];
  const deps = {
    '../../utils/navigation': {},
    '../../utils/context': { getApp: () => ({}) },
    '../../utils/access': { protectedPage: definition => { page = definition; } },
    '../../utils/api': {
      getBudgetRegions: () => Promise.resolve(platformRegions),
      listMyRegions: () => Promise.resolve(myRegions),
      getMyPrices: (code) => { calls.push(['get', code]); return Promise.resolve(typeof payload === 'function' ? payload(code) : payload); },
      createMyRegion: () => Promise.resolve({ code: regionCode, name: '黄冈' }),
      setMyPrice: (optionId, code, cents) => { calls.push(['set', optionId, code, cents]); return Promise.resolve({}); },
      resetMyPrice: (optionId, code) => { calls.push(['reset', optionId, code]); return Promise.resolve({}); }
    },
    '../../utils/request': { captureSession: () => session, isSameSession: value => !!value && value === session }
  };
  const wx = {
    showToast: ({ title }) => toasts.push(title),
    showModal: (options) => { modals.push(options); if (options.success) options.success({ confirm: true, content: '黄冈' }); },
    navigateTo() {}, navigateBack() {}
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/budget/prices.js'), 'utf8'), {
    module: { exports: {} }, exports: {}, require: name => {
      if (Object.hasOwn(deps, name)) return deps[name];
      throw new Error('Unexpected require ' + name);
    }, wx, console
  });
  page.data = clone(page.data);
  page.setData = patch => Object.assign(page.data, patch);
  return { page, calls, toasts, modals, changeSession: () => { session = 'scope-b'; } };
}

const manual = option({ optionId: '208001', label: '条形基础', baselinePriceCents: 52000, myPriceCents: 60000, priceSource: 'PROFILE_MANUAL' });
const copied = option({ optionId: '208006', label: '砖混结构', baselinePriceCents: 98000, myPriceCents: 98000, priceSource: 'PROFILE_COPIED' });
const missing = option({ optionId: '208009', label: '木屋顶', baselinePriceCents: null, myPriceCents: null, priceSource: 'MISSING' });

test('新建城市：武汉复制/系数/手工价都显示为我的单价，缺价项保持待补', async () => {
  const h = pricePage({ custom: true, payload: { regionCode: 'u1-abc123', groups: group([manual, copied, missing]) } });
  h.page.onLoad({ regionCode: 'u1-abc123' });
  await settle();
  const options = h.page.data.groups[0].options;
  assert.equal(h.page.data.regionCode, 'u1-abc123');
  assert.equal(h.page.data.regionCustom, true);
  assert.equal(options[0].overridden, true);
  assert.equal(options[0].myText, '¥600.00');
  assert.equal(options[0].sourceLabel, '手工价');
  assert.equal(options[0].canReset, true);
  assert.equal(options[1].overridden, true);
  assert.equal(options[1].myText, '¥980.00');
  assert.equal(options[1].sourceLabel, '武汉复制');
  assert.equal(options[1].canReset, false);
  assert.equal(options[2].overridden, false);
  assert.equal(options[2].missing, true);
  assert.equal(h.page.data.overriddenCount, 2);
});

test('再次编辑回填已保存的当地价，而不是武汉基准价', async () => {
  const h = pricePage({ custom: true, payload: { regionCode: 'u1-abc123', groups: group([manual]) } });
  h.page.onLoad({ regionCode: 'u1-abc123' });
  await settle();
  h.page.startEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.equal(h.page.data.editOptionId, '208001');
  assert.equal(h.page.data.inputValue, '600.00');
});

test('保存后重新读取立即显示我的单价，不再看起来没保存', async () => {
  let version = 0;
  const h = pricePage({
    custom: true,
    payload: () => {
      version++;
      return {
        regionCode: 'u1-abc123',
        groups: group([version === 1
          ? option({ optionId: '208001', label: '条形基础', baselinePriceCents: 52000, myPriceCents: 52000, priceSource: 'PROFILE_COPIED' })
          : option({ optionId: '208001', label: '条形基础', baselinePriceCents: 52000, myPriceCents: 60000, priceSource: 'PROFILE_MANUAL' })])
      };
    }
  });
  h.page.onLoad({ regionCode: 'u1-abc123' });
  await settle();
  // 初始行来自武汉复制，编辑框回填配置价
  h.page.startEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.equal(h.page.data.inputValue, '520.00');
  h.page.onPriceInput({ detail: { value: '600' } });
  await h.page.confirmEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.deepEqual(h.calls.filter(c => c[0] === 'set'), [['set', '208001', 'u1-abc123', 60000]]);
  const saved = h.page.data.groups[0].options[0];
  assert.equal(saved.overridden, true);
  assert.equal(saved.myText, '¥600.00');
  assert.equal(saved.sourceLabel, '手工价');
  assert.equal(h.page.data.overriddenCount, 1);
  assert.equal(h.page.data.editOptionId, null);
  assert.equal(h.page.data.saving, false);
  assert.equal(h.toasts.at(-1), '已保存，仅影响你的新测算');
});

test('平台地区账号覆盖价保持原行为，恢复默认仍可用', async () => {
  const h = pricePage({ custom: false, payload: { regionCode: 'WUHAN', groups: group([
    option({ optionId: '208001', label: '条形基础', baselinePriceCents: 52000, myPriceCents: 60000, priceSource: 'ACCOUNT_OVERRIDE' })
  ]) } });
  h.page.onLoad({ regionCode: 'WUHAN' });
  await settle();
  const row = h.page.data.groups[0].options[0];
  assert.equal(row.overridden, true);
  assert.equal(row.myText, '¥600.00');
  assert.equal(row.sourceLabel, '');
  assert.equal(row.canReset, true);
  h.page.startEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.equal(h.page.data.inputValue, '600.00');
});

test('恢复默认对自定义地区走地区配置重置，并保留手工价以外的行', async () => {
  const h = pricePage({ custom: true, payload: { regionCode: 'u1-abc123', groups: group([manual]) } });
  h.page.onLoad({ regionCode: 'u1-abc123' });
  await settle();
  await h.page.reset({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.deepEqual(h.calls.filter(c => c[0] === 'reset'), [['reset', '208001', 'u1-abc123']]);
  assert.match(h.modals[0].content, /武汉基准价/);
});

test('换会话后不允许再提交保存', async () => {
  const h = pricePage({ custom: true, payload: { regionCode: 'u1-abc123', groups: group([manual]) } });
  h.page.onLoad({ regionCode: 'u1-abc123' });
  await settle();
  h.page.startEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  h.changeSession();
  await h.page.confirmEdit({ currentTarget: { dataset: { optionId: '208001' } } });
  assert.equal(h.calls.filter(c => c[0] === 'set').length, 0);
  assert.equal(h.page.data.saving, false);
});