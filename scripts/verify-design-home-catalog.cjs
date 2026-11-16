const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

function flush() {
  return new Promise(resolve => setImmediate(resolve));
}

function harness(options = {}) {
  let definition;
  let token = options.token === undefined ? 'token-1' : options.token;
  let post = options.post === undefined ? { postId: 'post-1', roleCode: 'sales' } : options.post;
  let sessionGeneration = 1;
  const requests = [];
  const downloads = [];
  const navigations = [];
  const pageResult = options.pageResult || Promise.resolve({ list: [], nextCursor: null });
  const api = {
    cases: {
      page(params) { requests.push(params); return typeof pageResult === 'function' ? pageResult(params) : pageResult; },
      downloadUrl(caseId, assetId) {
        downloads.push([String(caseId), String(assetId)]);
        if (options.downloadError) return Promise.reject(options.downloadError);
        return Promise.resolve({ downloadUrl: 'https://image.example/' + assetId });
      }
    }
  };
  const PF = {
    caseRow(row) {
      return {
        id: String(row.caseId), title: row.title, coverAssetId: row.coverAssetId,
        faceWidth: row.faceWidth, depth: row.depth, floorCount: row.floorCount
      };
    },
    loadCaseCovers(rows, download, alive) {
      assert.ok(rows.length <= 10, 'each catalog page must remain bounded');
      return Promise.all(rows.map(async row => {
        if (!row.coverAssetId) return { ...row, coverLoading: false, coverError: true };
        const result = await download(row.id, row.coverAssetId);
        if (!alive()) return row;
        return { ...row, coverUrl: result.downloadUrl, coverLoading: false, coverError: false };
      }));
    },
    errorMessageDesign(error) { return error && error.message || '户型资料加载失败'; }
  };
  const http = {
    token: () => token,
    sessionGeneration: () => sessionGeneration
  };
  const source = fs.readFileSync(path.join(ROOT, 'miniprogram/pages/design/index.js'), 'utf8');
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    Promise,
    require(name) {
      if (name.endsWith('/api/auth')) return { currentApiPost: () => post };
      if (name.endsWith('/api/http')) return http;
      if (name.endsWith('/api/franchise/design')) return api;
      if (name.endsWith('/utils/platform-design')) return PF;
      throw new Error('unexpected require: ' + name);
    },
    wx: { navigateTo({ url }) { navigations.push(url); } }
  }, { filename: 'pages/design/index.js' });
  definition.data = JSON.parse(JSON.stringify(definition.data));
  definition.setData = patch => {
    Object.entries(patch).forEach(([key, value]) => { definition.data[key] = value; });
  };
  return {
    page: definition, requests, downloads, navigations,
    setSession(nextToken, nextPost) { token = nextToken; post = nextPost; sessionGeneration += 1; },
    unload() { definition.onUnload(); }
  };
}

const rows = [1, 2, 3, 4, 5].map(n => ({
  caseId: n, title: '户型' + n, coverAssetId: 100 + n,
  faceWidth: 12, depth: 15, floorCount: 2
}));

