const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');

const accounts = [
  { id: 'sales-dual', label: '业务', roles: ['sales', 'sales-lead'], companyId: 'quanzhou', home: 'pages/business/index' },
  { id: 'engineering-dual', label: '工程', roles: ['engineer', 'project-manager'], companyId: 'quanzhou', home: 'pages/build/home/index' },
  { id: 'finance-dual', label: '财务', roles: ['finance', 'finance-lead'], companyId: 'quanzhou', home: 'pages/management/index' },
  { id: 'boss', label: '老板', roles: ['boss', 'owner'], companyId: 'quanzhou', home: 'pages/admin/home/index' },
  { id: 'hq-design-admin', label: '总部设计', roles: ['hq-design-admin'], companyId: 'headquarters', home: 'pages/workflow/commission/index', view: 'admin' },
  { id: 'hq-finance-dual', label: '总部财务', roles: ['finance', 'finance-lead'], companyId: 'headquarters', home: 'pages/management/index' }
];

process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9890';
process.env.FRANCHISE_NATIVE_OUT = process.env.FRANCHISE_NATIVE_OUT || path.resolve('qa/demo-login-picker-20261023/native');

(async () => {
  const runtime = require('./franchise-native-runtime.cjs');
  const r = await runtime.connect('demo-login-picker', { realEvents: true });
  const checks = [];
  const screens = [];
  const originalPage = await r.mp.evaluate(() => getCurrentPages().at(-1)?.route || 'pages/home/index');
  const pass = name => {
    checks.push({ name, result: 'passed' });
    console.log('PASS ' + name);
  };
  const shot = async name => {
    await r.mp.evaluate(() => { wx.hideToast(); return true; });
    await r.sleep(500);
    screens.push(await r.capture(name));
  };
  const retry = async (fn, label) => {
    let lastError;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try { return await fn(); }
      catch (error) { lastError = error; await r.sleep(500); }
    }
    throw new Error(label + ': ' + lastError.message);
  };
  const route = async () => {
    try { return await r.mp.evaluate(() => getCurrentPages().at(-1)?.route || ''); }
    catch (_) { return ''; }
  };
  const session = () => retry(() => r.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session), 'read account session');
  const navigate = async url => {
    const target = url.split('?')[0].slice(1);
    try { await r.mp.callWxMethod('reLaunch', { url }); }
    catch (error) {
      if (!/timeout waiting for automator response/.test(error.message)) throw error;
    }
    await r.until(async () => {
      try { return await r.mp.evaluate(target => getCurrentPages().at(-1)?.route === target, target); }
      catch (_) { return false; }
    }, 'navigate ' + url, 12000);
  };
  const openAccount = async view => {
    await navigate('/pages/account/index?view=' + view);
    await r.until(async () => {
      try { return await r.mp.evaluate(view => getCurrentPages().at(-1)?.data?.view === view, view); }
      catch (_) { return false; }
    }, 'account ' + view, 12000);
    await r.until(async () => (await r.rects([view === 'login' ? '.demo-user-picker' : '.identity-option']))[0], 'rendered account ' + view, 12000);
    r.mp.pageMap?.clear();
  };
  const login = async account => {
    await openAccount('login');
    const data = await r.data();
    const index = data.demoUsers.findIndex(person => person.id === account.id);
    assert(index >= 0, '登录列表存在 ' + account.id);
    await r.event('.demo-user-picker', 'selectDemoUser', { value: index });
    await r.event('.agreement-row .check-row', 'toggle');
    await r.event('.page-actions .solid', 'login');
    await r.until(async () => (await r.rects(['.sheet .solid']))[0], 'login dialog', 12000);
    await r.event('.sheet .solid', 'confirmModal');
    await r.until(async () => {
      if ((await route()) !== account.home) return false;
      if (!account.view) return true;
      try { return await r.mp.evaluate(view => getCurrentPages().at(-1)?.data?.view === view, account.view); }
      catch (_) { return false; }
    }, 'settled home ' + account.id, 12000);
    await r.sleep(500);
  };
  const restore = async () => {
    const navigationErrors = [];
    try {
      // 导航超时也必须继续恢复原始存储。
      try { await navigate('/pages/account/index?view=policy'); }
      catch (error) { navigationErrors.push({ target: 'policy', error: error.message }); }
      await r.replace(r.before);
      try { await navigate('/' + originalPage); }
      catch (error) { navigationErrors.push({ target: originalPage, error: error.message }); }
      await r.sleep(500);
      await r.replace(r.before);
      await r.sleep(250);
      await r.replace(r.before);
      const expected = JSON.stringify([...r.before].sort((a, b) => a[0].localeCompare(b[0])));
      const actual = JSON.stringify((await r.snapshot()).sort((a, b) => a[0].localeCompare(b[0])));
      const restored = actual === expected;
      fs.writeFileSync(path.join(r.directory, 'demo-login-picker-restored.json'), JSON.stringify({ restored, keys: r.before.length, originalPage, navigationErrors }, null, 2));
      assert(restored, '原生验收后模拟器存储未完整恢复');
    } finally {
      await r.mp.disconnect();
    }
  };

  try {
    await r.replace([]);
    await openAccount('login');
    const loginData = await r.data();
    assert.deepEqual(loginData.demoUsers, accounts.map(({ id, label }) => ({ id, label })));
    assert.equal(loginData.selectedDemoUser, 'sales-dual');
    assert.equal(loginData.demoUserLabel, '业务');
    await shot('01-login-default-business');
    pass('登录列表按序仅显示业务、工程、财务、老板、总部设计、总部财务，默认业务');

    for (const account of accounts) {
      await login(account);
      let active = await session();
      assert.equal(active.staffId, account.id === 'boss' ? 'zhao-boss' : account.id);
      assert.equal(active.companyId, account.companyId);
      assert.equal(await route(), account.home);

      await openAccount('identity');
      let identity = await r.data();
      assert.deepEqual(identity.roleOptions.map(option => option.id), account.roles);
      assert.equal(identity.profile.name, account.id === 'boss' ? '赵老板' : {
        'sales-dual': '业务兼岗人员',
        'engineering-dual': '工程兼岗人员',
        'finance-dual': '门店财务兼岗人员',
        'hq-design-admin': '总部设计行政',
        'hq-finance-dual': '总部财务兼岗人员'
      }[account.id]);

      for (const role of account.roles) {
        await r.event({ selector: '.identity-option', dataset: { role } }, 'selectRole');
        identity = await r.data();
        assert.equal(identity.selectedRole, role);
        if (account.id === 'boss' && role === 'owner') await shot('02-boss-owner-identity');
        if (account.id === 'hq-design-admin') await shot('03-headquarters-design-admin');
        if (account.id === 'hq-finance-dual' && role === 'finance-lead') await shot('04-headquarters-finance-lead');
        await r.event('.page-actions .solid', 'switchRole');
        await r.until(async () => {
          if ((await route()) !== account.home) return false;
          if (!account.view) return true;
          try { return await r.mp.evaluate(view => getCurrentPages().at(-1)?.data?.view === view, account.view); }
          catch (_) { return false; }
        }, 'switch ' + account.id + ' ' + role, 12000);
        await r.sleep(500);
        active = await session();
        assert.equal(active.staffId, account.id === 'boss' ? 'zhao-boss' : account.id);
        assert.equal(active.companyId, account.companyId);
        assert.equal(active.role, role);
        assert.equal(await route(), account.home);
        if (role !== account.roles.at(-1)) await openAccount('identity');
      }
      pass(account.label + '通过真实原生控件登录，并逐一切换所有授权岗位');
    }

    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(r.directory, 'results.json'), JSON.stringify({
      result: 'passed',
      kind: 'Native WeChat login picker and identity controls',
      realEvents: true,
      visibleAccounts: accounts.map(({ id, label }) => ({ id, label })),
      checks,
      screens,
      interactions: r.interactions,
      exceptions: r.errors
    }, null, 2));
    console.log(JSON.stringify({ result: 'passed', checks: checks.length, screens: screens.length, interactions: r.interactions.length }));
  } catch (error) {
    fs.writeFileSync(path.join(r.directory, 'failed.json'), JSON.stringify({
      error: error.stack,
      current: { route: await route() },
      checks,
      screens,
      interactions: r.interactions,
      exceptions: r.errors
    }, null, 2));
    throw error;
  } finally {
    await restore();
  }
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
