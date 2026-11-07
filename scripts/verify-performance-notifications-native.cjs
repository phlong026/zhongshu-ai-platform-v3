const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function homeTasks(data, role) {
  if (role === 'sales') return data.todoRows || [];
  if (role === 'engineer') return data.eng?.tasks || [];
  if (role === 'boss') return data.homeTodos || [];
  if (role === 'hq-design-admin') return data.tasks || [];
  return data.deskHome?.previewTasks || [];
}

function todoControl(sample) {
  if (sample.role === 'sales') return [{ selector:'.sales-todo-row', dataset:{ id:sample.expected.todoId } }, 'openFlowTask'];
  if (sample.role === 'engineer') return [{ selector:'.eng-todo button', dataset:{ url:sample.performanceRoute } }, 'openEngineering'];
  if (sample.role === 'boss') return [{ selector:'.home-todo button', dataset:{ key:sample.expected.todoId } }, 'go'];
  if (sample.role === 'hq-design-admin') return [{ selector:'.admin-list-row', dataset:{ url:sample.performanceRoute } }, 'openTask'];
  return [{ selector:'.fin-task', dataset:{ url:sample.performanceRoute } }, 'go'];
}

(async () => {
  const fixture = JSON.parse(fs.readFileSync('qa/all-role-performance-20261030/notification-fixtures.json', 'utf8'));
  const directory = path.resolve(process.env.PERFORMANCE_NOTIFICATION_NATIVE_OUT || 'qa/all-role-performance-20261030/native-notifications');
  const r = await require('./franchise-native-runtime.cjs').connect('performance-notifications', {
    directory, wsEndpoint:process.env.FRANCHISE_WS || 'ws://127.0.0.1:9907', realEvents:true
  });
  const checks = [];
  try {
    const samples = process.env.PERFORMANCE_NATIVE_ROLE ? fixture.fixtures.filter(sample => sample.loginId === process.env.PERFORMANCE_NATIVE_ROLE) : fixture.fixtures;
    for (const [index, sample] of samples.entries()) {
      await r.open('/pages/account/index?view=policy', '.account', { relaunch:true });
      await r.replace(sample.rows);
      await r.open(sample.messagesRoute, '.message', { relaunch:true });
      const messages = (await r.data()).messages;
      assert(messages.some(row => row.id === sample.expected.manualNotificationId));
      assert(messages.some(row => row.id === sample.expected.automaticNotificationId));
      await r.event({ selector:'.message', dataset:{ id:sample.expected.manualNotificationId } }, 'read');
      await r.until(async () => (await r.current()).route === 'pages/admin/tasks/index', '消息进入本人绩效');
      assert.equal((await r.data()).myName, sample.name);
      assert.equal((await r.data()).period, fixture.period);

      await r.open(sample.workbenchRoute, null, { relaunch:true });
      const tasks = homeTasks(await r.data(), sample.role);
      assert(tasks.some(row => (row.id || row.key) === sample.expected.todoId), sample.id + ' 手动待办可见');
      assert(!tasks.some(row => (row.id || row.key) === 'performance:' + sample.expected.automaticTaskId), '自动采集目标不产生待办');
      await r.sleep(1200);
      const homeScreenshot = await r.capture(String(index + 1).padStart(2, '0') + '-' + sample.loginId + '-todo');
      await r.event(...todoControl(sample));
      await r.until(async () => (await r.current()).route === 'pages/admin/tasks/index', '待办进入本人绩效');
      await r.event({ selector:'.task-actions button', dataset:{ id:sample.expected.manualTaskId } }, 'beginManual');
      await r.event('.sheet textarea', 'input', { value:'已完成本月工作复盘，原生隔离测试。' });
      assert.equal((await r.data()).evidence.note, '已完成本月工作复盘，原生隔离测试。', '输入框回写完成说明');
      await r.event('.sheet .primary', 'completeManual');
      // The SDK tap emits tap only; it does not dispatch a form's native submit default action.
      const nativePage = await r.mp.currentPage();
      const form = await nativePage.$('.task-sheet-form') || await nativePage.$('.sheet');
      assert(form, '完成凭证表单绑定');
      await form.trigger('submit', { value:{ note:(await r.data()).evidence.note } });
      assert.equal((await r.data()).error, '', '完成凭证提交错误');
      await r.until(async () => (await r.data()).goalRows?.some(row => row.id === sample.expected.manualTaskId && row.percent === 100), '完成凭证回写圆环');
      await r.sleep(1200);
      const completedScreenshot = await r.capture(String(index + 1).padStart(2, '0') + '-' + sample.loginId + '-completed');
      await r.open(sample.workbenchRoute, null, { relaunch:true });
      assert(!homeTasks(await r.data(), sample.role).some(row => (row.id || row.key) === sample.expected.todoId), sample.id + ' 完成后退出待办');
      checks.push({ id:sample.id, result:'passed', homeScreenshot, completedScreenshot });
      fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'running', checks, exceptions:r.errors }, null, 2));
      console.log('PASS 通知到完成 ' + sample.id);
    }
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'passed', formSubmission:'Element.trigger(submit); SDK tap does not dispatch form default actions', checks, exceptions:r.errors }, null, 2));
  } catch (error) {
    const current = await r.current();
    await r.capture('failure');
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'failed', checks, error:error.message, pageError:current.data?.error, evidence:current.data?.evidence, route:current.route, exceptions:r.errors }, null, 2));
    throw error;
  } finally { await r.restore(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
