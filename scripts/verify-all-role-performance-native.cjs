const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const directory = path.resolve(process.env.PERFORMANCE_NATIVE_OUT || 'qa/all-role-performance-20261030/native');
  const fixture = JSON.parse(fs.readFileSync('qa/all-role-performance-20261030/fixtures.json', 'utf8'));
  const runtime = await require('./franchise-native-runtime.cjs').connect('all-role-performance', {
    directory, wsEndpoint:process.env.FRANCHISE_WS || 'ws://127.0.0.1:9907', realEvents:true
  });
  const checks = [];
  try {
    for (const [index, sample] of fixture.fixtures.entries()) {
      // Finish the previous page before replacing the isolated simulator's fixture.
      await runtime.open('/pages/account/index?view=policy', '.account', { relaunch:true });
      await runtime.replace(sample.rows);
      const boss = ['boss', 'owner'].includes(sample.role);
      const profile = boss ? '/pages/admin/profile/index' : ['sales', 'sales-lead'].includes(sample.role) ? '/pages/account/sales/index' : '/pages/profile/index';
      await runtime.open(profile, '.quick-card', { relaunch:true });
      await runtime.event({ selector:'.profile-menu', dataset:{ action:'performance' } }, 'open');
      await runtime.until(async () => (await runtime.current()).route === 'pages/admin/tasks/index', '绩效入口 ' + sample.id);
      if (boss) await runtime.event({ selector:'.tabs button', dataset:{ view:'mine' } }, 'tab');
      await runtime.until(async () => (await runtime.data()).view === 'mine', '本人绩效 ' + sample.id);
      const page = await runtime.data();
      assert.equal(page.error, '', sample.id);
      assert.equal(page.myName, sample.name);
      assert.equal(page.period, fixture.period);
      const goal = page.goalRows.find(row => row.id === sample.taskId);
      assert(goal, sample.id + ' 本人目标');
      assert.equal(goal.target, sample.target);
      assert.equal(goal.collected, sample.collected);
      assert.equal(goal.percent, sample.percent);
      // The Page state can arrive before the native navigation animation finishes.
      await runtime.sleep(1200);
      const bounds = await runtime.rects(['.performance-goals', '.goal-ring', '.list-head']);
      assert(bounds[0]?.width > 0 && bounds[1]?.width > 0, sample.id + ' 目标及圆环可见');
      assert(bounds[0].top < bounds[2].top, sample.id + ' 目标在进展详情上方');
      const screenshot = await runtime.capture(String(index + 1).padStart(2, '0') + '-' + sample.company + '-' + sample.role);
      checks.push({ id:sample.id, person:sample.expectedPerson, target:goal.target, collected:goal.collected, percent:goal.percent, screenshot });
      fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'running', checks, exceptions:runtime.errors }, null, 2));
      console.log('PASS ' + sample.id);
    }
    assert.equal(runtime.errors.length, 0, JSON.stringify(runtime.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'passed', checks, exceptions:runtime.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'failed', error:error.message, checks, exceptions:runtime.errors }, null, 2));
    throw error;
  } finally {
    await runtime.restore();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
