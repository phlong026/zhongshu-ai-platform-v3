const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const studio = require('../miniprogram/utils/studio');
const P = require('../miniprogram/utils/platform-content');

const flush = () => new Promise(resolve => setImmediate(resolve));

function pageFrom(definition) {
  return {
    ...definition,
    data: { ...definition.data },
    routes: [],
    setData(patch) { Object.assign(this.data, patch); },
    go(view, query) { this.routes.push({ view, query }); },
    persistEditor() {}
  };
}

async function main() {
  const originals = {
    token: http.token, sessionGeneration: http.sessionGeneration, currentPost: auth.currentApiPost,
    guard: studio.guard, scope: studio.scope, read: studio.read,
    works: P.api.drafts.works, create: P.api.publications.create, page: P.api.publications.page,
    verify: P.api.publications.verify, link: P.api.publications.link
  };
  const storage = {};
  global.wx = {
    getStorageSync(key) { return storage[key] || ''; }, setStorageSync(key, value) { storage[key] = value; },
    removeStorageSync(key) { delete storage[key]; }, showToast() {}, redirectTo() {},
    reLaunch(options) { if (options.success) options.success(); if (options.complete) options.complete(); }
  };
  let session = 3;
  let post = { postId: 'post-A', orgId: 'org-A', userId: 'user-A' };
  http.token = () => 'token'; http.sessionGeneration = () => session; auth.currentApiPost = () => post;
  studio.guard = () => true; studio.scope = () => 'scope-A';
  studio.read = () => ({
    original: '', script: '', title: '', tasks: [], records: [], works: [], rewriteHistory: [],
    publishDrafts: { 'work-1': { title: '旧草稿', publishText: '旧文案', tags: '#旧', cover: '/old.jpg', workId: 'work-1', selectedAccount: 1, scheduled: '2099-01-01 10:00' } }
  });
  P.api.drafts.works = async () => [];
  let definition;
  global.Page = value => { definition = value; };
  const pagePath = require.resolve('../miniprogram/pages/studio/index');
  delete require.cache[pagePath]; require(pagePath);

  try {
    const restored = pageFrom(definition);
    restored.onLoad({ view: 'publish', work: 'work-1' });
    assert.equal(restored.data.title, '旧草稿');
    assert.equal(restored.data.publishAccount, '', '旧 selectedAccount 不得回填成演示账号');
    assert.equal(restored.data.publishPlatform, 'douyin');
    assert.equal(restored.data.scheduled, undefined, '旧 scheduled 草稿必须忽略');

    assert.equal(P.validatePublicationForm({ title: '标题', publishText: '文案', accountId: ' ' }), '请填写实际发布账号名称或标识');
    assert.equal(P.validatePublicationForm({ title: '标题', publishText: '文案', accountId: 'a'.repeat(65), platform: 'douyin' }), '发布账号名称或标识需为1-64个字符');
    assert.equal(P.validatePublicationForm({ title: '标题', publishText: '文案', accountId: '账号', platform: 'other' }), '请选择发布平台');
    assert.equal(P.validatePublicationForm({ title: '标题', publishText: '文案', accountId: ' 真实账号 ', platform: 'channels', scheduled: '2099-01-01 10:00' }), '', '旧 scheduled 字段不参与首发表单校验');

    const calls = [];
    let deferred;
    P.api.publications.create = (payload, key) => {
      calls.push({ payload, key });
      return new Promise((resolve, reject) => { deferred = { resolve, reject }; });
    };
    const page = pageFrom(definition);
    Object.assign(page.data, {
      workId: 'work-1', title: ' 标题 ', publishText: ' 文案 ', tags: ' #话题 ',
      publishAccount: ' 真实视频号账号 ', publishPlatform: 'channels',
      selectedAccount: 1, scheduled: '2099-01-01 10:00'
    });
    page.submitPublish(); await flush();
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].payload, { workId: 'work-1', title: '标题', publishText: '文案', tags: '#话题', accountId: '真实视频号账号', platform: 'channels' });
    assert.ok(calls[0].key && calls[0].key.length <= 80, '创建发布记录必须传1..80字符稳定键');
    assert.equal(page.data.publicationSaving, true, '提交期间必须防双击');
    page.submitPublish(); assert.equal(calls.length, 1, 'busy 时不得重复提交');
    const firstKey = calls[0].key;
    deferred.reject({ message: '网络失败' }); await flush();
    page.submitPublish(); await flush();
    assert.equal(calls[1].key, firstKey, '相同scope与payload失败重试必须复用同键');
    deferred.reject({ message: '网络失败' }); await flush();

    page.setData({ publishAccount: '另一个账号' });
    page.submitPublish(); await flush();
    assert.notEqual(calls[2].key, firstKey, '账号内容变化必须生成新键');
    session += 1; post = { postId: 'post-B', orgId: 'org-B', userId: 'user-B' };
    deferred.resolve({ id: 'old-session-publication' }); await flush();
    assert.equal(page.routes.length, 0, '切岗后的迟到成功不得导航');

    page.submitPublish(); await flush();
    assert.notEqual(calls[3].key, calls[2].key, '会话/岗位scope变化必须生成新键');
    deferred.resolve({ id: 'publication-2' }); await flush();
    assert.deepEqual(page.routes, [{ view: 'receipt', query: '&record=publication-2' }]);
    assert.equal(page.data.publicationSaving, false);

    const flow = [];
    await P.savePublicationReceipt({ status: 'scheduled', url: 'https://example.com/work/1', currentUrl: '',
      verify: async () => flow.push('verify'), link: async () => flow.push('link') });
    assert.deepEqual(flow, ['verify', 'link'], '历史 scheduled 仅在用户登记链接时人工 verify→link');

    const receiptCalls = [];
    P.api.publications.verify = async (id, key) => { receiptCalls.push({ action: 'verify', id, key }); };
    P.api.publications.link = async (id, url, key) => { receiptCalls.push({ action: 'link', id, url, key }); };
    const receiptPage = pageFrom(definition);
    Object.assign(receiptPage.data, { view: 'receipt', recordId: 'publication-2', publishedUrlInput: 'https://example.com/work/2', detail: { status: 'publishing', publishedUrl: '' } });
    receiptPage.savePublicationLinkOnline(); await flush();
    assert.deepEqual(receiptCalls.map(call => call.action), ['verify', 'link'], '创建后进入receipt由用户手动登记链接');

    const rows = [
      { id: 'scheduled-1', workId: 'work-1', title: '历史定时记录', publishText: '真实文案', tags: '#真实', platform: 'douyin', accountId: '真实抖音号', authorUserId: 'user-B', status: 'scheduled', createTime: '2026-11-14T10:00:00+08:00' },
      { id: 'published-1', workId: 'work-2', title: '已登记记录', publishText: '文案2', tags: '', platform: 'channels', accountId: '真实视频号', authorUserId: 'other-user', status: 'published', publishedUrl: 'https://example.com/published', createTime: '2026-11-14T11:00:00+08:00' }
    ];
    let pageCalls = 0;
    P.api.publications.page = async () => { pageCalls += 1; return rows; };
    const recordsPage = pageFrom(definition); recordsPage.data.view = 'records';
    recordsPage.refreshData(); await flush();
    assert.equal(pageCalls, 1, 'records 必须读取 publications.page');
    assert.equal(recordsPage.data.records.length, 2);
    assert.equal(recordsPage.data.records[0].account, '真实抖音号');
    assert.equal(recordsPage.data.records[0].label, '待手动发布');
    assert.match(recordsPage.data.records[0].note, /定时发布已关闭/);
    recordsPage.changeRecordFilter({ currentTarget: { dataset: { index: 1 } } }); await flush();
    assert.deepEqual(recordsPage.data.records.map(row => row.id), ['scheduled-1'], '进行中筛选保留历史scheduled手动办理记录');

    const detailPage = pageFrom(definition); Object.assign(detailPage.data, { view: 'detail', recordId: 'scheduled-1' });
    detailPage.refreshData(); await flush();
    assert.equal(detailPage.data.detail.publishText, '真实文案');
    assert.equal(detailPage.data.detail.tags, '#真实');
    assert.equal(detailPage.data.detail.account, '真实抖音号');
    assert.equal(detailPage.data.detail.authorUserId, 'user-B');
    assert.match(detailPage.data.detail.displayTime, /11月14日 10:00/, '详情显示后端createTime');
    assert.equal(detailPage.data.detail.canRegisterLink, true);
    detailPage.advancePublication();
    assert.deepEqual(detailPage.routes, [{ view: 'receipt', query: '&record=scheduled-1' }]);

    const failedActionPage = pageFrom(definition);
    failedActionPage.data.detail = { id: 'failed-1', apiRecord: true, status: 'failed', canRegisterLink: true };
    failedActionPage.advancePublication();
    assert.equal(failedActionPage.routes.length, 0, 'failed 详情不得进入无法办理的回执页');
    const publishedActionPage = pageFrom(definition);
    publishedActionPage.data.detail = { id: 'published-1', apiRecord: true, status: 'published', canRegisterLink: true };
    publishedActionPage.advancePublication();
    assert.equal(publishedActionPage.routes.length, 0, 'published 详情不得提供修改链接入口');

    detailPage.data.recordId = 'published-1'; detailPage.refreshData(); await flush();
    assert.equal(detailPage.data.detail.canRegisterLink, false, '明确非本人记录必须只读');

    let rejectList;
    P.api.publications.page = () => new Promise((resolve, reject) => { rejectList = { resolve, reject }; });
    const failedList = pageFrom(definition); failedList.data.view = 'records'; failedList.refreshData(); await flush();
    rejectList.reject({ message: '列表失败' }); await flush();
    assert.match(failedList.data.recordsError, /列表失败/);
    P.api.publications.page = async () => rows;
    failedList.retryRecords(); await flush();
    assert.equal(failedList.data.recordsError, '');
    assert.equal(failedList.data.records.length, 2, '失败后可显式重试');

    let lateResolve;
    P.api.publications.page = () => new Promise(resolve => { lateResolve = resolve; });
    const staleList = pageFrom(definition); staleList.data.view = 'records'; staleList.refreshData(); await flush();
    session += 1; post = { postId: 'post-C', orgId: 'org-C', userId: 'user-C' };
    lateResolve(rows); await flush();
    assert.equal(staleList.data.records.length, 0, '切岗后的列表迟到结果不得回写');

    const wxml = fs.readFileSync(require.resolve('../miniprogram/pages/studio/index.wxml'), 'utf8');
    assert.equal(wxml.includes('chooseSchedule'), false, '首发页面不得保留定时入口');
    assert.equal(wxml.includes('众墅之家 · 建房顾问'), false, '不得显示硬编码演示账号');
    assert.equal(wxml.includes('众墅之家 · 乡墅设计'), false, '不得显示硬编码演示账号');
    assert.ok(wxml.includes('手动发布后登记链接'));
    assert.equal(wxml.includes('moreSettings'), false, '不得保留打开空白弹层的更多设置入口');
    assert.equal(wxml.includes('修改链接'), false, '已登记链接只允许复制，不得显示修改入口');
    assert.ok(wxml.includes('detail.canRegisterLink'), '详情必须按明确作者关系隐藏登记操作');
    const studioSource = fs.readFileSync(require.resolve('../miniprogram/pages/studio/index.js'), 'utf8');
    assert.equal(studioSource.includes('授权已失效'), false, '手动账号元数据不得暗示存在平台授权');
    console.log('verify-studio-manual-publication ✔ 手动账号、无定时、稳定键与历史scheduled人工登记通过');
  } finally {
    http.token = originals.token; http.sessionGeneration = originals.sessionGeneration; auth.currentApiPost = originals.currentPost;
    studio.guard = originals.guard; studio.scope = originals.scope; studio.read = originals.read;
    P.api.drafts.works = originals.works; P.api.publications.create = originals.create; P.api.publications.page = originals.page;
    P.api.publications.verify = originals.verify; P.api.publications.link = originals.link;
    delete global.Page; delete global.wx;
  }
}

main().catch(error => { console.error(error); process.exit(1); });
