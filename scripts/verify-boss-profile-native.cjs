// Isolated WeChat preview only: every request is mocked; no production writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const automator = require('miniprogram-automator');
const directory = path.resolve('docs/boss-profile-20261116/screenshots');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  fs.mkdirSync(directory, { recursive: true });
  let mp;
  for (let attempt = 0; !mp; attempt++) {
    try { mp = await automator.connect({ wsEndpoint: process.env.BOSS_PROFILE_WS || 'ws://127.0.0.1:9954' }); }
    catch (error) { if (attempt === 4) throw error; await delay(800); }
  }
  const errors = [], checks = [];
  mp.on('exception', error => errors.push(error));
  for (let attempt = 0; ; attempt++) {
    if (await mp.evaluate(() => typeof getApp === 'function' && !!getApp())) break;
    if (attempt === 59) throw new Error('Native app did not finish initializing');
    await delay(150);
  }
  const open = async url => {
    await mp.evaluate(url => { getApp().globalData.__bossProfileNavigated = false; setTimeout(() => wx.reLaunch({ url, success: () => { getApp().globalData.__bossProfileNavigated = true; } }), 50); return true; }, url);
    for (let attempt = 0; attempt < 60; attempt++) {
      const state = await mp.evaluate(() => ({ navigated: getApp().globalData.__bossProfileNavigated, route: getCurrentPages().slice(-1)[0]?.route, ready: getCurrentPages().slice(-1)[0]?.data.ready }));
      if (state.navigated && state.route === url.split('?')[0].slice(1) && (state.ready !== false)) { await delay(300); mp.pageMap?.clear(); return true; }
      await delay(150);
    }
    throw new Error('Native page did not become ready: ' + url);
  };
  const data = () => mp.evaluate(() => getCurrentPages().slice(-1)[0].data);
  const rect = selector => {
    const attribute = selector.match(/^\[data-(id|key)="([^"]+)"\]$/);
    const spec = attribute ? { selector: attribute[1] === 'id' ? '.task-option' : '.task-input', key: attribute[1], value: attribute[2] } : { selector };
    return mp.evaluate(spec => new Promise(resolve => wx.createSelectorQuery().selectAll(spec.selector).boundingClientRect().exec(rows => resolve((rows[0] || []).find(row => !spec.key || row.dataset?.[spec.key] === spec.value) || null))), spec);
  };
  const invoke = async (selector, method, detail = {}, dataset = {}) => {
    assert(await rect(selector), 'Rendered native control: ' + selector);
    await mp.evaluate(({method,detail,dataset}) => { setTimeout(() => getCurrentPages().slice(-1)[0][method]({currentTarget:{dataset},detail}),50); return true; }, {method,detail,dataset});
    await delay(400);
  };
  const shot = async name => { await mp.evaluate(() => { wx.hideToast(); wx.hideKeyboard(); return true; }); await delay(200); await mp.screenshot({ path: path.join(directory, name + '.png') }); };
  try {
    await mp.mockWxMethod('request', function (options) {
      const fixture = getApp().globalData.__bossProfileFixture || (getApp().globalData.__bossProfileFixture = { tasks: [], requests: [] });
      const route = String(options.url).split('/admin-api')[1]?.split('?')[0] || '';
      const body = options.data || {}, method = options.method || 'GET';
      fixture.requests.push({ route, method, body, key: options.header?.['Idempotency-Key'] });
      let data = [], code = 0;
      if (method !== 'GET' && !(method === 'POST' && route === '/franchise/performance/tasks')) code = 590001;
      else if (route === '/platform/profile/mine') data = { nickname: '裴火荣', avatar: '/assets/account-avatar.jpg', mobile: '13800000000' };
      else if (route === '/platform/messages/unread-count') data = { count: 0 };
      else if (route === '/franchise/company-points/summary') data = { balance: 9300, inFlight: 0, status: 'ACTIVE' };
      else if (route === '/franchise/company-points/my-usage') data = { used: 0, list: [], total: 0 };
      else if (route === '/platform/org/staff') data = [{ userId: '7', postId: 'p7', name: '张业务', roleCode: 'sales', active: true }];
      else if (route === '/franchise/performance/tasks') {
        if (method === 'POST') {
          data = { id: '501', ...body, status: '进行中', collected: 0 };
          fixture.tasks.push(data);
        } else if (body.period === '2026-10') data = [{ id: '500', period: body.period, title: '每周回访重点客户', type: 'manual', targetCents: 0, assigneeUserId: '7', assigneeName: '张业务', assigneeDept: 'business', due: '2026-10-31', status: '已完成', manualDone: '{"note":"上月已完成"}' }];
        else data = fixture.tasks;
      }
      return { statusCode: 200, data: { code, data, msg: code ? 'Unconfigured fixture write blocked' : '' }, header: {} };
    });
    if (process.argv.includes('--guest-only')) {
      await mp.evaluate(() => {
        ['zs-api-token-v1', 'zs-api-post-v1', 'zs-api-posts-v1', 'zhongshu-account-v1'].forEach(key => wx.removeStorageSync(key));
        getApp().globalData.__bossProfileFixture = { tasks: [], requests: [] };
        return true;
      });
      await open('/pages/profile/index');
      const guest = await data();
      assert.equal(guest.loggedIn, false); assert.equal(guest.learningAvailable, true); assert.equal(guest.courseId, 'ai-video');
      assert(await rect('.academy-section')); assert(await rect('.academy-section .course-card'));
      assert.equal(await rect('.academy-section .course-status'), null);
      assert.equal(await mp.evaluate(() => getApp().globalData.__bossProfileFixture.requests.length), 0);
      await shot('07-guest-profile');
      checks.push('未登录显示学习培训和精选课程，不显示个人进度且不请求个人数据');
      await invoke('.academy-section .course-card', 'open', {}, { action: 'course' });
      let destination;
      for (let attempt = 0; attempt < 50; attempt++) {
        destination = await mp.evaluate(() => { const page=getCurrentPages().slice(-1)[0]; return { route:page.route, view:page.data.view, returnUrl:page.options.return }; });
        if (destination.route === 'pages/account/index' && destination.view === 'login') break;
        await delay(100);
      }
      assert.equal(destination.route, 'pages/account/index'); assert.equal(destination.view, 'login');
      assert.equal(decodeURIComponent(destination.returnUrl), '/pages/account/index?view=course&id=ai-video');
      checks.push('点击课程引导登录，并保留目标课程地址');
      assert.equal(errors.length, 0, JSON.stringify(errors));
      fs.writeFileSync(path.join(directory, '../guest-profile-results.json'), JSON.stringify({ result:'passed', mode:'isolated native WeChat renderer; all requests mocked', checks, exceptions:errors }, null, 2));
      console.log(JSON.stringify({ result:'passed', checks }, null, 2));
      return;
    }
    await mp.evaluate(() => {
      wx.setStorageSync('zs-api-token-v1', 'isolated-native-fixture');
      wx.setStorageSync('zs-api-post-v1', 'boss-fixture');
      wx.setStorageSync('zs-api-posts-v1', [{ postId: 'boss-fixture', orgId: 'fixture-store', orgType: 'store', roleCode: 'boss', orgName: '合家美宅-江西南昌-高新区合家美宅-裴火荣' }]);
      return true;
    });
    console.log('native: profile');
    let page = await open('/pages/profile/index');
    assert.equal((await data()).courseId, 'management');
    const geometry = await mp.evaluate(() => new Promise(resolve => {
      const query = wx.createSelectorQuery();
      ['.profile-card', '.profile-avatar', '.profile-name', '.profile-role-label', '.profile-organization', '.profile-action .profile-link'].forEach(selector => query.select(selector).boundingClientRect());
      query.exec(resolve);
    }));
    assert(geometry.every(Boolean));
    const [card, avatar, name, role, company, link] = geometry;
    assert(avatar.right <= name.left && name.right <= role.left);
    assert(company.top >= name.bottom && company.right <= link.left);
    assert(Math.abs((link.top + link.bottom) / 2 - (card.top + card.bottom) / 2) <= 1);
    assert(avatar.top >= card.top && avatar.bottom <= card.bottom && link.bottom <= card.bottom);
    await shot('01-boss-profile'); checks.push('头像、姓名、岗位、公司和资料入口均在卡片内，无重叠');
    if (process.argv.includes('--profile-only')) {
      assert.equal((await data()).showStoreServices, true);
      const services = await rect('.franchise-section');
      assert(services && services.width > 0 && services.height > 0);
      const serviceActions = await mp.evaluate(() => new Promise(resolve => wx.createSelectorQuery().selectAll('.franchise-section .tile').boundingClientRect().exec(rows => resolve((rows[0] || []).map(row => row.dataset.action)))));
      assert.deepEqual(serviceActions, ['services', 'selection', 'storeMap', 'academy']);
      await mp.evaluate(top => { wx.pageScrollTo({ scrollTop: Math.max(0, top - 100), duration: 0 }); return true; }, services.top);
      await delay(250);
      await shot('08-boss-franchise-services');
      checks.push('门店老板登录后显示展陈设计、众墅甄选、门店地图、商学院四个入口');
      fs.writeFileSync(path.join(directory, '../profile-layout-results.json'), JSON.stringify({ result: 'passed', mode: 'isolated native WeChat renderer; all requests mocked', checks, geometry, exceptions: errors }, null, 2));
      console.log('PASS 个人资料右侧入口及门店登录后的加盟商服务模块均正常显示');
      return;
    }

    console.log('native: courses');
    page = await open('/pages/account/index?view=learning');
    await delay(250);
    assert.equal(((await data()).filteredCourses).length, 6);
    await shot('02-all-courses'); checks.push('老板课程中心显示全部六门课程');

    console.log('native: tasks');
    page = await open('/pages/admin/tasks/index?view=team&period=2026-11');
    assert.equal(await rect('.initialize-previous'), null);
    assert.equal(await rect('.reuse-tasks'), null);
    await shot('03-task-form');
    await invoke('.task-dropdown-toggle','toggleTaskOptions'); await delay(300);
    assert(((await data()).taskOptions).some(option => option.id === 'reuse:500'));
    console.log('native: dropdown options', await mp.evaluate(() => { const d=getCurrentPages().slice(-1)[0].data; return { count:d.taskOptions.length, open:d.taskOptionsOpen, query:d.taskQuery }; }));
    await shot('04-task-dropdown');
    await invoke('[data-id="reuse:500"]','selectTaskOption',{}, {id:'reuse:500'}); await delay(200);
    assert.equal(((await data()).form).due, '2026-11-30');
    assert.equal(((await data()).form).assigneeId, '7');
    assert.equal(((await data()).form).manualDone, undefined);
    await shot('05-reused-task'); checks.push('下拉选择上月任务，只填入本月内容、责任人及有效截止日');

    await invoke('.task-title-input','inputTask',{value:'安排客户回访'}); await delay(150);
    await invoke('[data-id="manual"]','selectTaskOption',{}, {id:'manual'});
    await invoke('.primary','submitTask',{value:{}}); await delay(500);
    const mutations = await mp.evaluate(() => getApp().globalData.__bossProfileFixture.requests.filter(row => row.method === 'POST'));
    assert.equal(mutations.length, 1); assert.equal(mutations[0].body.title, '安排客户回访');
    assert.equal(mutations[0].body.type, 'manual'); assert(mutations[0].key);
    checks.push('原生控件绑定的Page处理器可完成自定义任务派发（请求已拦截）');

    await invoke('.task-dropdown-toggle','toggleTaskOptions'); await delay(150);
    await invoke('[data-id="type:auto:collectionsAmount"]','selectTaskOption',{}, {id:'type:auto:collectionsAmount'});
    await invoke('[data-key="target"]','field',{value:'5000'},{key:'target'});
    await shot('06-metric-task');
    assert.equal(((await data()).form).type, 'auto:collectionsAmount');
    checks.push('同一个下拉框可选业务指标，按需显示目标值和单位');
    assert.equal(errors.length, 0, JSON.stringify(errors));
    fs.writeFileSync(path.join(directory, '../native-results.json'), JSON.stringify({ result: 'passed', mode: 'isolated native WeChat renderer; all requests mocked', checks, geometry, exceptions: errors }, null, 2));
    console.log(JSON.stringify({ result: 'passed', checks, directory }, null, 2));
  } catch (error) {
    const data = await mp.evaluate(() => getCurrentPages().slice(-1)[0]?.data).catch(() => null);
    fs.writeFileSync(path.join(directory, '../native-failure.json'), JSON.stringify({ error: error.stack, data, exceptions: errors }, null, 2));
    throw error;
  } finally {
    await mp.evaluate(() => { ['zs-api-token-v1', 'zs-api-post-v1', 'zs-api-posts-v1'].forEach(key => wx.removeStorageSync(key)); return true; }).catch(() => {});
    await open('/pages/profile/index').catch(() => {});
    await mp.restoreWxMethod('request').catch(() => {});
    await mp.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
