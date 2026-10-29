const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  try {
    const A = require('../miniprogram/utils/account');
    const M = require('../miniprogram/pages/admin/model');
    const F = require('../miniprogram/utils/finance-source');
    const D = require('../miniprogram/utils/finance-desk');
    A.mutate(account => A.loginDemo(account, 'boss'));
    const state = M.load();
    const record = { id:'legacy-salary', payrollId:'legacy-settlement', companyId:state.companyId, projectId:'company:' + state.companyId, total:10000, recipient:'历史人员', status:'待办理' };
    const bonus = { ...record, id:'legacy-task-bonus', payrollId:undefined, performanceArchive:{ kind:'performance-task-bonus-v1' } };
    state.payables.push(record, bonus);
    for (const action of ['payroll-policy', 'payroll-submit', 'performance-bonus-register']) {
      const before = JSON.stringify(state);
      assert.throws(() => M.apply(state, action, 'unused', {}), /薪酬与奖金.*线下办理/);
      assert.equal(JSON.stringify(state), before, '退役命令不能修改历史记录');
    }
    A.mutate(account => A.loginDemo(account, 'finance-dual'));
    for (const row of [record, bonus]) {
      const before = JSON.stringify(state);
      assert.throws(() => M.apply(state, 'pay', row.id, { amount:'100', date:A.localDate(), transaction:'SALARY-EXCLUDED', file:{ name:'历史凭据.pdf' }, checked:true }), /薪酬与奖金.*线下办理/);
      assert.equal(JSON.stringify(state), before, '薪酬奖金不能经通用付款绕过范围限制');
      assert(!F.tasks(state).some(task => task.id === row.id), '历史薪酬奖金不生成办理待办');
      assert(!D.flows(state, 'payables').some(item => item.id === row.id), '薪酬奖金不进入应付办理列表');
    }
    assert(state.payables.some(row => row.id === record.id), '历史账簿记录仍然保留');
    const wxml = fs.readFileSync('miniprogram/pages/admin/operations/index.wxml', 'utf8');
    assert(!/薪酬|奖金|data-view="payroll"/.test(wxml), '运营页不再暴露薪酬奖金入口');
    wx.reLaunch({ url:'/pages/admin/operations/index?view=payroll' });
    assert.equal(getCurrentPages().at(-1).route, 'pages/admin/tasks/index', '旧薪酬直链返回任务管理');
    console.log('PASS 薪酬奖金命令、待办和办理入口退役，历史账簿保持');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