test('authorized design home loads every row in its first catalog page', async () => {
  const h = harness({ pageResult: Promise.resolve({ list: rows, nextCursor: 'next' }) });
  h.page.onShow();
  assert.equal(h.page.data.catalogLoading, true);
  await flush(); await flush();
  assert.equal(JSON.stringify(h.requests), JSON.stringify([{ limit: 10 }]));
  assert.equal(h.page.data.catalogCases.length, 5);
  assert.equal(h.downloads.length, 5);
  assert.match(h.page.data.catalogCases[0].coverUrl, /^https:\/\/image\.example\//);
  assert.equal(h.page.data.catalogError, '');
});

test('catalog load failure is visible and retry starts a fresh request', async () => {
  let calls = 0;
  const h = harness({ pageResult: () => {
    calls += 1;
    return calls === 1 ? Promise.reject(new Error('公开案例服务暂不可用')) : Promise.resolve({ list: rows.slice(0, 1) });
  } });
  h.page.onShow();
  await flush(); await flush();
  assert.equal(h.page.data.catalogLoading, false);
  assert.match(h.page.data.catalogError, /暂不可用/);
  h.page.retryCatalog();
  await flush(); await flush();
  assert.equal(calls, 2);
  assert.equal(h.page.data.catalogCases.length, 1);
  assert.equal(h.page.data.catalogError, '');
});

test('missing or unauthorized post does not request the B catalog', async () => {
  const h = harness({ token: '', post: null });
  h.page.onShow();
  await flush();
  assert.equal(h.requests.length, 0);
  assert.equal(h.page.data.entranceEnabled, false);
  assert.equal(h.page.data.catalogCases.length, 0);
});

test('late catalog result cannot refill the page after account or post changes', async () => {
  const pending = deferred();
  const h = harness({ pageResult: pending.promise });
  h.page.onShow();
  h.setSession('', null);
  h.page.onShow();
  pending.resolve({ list: rows.slice(0, 2) });
  await flush(); await flush();
  assert.equal(h.page.data.catalogCases.length, 0);
});

test('a failed cover can be retried without reloading the catalog', async () => {
  const h = harness();
  h.page.onShow();
  await flush();
  h.page.data.catalogCases = [{ id: '9', title: '户型9', coverAssetId: '109', coverError: true, coverLoading: false, coverUrl: '' }];
  h.page.retryCatalogCover({ currentTarget: { dataset: { id: '9' } } });
  assert.equal(h.page.data.catalogCases[0].coverLoading, true);
  await flush(); await flush();
  assert.equal(JSON.stringify(h.downloads), JSON.stringify([['9', '109']]));
  assert.equal(h.page.data.catalogCases[0].coverUrl, 'https://image.example/109');
  assert.equal(h.requests.length, 1);
});

test('cover retry ignores duplicate taps while loading and contains a rejected download', async () => {
  const h = harness({ downloadError: { code: 401, message: '登录已失效' } });
  h.page.onShow();
  await flush();
  h.page.data.catalogCases = [{ id: '9', title: '户型9', coverAssetId: '109', coverError: true, coverLoading: true, coverUrl: '' }];
  h.page.retryCatalogCover({ currentTarget: { dataset: { id: '9' } } });
  assert.equal(h.downloads.length, 0);
  h.page.data.catalogCases[0].coverLoading = false;
  h.page.retryCatalogCover({ currentTarget: { dataset: { id: '9' } } });
  await flush(); await flush();
  assert.equal(h.downloads.length, 1);
  assert.equal(h.page.data.catalogCases[0].coverLoading, false);
  assert.match(h.page.data.catalogError, /登录已失效/);
});

test('home cards use the canonical direct case route and expose image failure retry', () => {
  const h = harness();
  h.page.data.catalogCases = [{ id: 'case 1', coverAssetId: 'asset-1' }];
  h.page.openCatalogCase({ currentTarget: { dataset: { id: 'case 1' } } });
  assert.equal(h.navigations[0], '/pages/workflow/explore/index?view=case&id=case%201');
  const wxml = fs.readFileSync(path.join(ROOT, 'miniprogram/pages/design/index.wxml'), 'utf8');
  assert.match(wxml, /catalogCases/);
  assert.match(wxml, /retryCatalog/);
  assert.match(wxml, /retryCatalogCover/);
  assert.match(wxml, /coverUrl/);
  assert.match(wxml, /暂无封面/);
});


test('home reaches the second page, deduplicates rows, and stops at the end', async () => {
  const h = harness({pageResult: params => Promise.resolve(params.cursor ? {list: rows.slice(3), nextCursor: null} : {list: rows.slice(0, 4), nextCursor: 'next'})});
  h.page.onShow(); await flush(); await flush();
  h.page.onReachBottom(); await flush(); await flush();
  assert.deepEqual(Array.from(h.page.data.catalogCases, r => r.id), ['1','2','3','4','5']);
  assert.equal(h.requests[1].cursor, 'next');
  h.page.onReachBottom(); assert.equal(h.requests.length, 2);
});

test('failed next page keeps previous cases and retries the same cursor', async () => {
  let fails = true;
  const h = harness({pageResult: params => !params.cursor ? Promise.resolve({list: rows.slice(0,2), nextCursor:'next'}) : fails ? Promise.reject(Error('网络暂不可用')) : Promise.resolve({list: rows.slice(2), nextCursor:null})});
  h.page.onShow(); await flush(); await flush();
  h.page.loadMoreCatalog(); await flush();
  assert.equal(h.page.data.catalogCases.length,2); assert.equal(h.page.data.catalogCursor,'next');
  fails = false; h.page.retryCatalog(); await flush(); await flush();
  assert.equal(h.requests[2].cursor,'next'); assert.equal(h.page.data.catalogCases.length,5);
});
