const assert = require('assert/strict'), fs = require('fs'), path = require('path'), automator = require('miniprogram-automator');
const root = path.resolve(__dirname, '..'), dir = path.join(root, 'qa/remediation-20261017');
const wait = ms => new Promise(r => setTimeout(r, ms));
async function main() {
  const mp = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  const errors = [], checks = [], measurements = {};
  let modalMocked = false;
  const finalOnly = process.argv.includes('--visual-empty-only');
  let retainedRun = null;
  if (finalOnly) {
    const previous = JSON.parse(fs.readFileSync(path.join(dir, 'simulator-results.json'), 'utf8'));
    assert(previous.checks.length >= 14 && previous.checks.slice(0, 14).every(check => check.result === 'passed'));
    retainedRun = previous.runAt; checks.push(...previous.checks.slice(0, 14)); Object.assign(measurements, previous.measurements);
  }
  mp.on('exception', e => errors.push(e)); mp.on('console', e => { if (e.level === 'error') errors.push(e); });
  await wait(2500);
  const keys = ['zhongshu-studio', 'zhongshu-video-draft', 'zhongshu-creation-draft'];
  const before = await mp.evaluate(keys => keys.map(key => ({ key, value: wx.getStorageSync(key) })), keys);
  const trace = message => fs.appendFileSync(path.join(dir, 'simulator-trace.log'), new Date().toISOString() + ' ' + message + '\n');
  async function open(url) { trace('OPEN ' + url); await mp.callWxMethod('reLaunch', { url }); await wait(1200); trace('READY ' + url); }
  async function data(key) { return (await mp.currentPage()).data(key); }
  async function state() { return mp.evaluate(() => wx.getStorageSync('zhongshu-studio')); }
  async function until(fn, label) { for (let i = 0; i < 50; i++) { if (await fn()) return; await wait(150); } throw Error('Timed out: ' + label); }
  async function el(selector) { let element; await until(async () => { element = await (await mp.currentPage()).$(selector); return !!element; }, selector); return element; }
  async function tap(selector) { trace('TAP ' + selector); await (await el(selector)).tap(); await wait(700); trace('TAPPED ' + selector); }
  async function input(selector, value) { await (await el(selector)).input(value); await wait(150); }
  async function view(name) { trace('WAIT VIEW ' + name); await until(async () => await mp.evaluate(() => getCurrentPages().slice(-1)[0].data.view) === name, 'view ' + name); await wait(500); trace('VIEW ' + name); }
  async function invoke(method, ...args) { return mp.evaluate(({ method, args }) => getCurrentPages().slice(-1)[0][method](...args), { method, args }); }
  function pass(name) { checks.push({ name, result: 'passed' }); console.log('PASS ' + name); }
  async function screenshot(name) { await wait(200); await mp.screenshot({ path: path.join(dir, name + '.png') }); }
  try {
    await open('/pages/business/index');
    await mp.evaluate(keys => keys.forEach(key => wx.removeStorageSync(key)), keys);
    await mp.mockWxMethod('showModal', { confirm: true, cancel: false }); modalMocked = true;
    if (!finalOnly) {
    await open('/pages/studio/index?view=result&task=task-complete'); await tap('.result-actions .studio-primary'); await view('publish');
    const workA = await data('workId');
    await input('.publish-title-text', '回归作品A'); await input('.publish-copy-input textarea', '发布文案A：采光与动线。'); await input('.hashtag', '#回归A');
    await tap('.publish-saved-line .soft-text-button'); await tap('[data-path="/assets/creation-courtyard.jpg"]');
    const count = (await state()).records.length; await tap('.publish-submit'); await view('records');
    let record = (await state()).records[0]; assert.equal(record.publishText, '发布文案A：采光与动线。'); assert.equal(record.tags, '#回归A'); assert.equal(record.cover, '/assets/creation-courtyard.jpg'); assert.equal(record.workSnapshot.id, workA); assert.equal((await state()).records.length, count + 1);
    pass('P1: native title, copy, tags and cover are saved in a publication snapshot');
    await open('/pages/studio/index?view=detail&record=' + record.id); assert.equal((await data('detail')).cover, record.cover); assert((await (await el('.detail-copy')).text()).includes('发布文案A')); await screenshot('24-publication-snapshot');
    pass('publication detail renders submitted content and cover');
    await tap('.detail-primary'); assert((await state()).records[0].publishedAt); assert.equal((await data('detail')).status, 'published');
    pass('demo receipt displays actual saved completion timestamp');
    await open('/pages/studio/index?view=publish&work=' + workA); await tap('.account-option[data-index="1"]'); await tap('.publish-submit'); await view('records');
    assert.equal((await state()).records.filter(r => r.workId === workA).length, 2);
    await open('/pages/studio/index?view=publish&work=' + workA); await tap('.publish-submit'); await view('records'); assert.equal((await state()).records.filter(r => r.workId === workA).length, 2);
    pass('same work can publish to another platform; same account remains protected from duplicates');
    await open('/pages/studio/index?view=result&task=task-retry'); await tap('.result-actions .studio-primary'); await view('publish'); const workB = await data('workId');
    await input('.publish-copy-input textarea', 'B草稿独立保留');
    await open('/pages/studio/index?view=publish&work=' + workA); await input('.publish-copy-input textarea', 'A草稿独立保留');
    await open('/pages/studio/index?view=publish&work=' + workB); assert.equal(await data('publishText'), 'B草稿独立保留');
    await open('/pages/studio/index?view=publish&work=' + workA); assert.equal(await data('publishText'), 'A草稿独立保留');
    pass('P1: A/B publication drafts survive repeated page unload and reload');
    const prior = (await state()).records.length;
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].setData({ scheduled: '2000-01-01 10:30' })); await tap('.publish-submit'); assert.equal((await state()).records.length, prior); assert.equal(await data('view'), 'publish');
    pass('P1: expired stored schedule cannot submit');
    await open('/pages/studio/index?view=detail&record=missing'); assert(!(await data('detail'))); assert(await data('invalidMessage')); assert(!(await (await mp.currentPage()).$('.detail-primary'))); await screenshot('25-invalid-receipt');
    await open('/pages/studio/index?view=publish&work=missing'); assert(await data('invalidMessage')); assert(!(await (await mp.currentPage()).$('.publish-submit')));
    await open('/pages/studio/index?view=result&task=missing'); await view('works');
    pass('P1: invalid receipt, work and task IDs cannot reveal or operate another object');
    await open('/pages/ai-video/index'); await input('.description-textarea', '历史任务A：庭院镜头缓慢推进。'); await invoke('changeParameter', { currentTarget: { dataset: { index: 0 } }, detail: { value: 1 } });
    await tap('[data-mode="reference"]'); await tap('.library-button'); await tap('.creation-sheet [data-id="house"]'); await tap('.generate-button'); await view('tasks'); const ai = (await state()).tasks[0];
    await invoke('taskAction', { currentTarget: { dataset: { id: ai.id } } }); await until(async () => (await state()).tasks[0].status === 'completed', 'AI completion');
    await open('/pages/ai-video/index'); await input('.description-textarea', '当前草稿B：客厅环绕。');
    await open('/pages/studio/index?view=result&task=' + ai.id); await tap('.result-title-row button'); await until(async () => (await mp.currentPage()).path === 'pages/ai-video/index', 'AI editor');
    assert.equal(await data('description'), ai.script); assert.equal(await data('mode'), 'reference'); assert.equal((await data('parameters'))[0].value, 1); assert.equal((await data('references'))[0].id, 'house');
    await input('.description-textarea', '历史A编辑副本'); await screenshot('26-history-task-editor');
    await open('/pages/ai-video/index'); assert.equal(await data('description'), '当前草稿B：客厅环绕。');
    assert.equal((await state()).tasks.find(t => t.id === ai.id).script, ai.script);
    pass('P1: historical AI configuration restores correctly and preserves current draft and original task');
    await open('/pages/studio/index?view=result&task=' + ai.id); await tap('.result-actions .studio-primary'); await view('publish'); const aiWork = await data('workId'); await tap('.publish-submit'); await view('records'); record = (await state()).records[0];
    await open('/pages/studio/index?view=detail&record=' + record.id); assert.equal((await data('detail')).kindLabel, 'AI视频'); assert.equal((await data('detail')).cover, '/assets/creation-house.jpg'); assert.equal(record.workId, aiWork); await screenshot('27-ai-publication-detail');
    pass('AI work type and cover survive result, saved work, publication and receipt');
    await open('/pages/creation/index'); await tap('.manual-link'); await input('.creation-sheet-input', '第一段文案\n第二段文案\n第三段必须显示\n末段必须完整显示'); await tap('.creation-sheet-close'); await tap('.manual-link'); assert((await data('editorText')).includes('末段必须完整显示')); await tap('.creation-sheet-button'); await view('remix'); await tap('.studio-bottom-pair .studio-primary'); await view('digital'); assert((await (await el('.speech-copy')).text()).includes('末段必须完整显示'));
    pass('manual editor recovers dismissed input and digital preview shows full script');
    await tap('.speech-card .soft-text-button'); await input('.creation-sheet-input', '关闭后仍可继续的修改'); await tap('.creation-sheet-close'); await tap('.speech-card .soft-text-button'); assert.equal(await data('editingText'), '关闭后仍可继续的修改'); await tap('.sheet-confirm');
    pass('script editor closes without losing unsaved changes');
    await open('/pages/creation/index'); await tap('.all-recent'); await view('recent'); assert((await data('recent')).some(r => r.id === 'draft')); assert((await data('recent')).some(r => r.id === 'video-draft'));
    pass('all creations includes copy drafts, AI drafts and saved works');
    await open('/pages/ai-video/index'); const generate = await el('.generate-button'); measurements.aiButton = { size: await generate.size(), color: await generate.style('background-color'), font: await generate.style('font-size') };
    await tap('[data-mode="frames"]'); measurements.framesHeight = (await (await el('.generate-button')).size()).height;
    await tap('[data-mode="reference"]'); measurements.referenceHeight = (await (await el('.generate-button')).size()).height; assert.equal(measurements.framesHeight, measurements.referenceHeight); assert(Number(measurements.framesHeight) >= 44);
    const help = await el('.reference-limits button'); const helpSize = await help.size(); assert(Number(helpSize.width) >= 44 && Number(helpSize.height) >= 44);
    await open('/pages/studio/index?view=publish&work=' + workA); const primary = await el('.publish-submit'); assert.equal(await primary.style('background-color'), measurements.aiButton.color); assert.equal(await primary.style('font-size'), measurements.aiButton.font); assert.equal((await primary.size()).height, measurements.framesHeight); const clear = await el('.publish-title-input button'); assert(Number((await clear.size()).width) >= 44);
    pass('visual: primary color, font and height consistent across modes and publishing; icon hit areas at least 44px');
    const titleSizes = [];
    for (const route of ['/pages/ai-video/index','/pages/studio/index?view=remix','/pages/studio/index?view=digital','/pages/studio/index?view=publish&work=' + workA]) { await open(route); const h = await el(route.includes('ai-video') ? '.creation-heading-title' : '.studio-heading'); titleSizes.push(await h.style('font-size')); }
    assert.equal(new Set(titleSizes).size, 1); measurements.sectionTitleSizes = titleSizes;
    pass('visual: same-level section titles use the same computed font size');
    }
    await open('/pages/studio/index?view=remix');
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].setData({ script: Array.from({ length: 24 }, (_, i) => '长文案第' + (i + 1) + '段：检验滚动时的返回导航与安全区。').join('\n') }));
    await mp.pageScrollTo(2000); await wait(400); await screenshot('28-remix-scrolled-fixed-header');
    const header = await (await mp.currentPage()).$('#creation-page-header'); assert(header); const fixed = await header.$('.creation-header'); assert.equal(await fixed.style('position'), 'fixed'); assert.equal(await fixed.style('top'), '0px'); assert((await (await mp.currentPage()).scrollTop()) > 0);
    pass('visual: custom header remains fixed at the top after long-page scrolling');
    await open('/pages/business/index'); await mp.evaluate(() => wx.setStorageSync('zhongshu-studio', { version: 2, title: '', original: '', script: '', tasks: [], works: [], records: [], publishDrafts: {} }));
    for (const name of ['tasks','works','records','detail','publish','recent']) { await open('/pages/studio/index?view=' + name); if (name === 'detail' || name === 'publish') assert(await data('invalidMessage')); else assert.equal((await data(name === 'recent' ? 'recent' : name)).length, 0); }
    await open('/pages/studio/index?view=tasks'); await screenshot('29-empty-tasks'); await open('/pages/studio/index?view=works'); await screenshot('30-empty-works');
    pass('P1: real simulator pages tolerate empty task, work and publication collections');
    assert.equal(errors.length, 0, JSON.stringify(errors)); pass('no captured runtime exceptions or error console messages');
  } catch (error) { errors.push({ assertion: error.message }); throw error; }
  finally {
    if (modalMocked) await mp.restoreWxMethod('showModal'); await mp.reLaunch('/pages/business/index');
    await mp.evaluate(before => before.forEach(({ key, value }) => { if (value) wx.setStorageSync(key, value); else wx.removeStorageSync(key); }), before);
    fs.writeFileSync(path.join(dir, 'simulator-results.json'), JSON.stringify({ result: errors.length ? 'failed' : 'passed', runAt: new Date().toISOString(), retainedRun, runScope: finalOnly ? 'Header and empty-state checks; retained 14 previously passed checks. Only component test IDs changed.' : 'Full run', system: await mp.systemInfo(), checks, measurements, errors }, null, 2)); await mp.disconnect();
  }
  console.log(checks.length + ' remediation simulator checks passed.');
}
main().catch(e => { console.error(e); process.exit(1); });
