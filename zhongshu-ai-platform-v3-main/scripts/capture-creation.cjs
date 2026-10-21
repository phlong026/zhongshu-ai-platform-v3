const fs = require('fs');
const path = require('path');
const automator = require('miniprogram-automator');
const suffix = process.argv[2] || 'round1';
const root = path.resolve(__dirname, '..');
const targets = ['creation', 'video-frames', 'video-reference', 'remix', 'digital', 'tasks', 'result', 'publish', 'records', 'detail'];
async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  const previous = await mp.evaluate(() => ({ studio: wx.getStorageSync('zhongshu-studio'), video: wx.getStorageSync('zhongshu-video-draft'), copy: wx.getStorageSync('zhongshu-creation-draft') }));
  const measurements = {};
  const focusSelectors = { creation: '.remix-card', 'video-frames': '.media-card', 'video-reference': '.description-card', remix: '.original-card', digital: '.actor-card', tasks: '.task-card', result: '.video-stage', publish: '.account-card', records: '.record-card', detail: '.detail-information' };
  try {
    await mp.reLaunch('/pages/business/index');
    await mp.evaluate(() => { wx.removeStorageSync('zhongshu-studio'); wx.removeStorageSync('zhongshu-video-draft'); wx.removeStorageSync('zhongshu-creation-draft'); });
    for (const target of targets) {
      let route = target === 'creation' ? '/pages/creation/index' : target.startsWith('video-') ? '/pages/ai-video/index' : '/pages/studio/index?view=' + target;
      if (target === 'publish') {
        await mp.evaluate(() => getCurrentPages().slice(-1)[0].saveResult());
        const workId = await mp.evaluate(() => wx.getStorageSync('zhongshu-studio').works.find(work => work.taskId === 'task-complete').id);
        route += '&work=' + encodeURIComponent(workId);
      }
      const page = await mp.reLaunch(route);
      if (target === 'digital') {
        // The supplied presenter screenshot shows a later copy revision containing “与”.
        // Apply that revision through the real editor handler; ordinary navigation
        // always carries the user's current text unchanged.
        await mp.evaluate(() => { const page = getCurrentPages().slice(-1)[0]; page.setData({ editingText: page.data.script.replace('安静通风', '安静与通风') }); page.saveScript(); });
      }
      if (target === 'video-reference') await (await page.$('[data-mode="reference"]')).tap();
      await page.waitFor(1800);
      const focus = await page.$(focusSelectors[target]);
      if (focus) measurements[target] = { ...(await focus.offset()), ...(await focus.size()) };
      await mp.screenshot({ path: path.join(root, 'qa/screenshots', target + '-' + suffix + '.png') });
      if (target === 'remix') {
        await mp.pageScrollTo(650); await page.waitFor(600);
        measurements.remixScrollTop = await page.scrollTop();
        await mp.screenshot({ path: path.join(root, 'qa/screenshots', target + '-bottom-' + suffix + '.png') });
      }
      console.log('Captured ' + target);
    }
    fs.writeFileSync(path.join(root, 'qa/creation-capture-info.json'), JSON.stringify({ system: await mp.systemInfo(), suffix, targets, measurements }, null, 2));
  } finally {
    await mp.reLaunch('/pages/business/index');
    await mp.evaluate(previous => {
      for (const [key, value] of [['zhongshu-studio', previous.studio], ['zhongshu-video-draft', previous.video], ['zhongshu-creation-draft', previous.copy]]) {
        if (value) wx.setStorageSync(key, value); else wx.removeStorageSync(key);
      }
    }, previous);
    await mp.reLaunch('/pages/creation/index'); await mp.disconnect();
  }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
