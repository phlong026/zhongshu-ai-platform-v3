const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const automator = require('miniprogram-automator');
const notices = require('./preview-profile-announcements.cjs');
const { trainingByRole } = notices;
const { roles } = require('../miniprogram/utils/organization');

const profile = 'pages/profile/index';
const list = 'pages/account/announcements/index';
const detail = 'pages/account/announcements/detail';
const storeRoles = ['boss', 'owner', 'sales', 'sales-lead', 'engineer', 'project-manager', 'finance', 'finance-lead'];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const output = path.resolve(process.argv[2] || '.');

async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9676' });
  const errors = [];
  mp.on('exception', error => errors.push(error));
  const read = () => mp.evaluate(() => {
    const page = getCurrentPages().at(-1);
    return page && { route: page.route, data: page.data };
  });
  async function waitFor(route, ready = () => true) {
    for (let attempt = 0; attempt < 50; attempt++) {
      const state = await read();
      if (state?.route === route && ready(state.data)) return state.data;
      await delay(150);
    }
    const state = await read();
    throw Error('Page did not become ready: ' + route + ' (current route ' + state?.route + ')');
  }
  const scroll = async scrollTop => {
    await mp.evaluate(y => { wx.pageScrollTo({ scrollTop: y, duration: 0 }); return true; }, scrollTop);
    await delay(350);
  };
  const shot = name => mp.screenshot({ path: path.join(output, name + '.png') });
  const tap = async (selector, index = 0) => {
    mp.pageMap?.clear();
    const matches = await (await mp.currentPage()).$$(selector);
    assert.ok(matches[index], 'Rendered native control: ' + selector);
    await matches[index].tap();
  };

  try {
    // Seed only the isolated DevTools copy, which has a different AppID and a loopback backend.
    await mp.evaluate(storeRoles => {
      const account = wx.getAccountInfoSync().miniProgram;
      if (account.appId !== 'touristappid') throw Error('Expected the isolated tourist app');
      const post = { postId: 'preview-boss', userId: '701', orgId: '31', brandId: 'preview-brand', roleCode: 'boss', orgType: 'store', orgName: '合家美宅 · 江西南昌' };
      wx.setStorageSync('zs-api-base', 'http://127.0.0.1:39776/admin-api');
      wx.setStorageSync('zs-api-token-v1', 'local-profile-demo');
      wx.setStorageSync('zs-api-user-v1', '701');
      wx.setStorageSync('zs-api-posts-v1', storeRoles.map(roleCode => ({ ...post, postId: 'preview-' + roleCode, roleCode })));
      wx.setStorageSync('zs-api-post-v1', post.postId);
      wx.reLaunch({ url: '/pages/profile/index' });
      return true;
    }, storeRoles);
    await waitFor(profile, data => data.announcementTitle === notices[0].title && data.courseId === 'management' && data.unread === 3 && data.name === '陈明');
    await delay(300);
    fs.mkdirSync(output, { recursive: true });
    const selectors = ['.profile-card', '#company-points', '.announcement-section', '.message-section', '.management-section', '.franchise-section', '.academy-section', '.support-section', '.profile-logout'];
    const geometry = await mp.evaluate(selectors => new Promise(resolve => {
      const query = wx.createSelectorQuery();
      selectors.forEach(selector => query.select(selector).boundingClientRect());
      query.selectAll('.franchise-section .tile').boundingClientRect();
      query.exec(resolve);
    }), selectors);
    const { windowWidth, windowHeight, platform } = await mp.systemInfo();
    const tiles = geometry.pop();
    assert.ok(geometry.every(Boolean), 'All eight sections and the logout button exist');
    for (const [i, rect] of geometry.entries()) {
      assert.ok(rect.height > 0 && rect.left >= 0 && rect.right <= windowWidth + 1, selectors[i] + ' fits width');
      if (i) assert.ok(rect.top >= geometry[i - 1].bottom - 1, selectors[i] + ' preserves section order');
    }
    assert.deepEqual(tiles.map(tile => tile.dataset.action), ['services', 'selection', 'storeMap', 'academy']);
    assert.equal(tiles[0].top, tiles[1].top);
    assert.equal(tiles[2].top, tiles[3].top);
    assert.ok(tiles[2].top >= tiles[0].bottom);
    await shot('我的-首屏');
    await scroll(9999);
    await shot('我的-下半屏');
    await scroll(0);
    await tap('.announcement-section button');
    await waitFor(list, data => data.items.length === 3);
    await delay(500);
    await shot('公司公告-列表');
    await tap('.announcement-row');
    const pinned = await waitFor(detail, data => data.announcement?.id === notices[0].id);
    assert.equal(pinned.announcement.pinned, true);
    assert.match(pinned.announcement.content, /本地模拟公告/);
    await shot('公司公告-置顶详情');
    await mp.navigateBack();
    await waitFor(list, data => data.items.length === 3);
    await delay(500);
    mp.pageMap?.clear();
    const search = await (await mp.currentPage()).$('.announcement-search input');
    await search.input('账本');
    await tap('.search-submit');
    await waitFor(list, data => data.items.length === 1 && data.items[0].id === notices[2].id);
    const checkedRoles = [];
    // The shared template is checked here through the native Tab. The finance-desk shell
    // and all eight roles are covered separately by the profile navigation regressions.
    for (const role of ['sales', 'engineer']) {
      const [courseId, title, , resource, completed] = trainingByRole[role];
      process.stdout.write('Checking the native profile for ' + role + '\n');
      await mp.evaluate(({ role, profile }) => {
        wx.setStorageSync('zs-api-post-v1', 'preview-' + role);
        wx.reLaunch({ url: '/' + profile });
        return true;
      }, { role, profile });
      const data = await waitFor(profile, data => data.announcementTitle === notices[0].title && data.role === roles[role]);
      assert.equal(data.courseId, courseId, role);
      assert.equal(data.courseTitle, title, role);
      assert.equal(data.progress.completed, completed, role);
      const fixed = await mp.evaluate(() => new Promise(resolve => {
        const query = wx.createSelectorQuery();
        ['.announcement-section', '.franchise-section', '.academy-section'].forEach(selector => query.select(selector).boundingClientRect());
        query.selectAll('.franchise-section .tile').boundingClientRect();
        query.exec(resolve);
      }));
      assert.ok(fixed.slice(0, 3).every(rect => rect && rect.height > 0), role + ' has all three fixed sections');
      assert.equal(fixed[3].length, 4, role + ' has all four franchise services');
      await scroll(9999);
      await tap('.academy-section .text-link');
      const catalog = await waitFor('pages/account/training/index', data => data.visibleCourses?.length === 1);
      assert.equal(catalog.visibleCourses[0].title, title, role);
      assert.deepEqual(catalog.visibleResources.map(row => row.name), [resource], role);
      await delay(500);
      await mp.navigateBack();
      await waitFor(profile, data => data.role === roles[role]);
      checkedRoles.push(role);
    }
    await mp.evaluate(url => {
      wx.setStorageSync('zs-api-post-v1', 'preview-boss');
      wx.reLaunch({ url });
      return true;
    }, '/' + profile);
    await waitFor(profile, data => data.announcementTitle === notices[0].title);
    assert.equal(errors.length, 0, JSON.stringify(errors));
    const result = { platform, windowWidth, windowHeight, geometry, checkedRoles, announcements: notices.map(row => row.title) };
    fs.writeFileSync(path.join(output, 'native-result.json'), JSON.stringify(result, null, 2));
    process.stdout.write(JSON.stringify(result) + '\n');
  } finally {
    mp.disconnect();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
