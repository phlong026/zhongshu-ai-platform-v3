const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { connect } = require('./franchise-native-runtime.cjs');

const phase = process.argv[2] || 'after';
assert(['before', 'after'].includes(phase), '参数须为 before 或 after');
const directory = path.resolve('qa/performance-task-layout-20261030', phase);
fs.mkdirSync(directory, { recursive: true });

async function fixtures() {
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const Model = require('../miniprogram/pages/admin/model');
  const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(require('../miniprogram/pages/admin/owner-performance').facts);
  const keys = require('../miniprogram/utils/storage-keys');
  wx.setStorageSync(keys.management, require('../miniprogram/utils/management-seed')());
  wx.setStorageSync(keys.business, { version: 1, customers: [] });
  wx.setStorageSync(keys.engineering, { version: 1, projects: [] });
  const login = id => A.mutate(account => A.loginDemo(account, id));
  const snapshot = () => wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]);
  login('boss');
  const state = Model.load(), period = '2026-10', title = '武汉门店十月份材料到货验收与客户交付资料完整性复核任务';
  const manual = Tasks.create(state, { period, title, assigneeId: 'finance-dual', due: period + '-31', type: 'manual', note: '', idempotencyKey: 'layout-manual' });
  Tasks.create(state, { period, title: '完成本月经营回款核对并跟踪全部项目到账记录', assigneeId: 'finance-dual', due: period + '-31', type: 'auto:collectionsAmount', target: '999999.99', idempotencyKey: 'layout-auto' });
  const team = snapshot();
  login('finance-dual');
  const mine = snapshot();
  login('boss');
  Tasks.archive(Model.load(), period, { note: '布局验证夹具', idempotencyKey: 'layout-archive' });
  return { team, mine, archive: snapshot(), taskId: manual.id, title };
}

(async () => {
  const seed = await fixtures();
  const r = await connect('performance-layout-' + phase, { directory, wsEndpoint: process.env.PERFORMANCE_LAYOUT_WS || 'ws://127.0.0.1:10063', realEvents: true });
  const results = [];
  const selectors = ['.task-content', '.task-form', '.task-field', '.task-input', '.task-select', '.picker', '.task-card', '.task-title', '.status', '.facts', '.sheet', '.sheet-head', '.task-textarea', '.primary', '.archive-card'];
  async function capture(name) {
    await r.mp.pageScrollTo(0);
    const geometry = await r.mp.evaluate(selectors => new Promise(resolve => {
      const q = wx.createSelectorQuery();
      selectors.forEach(selector => q.selectAll(selector).boundingClientRect());
      q.exec(rows => resolve(Object.fromEntries(selectors.map((selector, index) => [selector, rows[index] || []]))));
    }), selectors);
    const file = await r.capture(name + '-top');
    results.push({ name, file, route: (await r.current()).route, geometry });
    await r.mp.pageScrollTo(100000);
    await r.sleep(120);
    await r.capture(name + '-bottom');
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ phase, results, exceptions: r.errors }, null, 2));
  }
  try {
    await r.replace(seed.team);
    await r.open('/pages/admin/tasks/index?view=team&period=2026-10', '.task-page');
    await r.mp.evaluate(title => { const p = getCurrentPages().at(-1); p.selectType({ detail: { value: 1 } }); p.setData({ 'form.title': title, 'form.target': '999999.99' }); }, seed.title);
    await capture('01-team');
    if (phase === 'after') {
      const count = (await r.data()).rows.length;
      await r.mp.evaluate(() => getCurrentPages().at(-1).selectAssignee({ detail: { value: 0 } }));
      r.mp.pageMap.clear();
      const form = (await r.data()).form;
      await (await (await r.mp.currentPage()).$('.task-form-body')).trigger('submit', { value: { title: form.title, target: form.target } });
      await r.until(async () => (await r.data()).rows.length === count + 1, '原生表单submit事件派发任务');
    }
    await r.mp.pageScrollTo(0);
    await r.mp.evaluate(id => getCurrentPages().at(-1).beginReassign({ currentTarget: { dataset: { id } } }), seed.taskId);
    await capture('02-reassign');
    await r.replace(seed.mine);
    await r.open('/pages/admin/tasks/index?view=mine&period=2026-10', '.task-page');
    await capture('03-mine');
    await r.mp.evaluate(id => { const p = getCurrentPages().at(-1); p.beginManual({ currentTarget: { dataset: { id } } }); p.setData({ 'evidence.note': '已完成项目材料核对，补充说明与凭据供负责人复核。' }); }, seed.taskId);
    await capture('04-evidence');
    if (phase === 'after') {
      r.mp.pageMap.clear();
      await (await (await r.mp.currentPage()).$('.task-sheet-form')).trigger('submit', { value: { note: (await r.data()).evidence.note } });
      await r.until(async () => !(await r.data()).evidenceTask, '原生完成凭证表单提交');
      assert((await r.data()).rows.some(row => row.id === seed.taskId && row.status === '已完成'), '原生表单submit事件正确提交备注并完成任务');
    }
    await r.replace(seed.archive);
    await r.open('/pages/admin/tasks/index?view=archive&period=2026-10', '.task-page');
    await capture('05-archive');
    if (phase === 'after') {
      const team = results[0].geometry;
      assert(team['.task-input'].length >= 2, '派发表单存在两个可测输入框');
      for (const field of [...team['.task-input'], ...team['.task-select']]) {
        assert(field.height >= 44, '输入和选择框至少44px高');
        const parent = team['.task-form'][0];
        assert(field.left >= parent.left && field.right <= parent.right, '字段完整处于表单卡片内');
      }
      for (const row of results.filter(row => row.geometry['.sheet'].length)) {
        const sheet = row.geometry['.sheet'][0], title = row.geometry['.sheet-head'][0];
        assert(title.height >= 44, '弹层长标题不得把关闭按钮压扁');
        assert(title.left >= sheet.left && title.right <= sheet.right, '弹层标题完整位于框内');
      }
      assert.equal(r.errors.length, 0, '原生运行无异常');
    }
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ phase, result: 'passed', formEventMethod: 'Element.trigger(submit); SDK tap does not dispatch the native form default action', results, exceptions: r.errors }, null, 2));
    console.log('PASS 原生绩效任务5种状态截图与布局边界检查');
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error: error.stack, page: await r.current(), exceptions: r.errors }, null, 2));
    await r.capture('failure');
    throw error;
  } finally { await r.restore(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
