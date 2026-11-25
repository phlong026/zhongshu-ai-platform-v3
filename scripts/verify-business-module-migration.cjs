const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const P = require('../miniprogram/utils/platform-content');
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const event = (key, value) => ({ currentTarget: { dataset: key }, detail: { value } });
const plain = value => JSON.parse(JSON.stringify(value));

function taskApi(post) {
  const mod = { exports: {} };
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/franchise/content'), 'utf8'), {
    module: mod,
    require(name) { assert.equal(name, '../http'); return { post }; }
  });
  return mod.exports.tasks;
}

function harness(file, api = {}, role = 'sales-lead', persistence = {}) {
  if(file==='studio/characters/index.js')api={...api,personas:require('./persona-draft-fixture.cjs')(api.personas)};
  let linkDraft;
  let page, scope = 'post-1', generation = 1;
  let token = 'token', orgType = 'store';
  const seq = {}, storage = persistence.storage || new Map(), navigations = [], toasts = [], calls = [];
  const state = { title: '', original: '', script: '', tasks: [], records: [], works: [], rewriteHistory: [], publishDrafts: {} };
  const studio = { title: '', kindLabel:kind=>kind==='ai'?'AI视频':'数字人口播', guard: () => true, allowed: () => true, scope: () => scope, scopedKey: key => key + ':' + scope, read: () => state, write: value => Object.assign(state, value), recentItems: () => [] };
  const http = { tenantId:()=>1,token: () => token, sessionGeneration: () => generation, requestGuard: domain => { const g = generation, n = seq[domain] = (seq[domain] || 0) + 1; return () => generation === g && seq[domain] === n; } };
  const utility = { ...P, api, pollTask: async id => {
    calls.push(['poll', id]); return api.poll ? api.poll(id) : { id, status: 'SUCCEEDED', result: { text: '原视频完整文案' } };
  } };
  const wx = {
    getStorageSync: k => storage.get(k) || '', setStorageSync: (k, v) => storage.set(k, plain(v)), removeStorageSync: k => storage.delete(k),
    navigateTo: ({ url }) => navigations.push(url), redirectTo: ({ url }) => navigations.push(url),
    showToast: ({ title }) => toasts.push(title), showLoading() {}, hideLoading() {}, pageScrollTo() {}, nextTick(fn) { fn(); }
  };
  const draftModule={exports:{}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/creation-link-draft'),'utf8'),{module:draftModule,require:p=>require('../miniprogram/utils/'+p.slice(2)),wx,Uint8Array,unescape,encodeURIComponent});linkDraft=draftModule.exports;
  const source=file==='creation/index.js'?fs.readFileSync(path.resolve(__dirname,'../miniprogram/utils/sales-creation-page.js'),'utf8').replace('module.exports=()=>({','Page({'):fs.readFileSync(path.resolve(__dirname, '../miniprogram/pages', file), 'utf8');
  vm.runInNewContext(source, {
    Page: value => { page = value; }, wx, setTimeout: (fn,ms) => {const t=setTimeout(fn,ms);t.unref();return t;},clearTimeout,
    require(name) {
      if(name==='./material-library')return {data:{},handlers:{}};
      if(name.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(name.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if(name==='./content-task-result')return require('../miniprogram/pages/studio/content-task-result');
      if(name==='./draft-api')return ()=>api.personas;
      if(name==='./profile-draft')return require('../miniprogram/pages/studio/characters/profile-draft');
      if(name.startsWith('./'))name='../../utils/'+name.slice(2);
      if(name.endsWith('/utils/content-task-result'))return require('../miniprogram/pages/studio/content-task-result');
      if(name.endsWith('/task-display'))return require('../miniprogram/pages/studio/task-display');
      if (name.endsWith('/utils/studio')) return studio;
      if (name.endsWith('/utils/creation')) return { assets: [] };
      if (name.endsWith('/utils/creation-link-draft')) return linkDraft;
      if (name.endsWith('/utils/platform-content')) return utility;
      if (name.endsWith('/api/http')) return http;
      if (name.endsWith('/api/auth')) return { currentApiPost: () => ({ postId: scope, roleCode: role,orgType,orgId:persistence.orgId||'33',userId:persistence.userId||'77' }) };
      if (name.endsWith('/api/franchise/content')) return api;
      if (name.endsWith('/api/franchise/crm')) return { leads: { capture: async () => ({ result: 'CREATED', customerId: 'customer-1' }) } };
      if (name.endsWith('/utils/storage-keys')) return { creationDraft: 'creation-draft' };
      if (name.endsWith('/utils/page-navigation')) return { open: url => navigations.push(url) };
      throw Error('Unexpected dependency ' + name);
    }
  });
  page.data = plain(page.data); page.actorScope = scope; page.actorSessionGeneration = generation;
  page.setData = patch => { for (const [key, value] of Object.entries(patch)) {
    const segments = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = page.data;
    for (const segment of segments.slice(0, -1)) target = target[segment] || (target[segment] = {});
    target[segments.at(-1)] = value;
  } };
  return { page, state, calls, navigations, storage, toasts,wx, changeScope() { scope = 'post-2'; generation++; }, changeRole(value) { role = value; }, changeOrgType(value) { orgType = value; }, logout() { token = ''; generation++; } };
}

test('link submission locks immediately and duplicate tap does not create another paid parse', async () => {
  const pending = deferred(); let count = 0;
  const h = harness('creation/index.js', {
    links: { parse: () => { count++; return pending.promise; } },
    scripts: { extract: async () => ({ taskId: 'asr' }) },
    poll: async id => ({ id, status: 'SUCCEEDED', result: id === '1' ? { videoUrl: 'https://media.example/a.mp4' } : { text: '原视频完整文案' } })
  });
  h.page.data.link = 'https://v.example/video';
  const run = h.page.extract(); h.page.extract(); assert.equal(h.page.data.extracting, true); assert.equal(count, 1);
  pending.resolve({ taskId: '1' }); await run;
  assert.equal(h.state.original, '原视频完整文案'); assert.equal(h.navigations.length, 1); assert.equal(h.page.data.extracting, false);
});

test('media-only link continues through ASR with actual source URL and keeps source attribution', async () => {
  const extracted = [];
  const h = harness('creation/index.js', {
    links: { parse: async url => { assert.equal(url, 'https://v.example/video'); return { taskId: 'parse' }; } },
    scripts: { extract: async (body, key) => { extracted.push([plain(body), key]); return { taskId: 'asr' }; } },
    poll: async id => ({ id, status: 'SUCCEEDED', result: id === 'parse' ? { videoUrl: 'https://media.example/a.mp4', durationMs: 10000, title: '原视频' } : { text: '转写完整文案' } })
  });
  h.page.data.link = '分享说明 https://v.example/video'; await h.page.extract();
  assert.deepEqual(extracted[0][0], { sourceUrl: 'https://media.example/a.mp4', durationSec: 10 });
  assert.equal(h.state.original, '转写完整文案'); assert.equal(h.state.extraction.sourceTitle, '原视频');
});

for (const mediaField of ['videoUrl', 'audioUrl']) {
  test(`empty captions and optional caption save failures do not block ${mediaField} transcription`, async () => {
    for (const resultState of ['PENDING', 'FAILED', 'READY']) {
      for (const caption of [undefined, null, '', '   ']) {
        const extracted = [], sourceUrl = 'https://media.example/source';
        const h = harness('creation/index.js', {
          links: { parse: async () => ({ taskId: 'parse' }) },
          scripts: { extract: async body => { extracted.push(plain(body)); return { taskId: 'asr' }; } },
          poll: async id => id === 'parse'
            ? { id, status: 'SUCCEEDED', resultState, result: { [mediaField]: sourceUrl, ...(caption === undefined ? {} : { text: caption }) } }
            : { id, status: 'SUCCEEDED', resultState: 'READY', result: { text: '实际口播转写结果' } }
        });
        h.page.data.link = 'https://v.example/video'; await h.page.extract();
        assert.deepEqual(extracted, [{ sourceUrl }]);
        assert.deepEqual(h.calls, [['poll', 'parse'], ['poll', 'asr']]);
        assert.equal(h.state.original, '实际口播转写结果');
        assert.equal(h.page.data.extractError, ''); assert.equal(h.navigations.length, 1);
      }
    }
  });
}

const shareTitle = '乡村自建房 花钱最凶的六点 # 泸州自建房# 乡村自建房别墅# 乡村自建房';
const userShare = '7.64 f@o.qr QkC:/ :6pm 08/03 ' + shareTitle + String.raw`  https\://v.douyin.com/Tldsyq0f8lY/ 复制此链接，打开Dou音搜索，直接观看视频！`;

test('the reported Douyin share is normalized and the caption never replaces the spoken transcript', async () => {
  const pending = deferred(), extracted = [];
  const transcript = '视频中的完整口播内容（测试转写结果），与发布标题和话题不同。';
  const h = harness('creation/index.js', {
    links: { parse: async url => { assert.equal(url, 'https://v.douyin.com/Tldsyq0f8lY/'); return { taskId: 'parse' }; } },
    scripts: { extract: async (body, key) => { extracted.push([plain(body), key]); return { taskId: 'asr' }; } },
    poll: async id => id === 'parse'
      ? { id, status: 'SUCCEEDED', result: { text: shareTitle, title: shareTitle, videoUrl: 'https://media.example/video.mp4', audioUrl: 'https://media.example/audio.mp3', durationMs: 41000 } }
      : pending.promise
  });
  h.page.inputLink({ detail: { value: userShare } });
  const run = h.page.extract(); await flush();
  assert.equal(h.page.data.extractStage, '提取文案中'); assert.equal(h.page.data.extracting, true);
  assert.equal(h.navigations.length, 0); assert.equal(h.state.original, '');
  assert.deepEqual(extracted[0][0], { sourceUrl: 'https://media.example/audio.mp3', durationSec: 41 });
  pending.resolve({ id: 'asr', status: 'SUCCEEDED', result: { text: transcript } }); await run;
  assert.equal(h.state.original, transcript); assert.equal(h.state.script, '');
  assert.equal(h.state.extraction.sourceTitle, shareTitle);
  assert.equal(h.state.extraction.sourceUrl, 'https://v.douyin.com/Tldsyq0f8lY/');
  assert.equal(h.state.extraction.taskId, 'asr');
  assert.deepEqual(h.navigations, ['/pages/studio/index?view=remix']);
  assert.equal(h.page.data.extracting, false);
  const editor = harness('studio/index.js'); Object.assign(editor.state, h.state);
  editor.page.data.view = 'remix'; editor.page.refreshData();
  assert.equal(editor.page.data.original, transcript, 'the studio textarea receives the actual transcript');
});

test('share URL parsing handles normal links, escaped slashes and trailing punctuation consistently', () => {
  assert.equal(P.validateShareLink(userShare), '');
  assert.equal(P.shareLinkUrl(userShare), 'https://v.douyin.com/Tldsyq0f8lY/');
  for (const value of ['https://v.douyin.com/Tldsyq0f8lY/', String.raw`https:\/\/v.douyin.com/Tldsyq0f8lY/`]) {
    assert.equal(P.shareLinkUrl('分享【' + value + '】。'), 'https://v.douyin.com/Tldsyq0f8lY/');
  }
  assert.equal(P.shareLinkUrl('https://xhslink.com/a?xsec_token=a%2Fb&xsec_source=share'), 'https://xhslink.com/a?xsec_token=a%2Fb&xsec_source=share');
  assert.equal(P.validateShareLink('没有视频链接'), '请输入完整的视频分享链接');
});

test('a bad audio URL falls back to the parsed video without treating its caption as a transcript', async () => {
  let payload;
  const h = harness('creation/index.js', {
    links: { parse: async () => ({ taskId: 'parse' }) },
    scripts: { extract: async body => { payload = plain(body); return { taskId: 'asr' }; } },
    poll: async id => ({ id, status: 'SUCCEEDED', result: id === 'parse'
      ? { text: shareTitle, audioUrl: 'invalid', videoUrl: 'https://media.example/a.mp4' }
      : { script: '实际转写结果' } })
  });
  h.page.data.link = userShare; await h.page.extract();
  assert.deepEqual(payload, { sourceUrl: 'https://media.example/a.mp4' });
  assert.equal(h.state.original, '实际转写结果');
});

for (const outcome of ['missing-media', 'empty-transcript', 'failed-asr', 'uncertain-asr']) {
  test('caption cannot count as successful extraction when ' + outcome, async () => {
    let extracted = 0;
    const h = harness('creation/index.js', {
      links: { parse: async () => ({ taskId: 'parse' }) },
      scripts: { extract: async () => { extracted++; return { taskId: 'asr' }; } },
      poll: async id => id === 'parse'
        ? { id, status: 'SUCCEEDED', result: { text: shareTitle, title: shareTitle, ...(outcome === 'missing-media' ? {} : { videoUrl: 'https://media.example/a.mp4' }) } }
        : { id, status: outcome === 'failed-asr' ? 'FAILED' : outcome === 'uncertain-asr' ? 'SUBMISSION_UNCERTAIN' : 'SUCCEEDED', result: { text: '   ' } }
    });
    h.state.original = '用户原有草稿'; h.state.script = '用户原有二创稿';
    h.page.data.link = userShare; await h.page.extract();
    assert.equal(h.state.original, '用户原有草稿'); assert.equal(h.state.script, '用户原有二创稿');
    assert.equal(h.navigations.length, 0); assert.equal(h.page.data.extracting, false);
    assert.ok(h.page.data.extractError); assert.equal(extracted, outcome === 'missing-media' ? 0 : 1);
  });
}

test('retries after an ASR request failure reuse both task keys and session changes discard late transcripts', async () => {
  const parsedKeys = [], extractedKeys = [], pending = deferred(); let fail = true;
  const h = harness('creation/index.js', {
    links: { parse: async (url, key) => { parsedKeys.push(key); return { taskId: 'parse' }; } },
    scripts: { extract: async (body, key) => { extractedKeys.push(key); if (fail) throw Error('网络异常'); return { taskId: 'asr' }; } },
    poll: async id => id === 'parse' ? { id, status: 'SUCCEEDED', result: { videoUrl: 'https://media.example/a.mp4', text: shareTitle } } : pending.promise
  });
  h.page.data.link = userShare; await h.page.extract(); fail = false;
  const run = h.page.extract(); await flush(); h.changeScope();
  pending.resolve({ id: 'asr', status: 'SUCCEEDED', result: { text: '旧身份转写文案' } }); await run;
  assert.equal(parsedKeys[0], parsedKeys[1]); assert.equal(extractedKeys[0], extractedKeys[1]);
  assert.equal(h.state.original, ''); assert.equal(h.navigations.length, 0);
});

test('missing company pool is explained as points setup and same-link retries reuse the same key', async () => {
  const keys = [];
  const h = harness('creation/index.js', { links: { parse: async (url, key) => { keys.push(key); throw { code: 1072000010, message: '公司未开通设计能力' }; } } });
  h.page.data.link = 'https://v.example/video'; await h.page.extract(); await h.page.extract();
  assert.equal(keys[0], keys[1]); assert.match(h.page.data.extractError, /积分账户/); assert.doesNotMatch(h.page.data.extractError, /设计能力/);
  assert.equal(h.navigations.length, 0); assert.equal(h.state.original, '');
});

test('leaving the page before submission returns cannot write a draft or start polling', async () => {
  const pending = deferred(); const h = harness('creation/index.js', { links: { parse: () => pending.promise } });
  h.page.data.link = 'https://v.example/video'; const run = h.page.extract(); h.page.onUnload(); pending.resolve({ taskId: '1' }); await run;
  assert.equal(h.calls.length, 0); assert.equal(h.navigations.length, 0); assert.equal(h.state.original, '');
});

test('uncertain link task does not start transcription or invent source text', async () => {
  const h = harness('creation/index.js', { links: { parse: async () => ({ taskId: '1' }) }, poll: async () => ({ status: 'SUBMISSION_UNCERTAIN' }) });
  h.page.data.link = 'https://v.example/video'; await h.page.extract(); assert.equal(h.state.original, ''); assert.match(h.page.data.extractError, /待核实/);
});

test('ordinary sales can use company profiles but cannot create or modify them', async () => {
  let writes = 0; const h = harness('studio/characters/index.js', { personas: { page: async () => [{ id: '21', name: '顾问', enabled: true }], create: async () => { writes++; } } }, 'sales');
  h.page.onLoad(); h.page.onShow(); await flush(); h.page.edit(event({})); await h.page.save();
  assert.equal(h.page.data.canManage, false); assert.equal(writes, 0); h.page.use(event({ id: '21' })); assert.match(h.navigations[0], /persona=21/);
});

for (const kind of ['AVATAR', 'VOICE']) {
  test('empty library starts ' + kind + ' setup and continues with the saved server persona', async () => {
    const people = [], quotes = [], listed = [];
    const h = harness('studio/characters/index.js', {
      personas: { page: async () => people, create: async body => { const person = { ...plain(body), id: '90071992547409930' }; people.push(person); return person; } },
      clones: { list: async id => { listed.push(id); return []; }, quote: async value => { quotes.push(value); return { available: true, credits: 5 }; } }
    }, 'boss');
    h.page.onLoad(); h.page.onShow(); await flush();
    h.page.openCloneEntry(event({ kind }));
    assert.equal(h.page.data.editing, true); assert.equal(h.page.data.cloneEntryKind, kind);
    assert.equal(h.page.data.digitalPersona, null);
    h.page.input(event({ key: 'name' }, '新人物')); await h.page.save(); await flush();
    assert.equal(h.page.data.digitalPersona.id, '90071992547409930'); assert.equal(h.page.data.cloneKind, kind);
    assert.equal(h.page.data.cloneTitle, '新人物 · '+(kind==='AVATAR'?'视频分身':'克隆声音')+' 1'); assert.equal(h.page.data.editing, false);
    assert.deepEqual(quotes, [kind]); assert.deepEqual(listed, ['90071992547409930']);
  });
}

test('both clone options let sales open their personal IP form in an empty library', async () => {
  let writes = 0;
  const h = harness('studio/characters/index.js', { personas: { page: async () => [], create: async () => { writes++; } } }, 'sales');
  h.page.onLoad(); h.page.onShow(); await flush();
  for (const kind of ['AVATAR', 'VOICE']) {
    h.page.openCloneEntry(event({ kind })); await h.page.save();
    assert.equal(h.page.data.cloneEntryKind, kind); assert.equal(h.page.data.editing, true); assert.equal(h.page.data.canCreate, true);
  }
  assert.equal(writes, 0); assert.equal(h.page.data.digitalPersona, null);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../miniprogram/pages/studio/characters/index.wxml'), 'utf8');
  const entries = wxml.slice(wxml.indexOf('<view class="character-tools">'), wxml.indexOf('<view class="search-row">'));
  assert.match(entries, /data-kind="AVATAR" bindtap="openCloneEntry"/);
  assert.match(entries, /data-kind="VOICE" bindtap="openCloneEntry"/);
  assert.doesNotMatch(entries, /wx:if="\{\{canManage/);
  assert.match(entries, /口播分身/); assert.match(entries, /语音克隆/);
});

test('clone entry selects an enabled person and can switch exact persona IDs', async () => {
  const ids = [], quotes = [];
  const h = harness('studio/characters/index.js', {
    personas: { page: async () => [{ id: '1', name: '停用人物', enabled: false }, { id: '2', name: '同名人物' }, { id: '3', name: '同名人物' }] },
    clones: { list: async id => { ids.push(id); return []; }, quote: async kind => { quotes.push(kind); return { available: true }; } }
  });
  h.page.onLoad(); h.page.onShow(); await flush();
  h.page.openCloneEntry(event({ kind: 'VOICE' })); await flush();
  assert.equal(h.page.data.digitalPersona.id, '2'); assert.equal(h.page.data.cloneKind, 'VOICE');
  h.page.chooseDigitalPerson(event({}, 1)); await flush();
  assert.equal(h.page.data.digitalPersona.id, '3'); assert.equal(h.page.data.digitalPersonIndex, 1);
  assert.deepEqual(ids, ['2', '3']); assert.deepEqual(quotes, ['VOICE', 'VOICE']);
  h.page.data.cloneRetry = true; h.page.chooseDigitalPerson(event({}, 0)); h.page.openCloneEntry(event({ kind: 'AVATAR' }));
  assert.equal(h.page.data.digitalPersona.id, '3'); assert.equal(h.page.data.cloneKind, 'VOICE');
});

test('canceling new-person clone setup does not reopen clone resources after an unrelated save', async () => {
  const people = [];
  const h = harness('studio/characters/index.js', {
    personas: { page: async () => people, create: async body => { const person = { ...plain(body), id: '88' }; people.push(person); return person; } }
  }, 'boss');
  h.page.onLoad(); h.page.onShow(); await flush(); h.page.openCloneEntry(event({ kind: 'VOICE' })); h.page.close();
  h.page.edit(event({})); h.page.input(event({ key: 'name' }, '仅保存资料')); await h.page.save();
  assert.equal(h.page.data.view, 'detail'); assert.equal(h.page.data.digitalPersona.id,'88');
  assert.equal(h.page.data.cloneQuote,null); assert.equal(h.page.__pendingCloneKind,null);
});

test('persona selection uses exact IDs even when two people have the same display name', async () => {
  const h = harness('studio/index.js', { personas: { page: async () => [{ id: '1', name: '顾问' }, { id: '2', name: '顾问' }] } });
  h.page.data.selectedPersonaId = '2'; assert.equal(await h.page.fetchPersonaId('顾问'), '2');
  h.page.data.selectedPersonaId = '3'; await assert.rejects(h.page.fetchPersonaId('顾问'), /已停用或不存在/);
});

test('digital editor selects only ready clones from the selected persona and preserves local IDs', async () => {
  const h = harness('studio/index.js', { personas: { page: async () => [{ id: '21', name: '顾问' }] }, clones: { list: async id => {
    assert.equal(id, '21'); return [{ id: '31', kind: 'AVATAR', title: '真实分身', ready: true }, { id: '32', kind: 'VOICE', title: '真实声音', ready: true }, { id: '33', kind: 'VOICE', title: '制作中', ready: false }];
  } } });
  await h.page.loadDigitalProfiles('21'); assert.equal(h.page.data.avatarCloneId, '31'); assert.equal(h.page.data.voiceCloneId, '32'); assert.equal(h.page.data.voiceOptions.length, 1);
  assert.deepEqual(plain(h.state.digitalSelection), { personaId: '21', avatarId: '31', voiceId: '32' });
});

test('network failure in clone creation keeps exact persisted request for safe retry', async () => {
  const submitted = []; const h = harness('studio/characters/index.js', { personas: { page: async () => [] }, clones: {
    create: async (person, body, key) => { submitted.push([person, plain(body), key]); throw { code: -1, message: '网络异常' }; }
  } });
  h.page.onLoad(); h.page.data.canManage = true; h.page.setData({ digitalPersona: { id: '21' }, cloneTitle: '我的分身', cloneUploadId: '101', cloneConsented: true, cloneQuote: { available: true, credits: 5, consentVersion: 'digital-clone-v1' } });
  await h.page.createClone(); await h.page.createClone(); assert.deepEqual(submitted[0], submitted[1]); assert.equal(h.page.data.cloneRetry, true);
  assert.equal(h.storage.size, 1); assert.equal(h.page.data.cloneBusy, false);
});

test('a disabled persona cannot enter new remix or digital generation', () => {
  const h = harness('studio/characters/index.js'); h.page.data.rows = [{ id: '21', enabled: false }]; h.page.use(event({ id: '21' }));
  h.page.data.digitalPersona = { id: '21', enabled: false }; h.page.data.cloneRows = [{ ready: true, kind: 'AVATAR' }, { ready: true, kind: 'VOICE' }]; h.page.useDigital(); assert.equal(h.navigations.length, 0);
});

test('IP positioning and supplemental fields save together and old fields survive editing', async () => {
  const people=[{id:'21',name:'张工',enabled:true,cta:'私信咨询',track:'乡墅设计',sampleScript:'旧口播'}];let updated;
  const h=harness('studio/characters/index.js',{personas:{page:async()=>people,update:async(id,body)=>{updated=plain(body);Object.assign(people[0],updated);return people[0];}}});
  h.page.onLoad();h.page.onShow();await flush();h.page.edit(event({id:'21'}));
  for(const [key,value] of Object.entries({expressionStyle:'专业、通俗',audienceNeeds:'预算和采光',factualBackground:'真实经历',sampleScript:'',forbiddenClaims:'保证零增项'}))h.page.input(event({key},value));
  h.page.supplement();assert.equal(h.page.data.view,'supplement');await h.page.save();
  assert.equal(updated.sampleScript,'');assert.equal(updated.cta,'私信咨询');assert.equal(updated.track,'乡墅设计');assert.equal(updated.audienceNeeds,'预算和采光');
  assert.equal(h.page.data.view,'detail');h.page.use(event({id:'21'}));assert.match(h.navigations[0],/view=remix&persona=21/);
});

test('returning from native media selection does not erase the IP form', async () => {
  const h=harness('studio/characters/index.js',{personas:{page:async()=>[]}});h.page.onLoad();h.page.onShow();await flush();
  h.page.edit(event({}));h.page.input(event({key:'name'},'尚未保存'));h.page.input(event({key:'factualBackground'},'已核实资料'));
  h.page.onHide();h.page.onShow();assert.equal(h.page.data.form.name,'尚未保存');assert.equal(h.page.data.form.factualBackground,'已核实资料');assert.equal(h.page.data.view,'edit');
  h.changeScope();h.page.onShow();await flush();assert.equal(h.page.data.view,'home');assert.deepEqual(plain(h.page.data.form),{});
});

async function readyDigital(api) {
  const h=harness('studio/index.js',{
    personas:{page:async()=>[{id:'21',name:'张工'}]},
    clones:{list:async()=>[{id:'31',kind:'AVATAR',title:'张工分身',ready:true},{id:'32',kind:'VOICE',title:'张工声音',ready:true}]},
    ...api
  });
  h.page.data.view='digital';await h.page.loadDigitalProfiles('21');return h;
}

test('digital network retry freezes complete payload and key across page recreation', async () => {
  const requests=[];const api={tasks:{create:async(body,key)=>{requests.push([plain(body),key]);throw {code:-1,message:'响应丢失'};}}};
  const h=await readyDigital(api);Object.assign(h.page.data,{script:'完整口播稿',title:'张工口播',digitalPersonaId:'21',avatarCloneId:'31',voiceCloneId:'32'});
  await h.page.generateDigitalOnline();assert.equal(h.page.data.digitalRetry,true);assert.equal(h.page.data.digitalSubmitting,false);
  h.page.data.script='不能替换待确认稿件';h.page.data.avatarCloneId='99';await h.page.generateDigitalOnline();assert.deepEqual(requests[0],requests[1]);
  const reload=harness('studio/index.js',api);for(const [key,value] of h.storage)reload.storage.set(key,value);
  reload.page.restoreDigitalAttempt();await reload.page.generateDigitalOnline();assert.deepEqual(requests[0],requests[2]);assert.equal(reload.page.data.script,'完整口播稿');
});

test('digital submission accepts the real task DTO id and saves the same completed task', async () => {
  const id='90071992547409931',requests=[],saves=[];
  const h=await readyDigital({tasks:taskApi(async(url,body,headers)=>{
    if(url==='/franchise/content/tasks'){
      requests.push([plain(body),headers['Idempotency-Key']]);
      return {id,kind:'digital',status:'QUEUED',generationStatus:'QUEUED',resultState:'PENDING',result:{}};
    }
    assert.equal(url,'/franchise/content/tasks/'+id+'/save-work');saves.push([id,body.title]);return {workId:'90071992547409932'};
  })});
  Object.assign(h.page.data,{script:'同一人物口播',title:'张工',digitalPersonaId:'21',avatarCloneId:'31',voiceCloneId:'32'});
  await h.page.generateDigitalOnline();assert.equal(requests[0][0].avatarCloneId,'31');assert.equal(requests[0][0].voiceCloneId,'32');assert.deepEqual(saves,[['90071992547409931','数字人口播｜张工']]);
  assert.deepEqual(h.calls,[['poll',id]]);
  assert.equal(h.page.data.digitalRetry,false);assert.equal(h.storage.size,0);assert.match(h.navigations[0],/view=tasks/);
});

test('digital retry after a lost response replays the frozen request and resumes its original task id', async () => {
  const requests=[],saves=[],accepted=new Map();let loseResponse=true;
  const tasks=taskApi(async(url,body,headers)=>{
    if(url==='/franchise/content/tasks'){
      const key=headers['Idempotency-Key'];requests.push([plain(body),key]);
      if(!accepted.has(key))accepted.set(key,{body:plain(body),id:'90071992547409933'});
      assert.deepEqual(plain(body),accepted.get(key).body);
      if(loseResponse){loseResponse=false;throw {code:-1,message:'response lost after acceptance'};}
      return {id:accepted.get(key).id,kind:'digital',status:'RUNNING'};
    }
    assert.equal(url,'/franchise/content/tasks/90071992547409933/save-work');saves.push(plain(body));return {workId:'90071992547409934'};
  });
  const first=await readyDigital({tasks});Object.assign(first.page.data,{script:'原口播稿',title:'原标题',digitalPersonaId:'21',avatarCloneId:'31',voiceCloneId:'32'});
  await first.page.generateDigitalOnline();assert.equal(first.page.data.digitalRetry,true);assert.equal(first.navigations.length,0);
  const retry=harness('studio/index.js',{tasks},'sales-lead',{storage:first.storage});
  retry.page.restoreDigitalAttempt();retry.page.data.script='重试不应替换原稿';await retry.page.generateDigitalOnline();
  assert.deepEqual(requests[0],requests[1]);assert.equal(accepted.size,1);
  assert.deepEqual(retry.calls,[['poll','90071992547409933']]);assert.deepEqual(saves,[{title:'原标题'}]);
  assert.equal(retry.page.data.digitalRetry,false);assert.equal(retry.storage.size,0);assert.match(retry.navigations[0],/view=tasks/);
});

test('task create normalizes the legacy taskId response without replacing the canonical id', async () => {
  const responses=[{taskId:'90071992547409935',status:'QUEUED'},{id:'90071992547409936',taskId:'old-id',status:'RUNNING'}];
  const tasks=taskApi(async()=>responses.shift());
  assert.equal((await tasks.create({kind:'digital'},'legacy-key')).id,'90071992547409935');
  assert.equal((await tasks.create({kind:'digital'},'canonical-key')).id,'90071992547409936');
});

test('a digital create response without any task id keeps the pending request for recovery', async () => {
  const h=await readyDigital({tasks:taskApi(async()=>({kind:'digital',status:'QUEUED'}))});
  Object.assign(h.page.data,{script:'原口播稿',avatarCloneId:'31',voiceCloneId:'32'});
  await h.page.generateDigitalOnline();
  assert.equal(h.page.data.digitalRetry,true);assert.equal(h.storage.size,1);assert.equal(h.navigations.length,0);assert.equal(h.calls.length,0);
});

test('late digital response cannot navigate or save after a session switch', async () => {
  const pending=deferred(),saves=[];const h=await readyDigital({tasks:{create:()=>pending.promise,saveWork:async()=>saves.push('saved')}});
  Object.assign(h.page.data,{script:'旧岗位稿件',title:'旧人物',avatarCloneId:'31',voiceCloneId:'32'});
  const run=h.page.generateDigitalOnline();h.changeScope();pending.resolve({id:'old-task',kind:'digital',status:'QUEUED'});await run;
  assert.equal(h.navigations.length,0);assert.deepEqual(saves,[]);
});

test('confirmed clone price rejection refreshes the quote and retries only at the new price',async()=>{
  const requests=[];let first=true;const h=harness('studio/characters/index.js',{clones:{quote:async()=>({available:true,credits:8,consentVersion:'digital-clone-v1'}),list:async()=>[],create:async(person,body,key)=>{requests.push([plain(body),key]);if(first){first=false;throw {code:1114000001,message:'制作价格已变化'};}return {id:'31'};}}});
  h.page.onLoad();Object.assign(h.page.data,{canManage:true,digitalPersona:{id:'21',enabled:true},cloneTitle:'张工',cloneUploadId:'101',cloneConsented:true,cloneQuote:{available:true,credits:5,consentVersion:'digital-clone-v1'}});
  await h.page.createClone();await flush();assert.equal(h.page.data.cloneRetry,false);assert.equal(h.page.data.cloneQuote.credits,8);await h.page.createClone();
  assert.equal(requests[0][0].confirmedCredits,5);assert.equal(requests[1][0].confirmedCredits,8);assert.notEqual(requests[0][1],requests[1][1]);assert.equal(h.page.data.view,'resources');
});

test('restored digital submission displays its original resource IDs rather than the first ready resources',async()=>{
  const h=harness('studio/index.js',{personas:{page:async()=>[{id:'21',name:'张工'}]},clones:{list:async()=>[{id:'99',kind:'AVATAR',title:'其他分身',ready:true},{id:'31',kind:'AVATAR',title:'原提交分身',ready:true},{id:'98',kind:'VOICE',title:'其他声音',ready:true},{id:'32',kind:'VOICE',title:'原提交声音',ready:true}]}});
  h.storage.set('zs-digital-attempt:post-1',{key:'original-request',personaId:'21',payload:{kind:'digital',avatarCloneId:'31',voiceCloneId:'32',text:'原提交文案',title:'原提交标题'}});
  h.page.restoreDigitalAttempt();await h.page.loadDigitalProfiles('21');assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.voiceCloneId,'32');assert.equal(h.page.data.actor,'原提交分身');assert.equal(h.page.data.digitalRetry,true);
});

test('resource refresh retries unfinished settlement before enabling digital use',async()=>{
  const refreshed=[],ready=[{id:'31',kind:'AVATAR',status:'READY',ready:true},{id:'32',kind:'VOICE',status:'READY',ready:true}];
  const h=harness('studio/characters/index.js',{clones:{refresh:async id=>refreshed.push(id),list:async()=>ready}});h.page.onLoad();
  Object.assign(h.page.data,{view:'resources',digitalPersona:{id:'21',enabled:true},cloneRows:[{...ready[0],ready:false},ready[1]]});await h.page.refreshAll();assert.deepEqual(refreshed,['31']);assert.equal(h.page.data.canUseDigital,true);
});

test('digital resources never display marked demo people, avatars or voices',async()=>{
  const h=harness('studio/index.js',{
    personas:{page:async()=>[{id:'demo',name:'模拟人物',demo:true},{id:'21',name:'真实人物'}]},
    clones:{list:async()=>[{id:'demo-avatar',kind:'AVATAR',title:'模拟分身',ready:true,mock:true},
      {id:'31',kind:'AVATAR',title:'真实分身',ready:true},{id:'32',kind:'VOICE',title:'真实声音',ready:true}]}
  });
  await h.page.loadDigitalProfiles();
  assert.deepEqual(plain(h.page.data.digitalPersonas),[{id:'21',name:'真实人物'}]);
  assert.equal(h.page.data.actor,'真实分身');assert.equal(h.page.data.voice,'真实声音');
  assert.equal(h.page.data.avatarOptions.length,1);assert.equal(h.page.data.voiceOptions.length,1);
  assert.equal(h.page.mapApiTask({id:'mock-task',status:'SUCCEEDED',mock:true}),null);
});

test('empty and failed digital resource loads clear old selections without supplying demo defaults',async()=>{
  let failed=false;
  const h=harness('studio/index.js',{personas:{page:async()=>{if(failed)throw Error('接口异常');return [];}}});
  Object.assign(h.page.data,{actor:'旧分身',voice:'旧声音',avatarCloneId:'31',voiceCloneId:'32',digitalPersonas:[{id:'old',name:'旧人物'}]});
  await h.page.loadDigitalProfiles();assert.equal(h.page.data.actor,'');assert.equal(h.page.data.voice,'');
  assert.equal(h.page.data.digitalPersonas.length,0);assert.match(h.page.data.digitalResourceError,/暂无可选人物/);
  failed=true;await h.page.loadDigitalProfiles();assert.equal(h.page.data.digitalPersonas.length,0);
  assert.equal(h.page.data.avatarCloneId,'');assert.match(h.page.data.digitalResourceError,/接口异常/);
});

test('old name-only demo submissions cannot restore a digital script or become a retryable paid request',()=>{
  const h=harness('studio/index.js');
  h.storage.set('zs-digital-attempt:post-1',{key:'old-demo',payload:{actor:'李顾问',voice:'李顾问原声',text:'演示口播'}});
  h.page.restoreDigitalAttempt();assert.equal(h.page.__digitalAttempt,null);
  assert.equal(h.page.data.digitalRetry,false);assert.equal(h.page.data.script,'');
});

test('fresh digital generation prefers configured defaults over the last manual selection',async()=>{
  const h=harness('studio/index.js',{personas:{page:async()=>[{id:'21',name:'张工'}]},clones:{list:async()=>[
    {id:'31',kind:'AVATAR',title:'旧分身',ready:true},{id:'33',kind:'AVATAR',title:'默认分身',ready:true,isDefault:true},
    {id:'32',kind:'VOICE',title:'旧声音',ready:true},{id:'34',kind:'VOICE',title:'默认声音',ready:true,isDefault:true}
  ]}});
  h.state.digitalSelection={personaId:'21',avatarId:'31',voiceId:'32'};
  await h.page.loadDigitalProfiles('21');
  assert.equal(h.page.data.avatarCloneId,'33');assert.equal(h.page.data.voiceCloneId,'34');
  assert.equal(h.page.data.actorIsDefault,true);assert.equal(h.page.data.voiceIsDefault,true);
  h.page.selectActor(event({id:'31'}));assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.actorIsDefault,false);
});

test('changing defaults cannot replace resources frozen by a pending digital submission',async()=>{
  const h=harness('studio/index.js',{clones:{list:async()=>[
    {id:'31',kind:'AVATAR',ready:true},{id:'33',kind:'AVATAR',ready:true,isDefault:true},
    {id:'32',kind:'VOICE',ready:true},{id:'34',kind:'VOICE',ready:true,isDefault:true}
  ]}});
  h.page.__digitalAttempt={payload:{avatarCloneId:'31',voiceCloneId:'32'}};h.page.data.digitalPersonaId='21';
  await h.page.loadDigitalClones('21',{avatarId:'31',voiceId:'32'});
  assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.voiceCloneId,'32');
});

test('setting a default reloads authoritative rows and preserves the other resource kind',async()=>{
  const rows=[{id:'31',kind:'AVATAR',ready:true,isDefault:true},{id:'33',kind:'AVATAR',ready:true,isDefault:false},{id:'32',kind:'VOICE',ready:true,isDefault:true}],requests=[];
  const h=harness('studio/characters/index.js',{clones:{setDefault:async id=>{requests.push(id);rows.filter(r=>r.kind==='AVATAR').forEach(r=>{r.isDefault=r.id===id;});},list:async()=>rows}});
  h.page.onLoad();Object.assign(h.page.data,{canManage:true,digitalPersona:{id:'21',enabled:true},cloneRows:plain(rows)});
  await h.page.setDefaultClone(event({id:'33'}));
  assert.deepEqual(requests,['33']);assert.equal(h.page.data.cloneRows.find(r=>r.id==='33').isDefault,true);
  assert.equal(h.page.data.cloneRows.find(r=>r.id==='31').isDefault,false);assert.equal(h.page.data.cloneRows.find(r=>r.id==='32').isDefault,true);
  h.page.data.canManage=false;await h.page.setDefaultClone(event({id:'31'}));assert.equal(requests.length,1);
});


test('task center hides explicit test records but retains real text and video tasks without fake progress',async()=>{
 const rows=[{id:'1',kind:'digital',status:'SUCCEEDED',provider:'fake'},{id:'2',kind:'digital',status:'RUNNING',mock:true},{id:'task-complete',kind:'digital',status:'SUCCEEDED'},{id:'3',kind:'asr',status:'SUCCEEDED',provider:'dashscope',result:{text:'真实转写 mock 示例'}},{id:'4',kind:'ai_video',status:'RUNNING',provider:'metaso'}];
 const h=harness('studio/index.js',{tasks:{page:async()=>rows}});h.page.data.view='tasks';await h.page.syncApiTasks();
 assert.deepEqual(plain(h.page.data.tasks.map(t=>t.id)),['3','4']);
 assert.equal(h.page.data.tasks[0].outputType,'text');assert.equal(h.page.data.tasks[1].stageLabel,'正在处理');
 assert.equal(h.page.data.tasks[1].progress,undefined);assert.match(h.page.data.tasks[1].image,/video-filled/);
});
test('task filter switches and account changes reject late task list responses',async()=>{
 const first=deferred(),second=deferred();let n=0;const h=harness('studio/index.js',{tasks:{page:()=>++n===1?first.promise:second.promise}});h.page.data.view='tasks';
 const a=h.page.syncApiTasks();h.page.data.taskFilter=2;const b=h.page.syncApiTasks();
 second.resolve([{id:'2',kind:'digital',status:'SUCCEEDED'}]);await b;
 first.resolve([{id:'1',kind:'digital',status:'RUNNING'}]);await a;
 assert.deepEqual(plain(h.page.data.tasks.map(t=>t.id)),['2']);
});

test('ASR success plus exhausted save retries restores the same task without new transcription or charge',async()=>{
 let creates=0,charges=0,retries=0,saveState='FAILED';const keys=[];
 const parsed={id:'parse',kind:'link_parse',status:'SUCCEEDED',result:{videoUrl:'https://media.example/audio'}};
 const task=()=>({id:'asr',kind:'asr',generationStatus:'SUCCEEDED',status:saveState==='READY'?'SUCCEEDED':'FAILED',resultState:saveState,saveRetryable:saveState==='FAILED',result:{text:'只转写一次的文案'}});
 const h=harness('creation/index.js',{
  links:{parse:async()=>({taskId:'parse'})},scripts:{extract:async(body,key)=>{keys.push(key);creates++;charges++;return{taskId:'asr'};}},
  tasks:{get:async id=>{assert.equal(id,'asr');return task();},retrySave:async id=>{assert.equal(id,'asr');retries++;if(retries===1)throw Error('save endpoint temporarily unavailable');saveState='READY';return task();}},
  poll:async id=>id==='parse'?parsed:task()
 });
 h.page.data.link='https://v.example/save-failure';await h.page.extract();assert.match(h.page.data.extractError,/保存失败/);
 const key=h.page.__extractAttempt.asrKey;
 await h.page.extract();assert.equal(h.navigations.length,0);
 await h.page.extract();assert.equal(h.page.__extractAttempt.asrKey,key);assert.equal(creates,1);assert.equal(charges,1);assert.equal(retries,2);
 assert.equal(h.state.original,'只转写一次的文案');assert.equal(h.state.extraction.taskId,'asr');assert.equal(h.navigations.length,1);
});
test('task center retries local save on the original task instead of offering new generation',async()=>{
 const calls=[],task={id:'81',kind:'asr',status:'FAILED',generationStatus:'SUCCEEDED',resultState:'FAILED',saveRetryable:true,result:{text:'已完成转写'}};
 const h=harness('studio/index.js',{tasks:{page:async()=>[task],retrySave:async id=>calls.push(id)}});
 h.page.data.view='tasks';await h.page.syncApiTasks();assert.equal(h.page.data.tasks[0].stageLabel,'生成完成，保存失败');
 await h.page.taskAction(event({id:'81'}));assert.deepEqual(calls,['81']);
});

test('restarting the mini-program restores save-failed ASR by account and organization without parsing or billing again',async()=>{
 const storage=new Map();let creates=0,charges=0,parses=0,saved=false,retries=0;
 const task=()=>({id:'restart-asr',kind:'asr',generationStatus:'SUCCEEDED',status:saved?'SUCCEEDED':'FAILED',resultState:saved?'READY':'FAILED',result:{text:'重启后恢复的转写文案'}});
 const api={links:{parse:async()=>{parses++;return{taskId:'parse'};}},scripts:{extract:async()=>{creates++;charges++;return{taskId:'restart-asr'};}},tasks:{get:async id=>{assert.equal(id,'restart-asr');return task();},retrySave:async id=>{assert.equal(id,'restart-asr');retries++;saved=true;}},poll:async id=>id==='parse'?{status:'SUCCEEDED',result:{audioUrl:'https://media.example/no-hash'}}:task()};
 const first=harness('creation/index.js',api,'sales',{storage});first.page.data.link='https://v.example/restart';await first.page.extract();
 const key=first.page.__extractAttempt.asrKey;assert.match(first.page.data.extractError,/保存失败/);
 const reopened=harness('creation/index.js',api,'sales',{storage});reopened.page.data.link='复制链接 https://v.example/restart';await reopened.page.extract();
 assert.equal(reopened.page.__extractAttempt.asrKey,key);assert.equal(reopened.state.original,'重启后恢复的转写文案');
 assert.deepEqual({creates,charges,parses,retries},{creates:1,charges:1,parses:1,retries:1});
 for(const identity of [{orgId:'34'},{userId:'78'}]){
  const other=harness('creation/index.js',api,'sales',{storage,...identity});other.page.data.link='https://v.example/restart';await other.page.extract();
  assert.notEqual(other.page.__extractAttempt.asrKey,key);
 }
 assert.equal(creates,3);assert.equal(retries,1,'other account/org must not retry this owner task');
});
test('restart after lost ASR creation response replays persisted request key, not a new paid operation',async()=>{
 const storage=new Map(),requests=new Set();let responseLost=true,charges=0;
 const api={links:{parse:async()=>({taskId:'parse'})},scripts:{extract:async(body,key)=>{if(!requests.has(key)){requests.add(key);charges++;}if(responseLost)throw Error('response lost after acceptance');return{taskId:'asr'};}},poll:async id=>({id,status:'SUCCEEDED',result:id==='parse'?{videoUrl:'https://media.example/a'}:{text:'已受理原任务'}})};
 const first=harness('creation/index.js',api,'sales',{storage});first.page.data.link='https://v.example/lost';await first.page.extract();responseLost=false;
 const second=harness('creation/index.js',api,'sales',{storage});second.page.data.link='https://v.example/lost';await second.page.extract();
 assert.equal(requests.size,1);assert.equal(charges,1);assert.equal(second.state.original,'已受理原任务');
});
test('unreadable or unwritable recovery storage blocks paid extraction instead of silently replacing keys',async()=>{
 for(const method of ['getStorageSync','setStorageSync']){
  let creates=0;const h=harness('creation/index.js',{links:{parse:async()=>{creates++;}}});h.wx[method]=()=>{throw Error('storage unavailable');};
  h.page.data.link='https://v.example/storage';await h.page.extract();assert.equal(creates,0);assert.match(h.page.data.extractError,/任务记录/);assert.equal(h.page.data.extracting,false);
 }
});

const digitalPeople=[{id:'21',name:'张工'},{id:'22',name:'李工'}];
const digitalClones=[
 {id:'31',kind:'AVATAR',title:'手选分身',ready:true},{id:'33',kind:'AVATAR',title:'默认分身',ready:true,isDefault:true},
 {id:'32',kind:'VOICE',title:'手选声音',ready:true},{id:'34',kind:'VOICE',title:'默认声音',ready:true,isDefault:true}
];
function digitalFixture(){
 let people=()=>Promise.resolve(digitalPeople),clones=()=>Promise.resolve(digitalClones);
 const reads={people:0,clones:[]};
 const h=harness('studio/index.js',{personas:{page:()=>{reads.people++;return people();}},clones:{list:id=>{reads.clones.push(id);return clones(id);}}});
 h.page.data.view='digital';
 return {...h,reads,setPeople:value=>{people=value;},setClones:value=>{clones=value;}};
}
function digitalSnapshot(page){return plain(Object.fromEntries(['digitalPersonas','digitalPersonaId','digitalPersonaIndex','actor','voice','avatarCloneId','voiceCloneId','avatarOptions','voiceOptions','actorIsDefault','voiceIsDefault'].map(key=>[key,page.data[key]])));}
function chooseManualDigital(page){page.selectActor(event({id:'31'}));page.selectVoice(event({id:'32'}));}

test('F06 concurrent profile reads share both the persona request and its clone request',async()=>{
 const h=digitalFixture(),people=deferred(),clones=deferred();
 h.setPeople(()=>people.promise);h.setClones(()=>clones.promise);
 const first=h.page.loadDigitalProfiles('21'),second=h.page.loadDigitalProfiles('21');
 assert.equal(h.reads.people,1);assert.equal(first,second);
 people.resolve(digitalPeople);await flush();
 const third=h.page.loadDigitalProfiles('21');assert.equal(h.reads.people,1);assert.equal(h.reads.clones.length,1);
 clones.resolve(digitalClones);await Promise.all([first,second,third]);
 assert.equal(h.page.data.digitalLoading,false);assert.equal(h.page.data.avatarCloneId,'33');
});

test('F06 returning to the editor retains its displayed resources and manual choices while updating',async()=>{
 const h=digitalFixture();h.page.onLoad({view:'digital',persona:'21'});h.page.onShow();await flush();
 chooseManualDigital(h.page);const before=digitalSnapshot(h.page);
 h.page.onHide();const people=deferred(),clones=deferred();h.setPeople(()=>people.promise);h.setClones(()=>clones.promise);
 h.page.onShow();h.page.onShow();
 assert.equal(h.reads.people,2);assert.deepEqual(digitalSnapshot(h.page),before);
 people.resolve(digitalPeople);await flush();assert.deepEqual(digitalSnapshot(h.page),before);
 clones.resolve(digitalClones.map(row=>({...row,title:row.title+'更新'})));await flush();
 assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.voiceCloneId,'32');
 assert.equal(h.page.data.actor,'手选分身更新');assert.equal(h.page.data.voice,'手选声音更新');assert.equal(h.page.data.digitalLoading,false);
});

test('F06 ordinary profile and clone failures retain the authorized selection and do not erase generation errors',async()=>{
 for(const stage of ['people','clones']){
  const h=digitalFixture();await h.page.loadDigitalProfiles('21');chooseManualDigital(h.page);
  const before=digitalSnapshot(h.page);h.page.data.operationError='原提交等待确认';
  h[stage==='people'?'setPeople':'setClones'](()=>Promise.reject(Error('资源接口超时')));
  await h.page.loadDigitalProfiles('21');
  assert.deepEqual(digitalSnapshot(h.page),before);assert.equal(h.page.data.digitalLoading,false);
  assert.match(h.page.data.digitalResourceError,/资源接口超时/);assert.equal(h.page.data.operationError,'原提交等待确认');
 }
});

test('F06 only an authoritatively missing or unavailable selection is replaced',async()=>{
 const h=digitalFixture();await h.page.loadDigitalProfiles('21');chooseManualDigital(h.page);
 h.setClones(()=>Promise.resolve(digitalClones.map(row=>({...row,isDefault:row.id==='33'||row.id==='34'}))));
 await h.page.loadDigitalProfiles('21');assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.voiceCloneId,'32');
 h.setClones(()=>Promise.resolve(digitalClones.filter(row=>row.id!=='31')));
 await h.page.loadDigitalProfiles('21');assert.equal(h.page.data.avatarCloneId,'33');assert.equal(h.page.data.voiceCloneId,'32');
 h.setClones(()=>Promise.resolve(digitalClones.map(row=>({...row,ready:row.kind==='AVATAR'}))));
 await h.page.loadDigitalProfiles('21');assert.equal(h.page.data.avatarCloneId,'33');assert.equal(h.page.data.voiceCloneId,'');
 assert.match(h.page.data.digitalResourceError,/声音/);
});

test('F06 a choice made while clones refresh wins over the earlier saved IDs',async()=>{
 const h=digitalFixture();await h.page.loadDigitalProfiles('21');
 const pending=deferred();h.setClones(()=>pending.promise);const refresh=h.page.loadDigitalClones('21',{avatarId:'33',voiceId:'34'});
 chooseManualDigital(h.page);pending.resolve(digitalClones);await refresh;
 assert.equal(h.page.data.avatarCloneId,'31');assert.equal(h.page.data.voiceCloneId,'32');
});

test('F06 changing persona clears incompatible resources and rejects its older child response and error',async()=>{
 for(const failOld of [false,true]){
  const h=digitalFixture();await h.page.loadDigitalProfiles('21');
  const old=deferred(),next=deferred();h.setClones(id=>id==='21'?old.promise:next.promise);
  const first=h.page.loadDigitalClones('21');h.page.changeDigitalPersona(event({},'1'));
  assert.equal(h.page.data.digitalPersonaId,'22');assert.equal(h.page.data.avatarCloneId,'');
  const resources=[{id:'41',kind:'AVATAR',ready:true,title:'李工分身'},{id:'42',kind:'VOICE',ready:true,title:'李工声音'}];
  next.resolve(resources);await flush();const snapshot=digitalSnapshot(h.page);
  if(failOld)old.reject(Error('旧人物失败'));else old.resolve(digitalClones);await first;
  assert.deepEqual(digitalSnapshot(h.page),snapshot);assert.equal(h.page.data.digitalResourceError,'');assert.equal(h.page.data.digitalLoading,false);
 }
});

test('F06 an older profile refresh must not restore the persona preferred before a newer selection',async()=>{
 const h=digitalFixture();await h.page.loadDigitalProfiles('21');
 const people=deferred(),child=deferred();h.setPeople(()=>people.promise);h.setClones(id=>id==='22'?child.promise:Promise.resolve(digitalClones));
 const refresh=h.page.loadDigitalProfiles('21');h.page.changeDigitalPersona(event({},'1'));
 people.resolve(digitalPeople);await flush();
 assert.equal(h.page.data.digitalPersonaId,'22');assert.deepEqual(h.reads.clones,['21','22']);
 child.resolve([{id:'41',kind:'AVATAR',ready:true,title:'李工分身'},{id:'42',kind:'VOICE',ready:true,title:'李工声音'}]);await refresh;
 assert.equal(h.page.data.avatarCloneId,'41');assert.equal(h.page.data.digitalLoading,false);
});

test('F06 a superseded persona child cannot keep the newer resources locked while it hangs',async()=>{
 const h=digitalFixture();await h.page.loadDigitalProfiles('21');const old=deferred(),next=deferred();
 h.setClones(id=>id==='21'?old.promise:next.promise);const refresh=h.page.loadDigitalProfiles('21');await flush();
 h.page.changeDigitalPersona(event({},'1'));
 next.resolve([{id:'41',kind:'AVATAR',ready:true,title:'李工分身'},{id:'42',kind:'VOICE',ready:true,title:'李工声音'}]);await flush();
 assert.equal(h.page.data.avatarCloneId,'41');assert.equal(h.page.data.digitalLoading,false,'an abandoned child must not lock the newly loaded persona');
 old.resolve(digitalClones);await refresh;assert.equal(h.page.data.avatarCloneId,'41');
});

test('F06 resource selection handlers cannot reuse displayed options after a same-session permission change',async()=>{
 for(const action of ['selectActor','selectVoice','changeDigitalPersona']){
  const h=digitalFixture();await h.page.loadDigitalProfiles('21');h.changeRole('boss');
  h.page[action](action==='changeDigitalPersona'?event({},'1'):event({id:action==='selectActor'?'31':'32'}));
  assert.equal(h.page.data.digitalPersonas.length,0);assert.equal(h.page.data.avatarCloneId,'');assert.equal(h.page.data.voiceCloneId,'');
 }
});

test('F06 role and organization type changes clear the old resource frame before new authorization',async()=>{
 for(const change of ['changeRole','changeOrgType']){
  const h=digitalFixture();await h.page.loadDigitalProfiles('21');const old=deferred();h.setClones(()=>old.promise);
  const first=h.page.loadDigitalClones('21');h[change](change==='changeRole'?'boss':'company');
  const people=deferred();h.setPeople(()=>people.promise);h.setClones(()=>Promise.resolve([{id:'41',kind:'AVATAR',ready:true,title:'新权限分身'},{id:'42',kind:'VOICE',ready:true,title:'新权限声音'}]));
  const second=h.page.loadDigitalProfiles('21');assert.equal(h.page.data.avatarCloneId,'');assert.equal(h.page.data.digitalPersonas.length,0);
  old.resolve(digitalClones);await first;assert.equal(h.page.data.avatarCloneId,'');
  people.resolve(digitalPeople);await second;assert.equal(h.page.data.avatarCloneId,'41');
 }
});

test('F06 revoked resource permissions clear all displayed people, avatars and voices',async()=>{
 for(const stage of ['people','clones'])for(const code of [403,1103000002]){
  const h=digitalFixture();await h.page.loadDigitalProfiles('21');
  h[stage==='people'?'setPeople':'setClones'](()=>Promise.reject({code,postInvalid:code===1103000002,message:'资源权限失效'}));
  await h.page.loadDigitalProfiles('21');
  assert.equal(h.page.data.digitalPersonas.length,0);assert.equal(h.page.data.digitalPersonaId,'');
  assert.equal(h.page.data.actor,'');assert.equal(h.page.data.voice,'');assert.equal(h.page.data.digitalLoading,false);
  assert.match(h.page.data.digitalResourceError,/权限失效/);
 }
});

test('F06 an expiring 401 clears the old frame even after its request guard is invalid',async()=>{
 const h=digitalFixture();await h.page.loadDigitalProfiles('21');const pending=deferred();h.setPeople(()=>pending.promise);
 const refresh=h.page.loadDigitalProfiles('21');h.logout();pending.reject({code:401,message:'登录已失效'});await refresh;
 assert.equal(h.page.data.digitalPersonas.length,0);assert.equal(h.page.data.avatarCloneId,'');assert.equal(h.page.data.digitalLoading,false);
 assert.match(h.page.data.digitalResourceError,/登录已失效/);
});

test('F06 hide releases the old read but retains its frame and the return reads fresh resources',async()=>{
 const h=digitalFixture();h.page.onLoad({view:'digital',persona:'21'});h.page.onShow();await flush();chooseManualDigital(h.page);
 const before=digitalSnapshot(h.page),old=deferred();h.setPeople(()=>old.promise);h.page.onShow();h.page.onHide();
 assert.deepEqual(digitalSnapshot(h.page),before);assert.equal(h.page.data.digitalLoading,false);
 h.setPeople(()=>Promise.resolve(digitalPeople));h.page.onShow();await flush();assert.equal(h.reads.people,3);
 old.resolve([]);await flush();assert.equal(h.page.data.digitalPersonaId,'21');assert.equal(h.page.data.avatarCloneId,'31');
});

test('F06 a removed persona cannot silently substitute resources in an unresolved paid attempt',async()=>{
 const h=digitalFixture();h.storage.set('zs-digital-attempt:post-1',{key:'paid-once',personaId:'21',payload:{kind:'digital',avatarCloneId:'31',voiceCloneId:'32',text:'待确认文案',title:'原提交'}});
 h.page.restoreDigitalAttempt();h.setPeople(()=>Promise.resolve([digitalPeople[1]]));await h.page.loadDigitalProfiles('21');
 assert.equal(h.page.data.digitalPersonaId,'');assert.equal(h.page.data.avatarCloneId,'');assert.equal(h.reads.clones.length,0);
 assert.equal(h.page.__digitalAttempt.key,'paid-once');assert.equal(h.page.data.digitalRetry,true);assert.match(h.page.data.digitalResourceError,/原提交人物|不存在|停用/);
});

test('F06 resource failures have an independent inline retry without replacing generation errors',()=>{
 const wxml=fs.readFileSync(path.resolve(__dirname,'../miniprogram/pages/studio/index.wxml'),'utf8');
 assert.match(wxml,/digitalResourceError/);assert.match(wxml,/bindtap="refreshDigitalResources"/);
 const h=digitalFixture();assert.equal(typeof h.page.refreshDigitalResources,'function');
});

for(const change of ['role','orgType']){
 test('F06 new paid digital submission rejects same-post '+change+' drift',async()=>{
  const submitted=[];
  const api={personas:{page:async()=>digitalPeople},clones:{list:async()=>digitalClones},tasks:{create:async(body,key)=>{submitted.push([plain(body),key]);throw {code:503,message:'测试请求挂起'};}}};
  const h=harness('studio/index.js',api,change==='role'?'sales-lead':'boss');h.page.data.view='digital';await h.page.loadDigitalProfiles('21');
  h.page.data.script='当前岗位的口播文案';if(change==='role')h.changeRole('boss');else h.changeOrgType('headquarters');
  await h.page.generateDigital();
  assert.equal(submitted.length,0,'a new paid request was sent using resources authorized before the identity change');
  assert.equal(h.page.data.avatarCloneId,'');assert.equal(h.page.data.voiceCloneId,'');assert.equal(h.storage.size,0);
 });
 test('F06 audition refuses same-post '+change+' drift',async()=>{
  const h=harness('studio/index.js',{personas:{page:async()=>digitalPeople},clones:{list:async()=>digitalClones.map(row=>({...row,demoUrl:'https://audio.test/'+row.id+'.mp3'}))}},change==='role'?'sales-lead':'boss');
  h.page.data.view='digital';await h.page.loadDigitalProfiles('21');let played=0;
  h.wx.createInnerAudioContext=()=>({onError(){},play(){played++;},stop(){},destroy(){}});
  if(change==='role')h.changeRole('boss');else h.changeOrgType('headquarters');
  h.page.audition();assert.equal(played,0,'old resource audio was played without rechecking the current identity');
  assert.equal(h.page.data.voiceCloneId,'');assert.match(h.page.data.digitalResourceError,/工作身份已变化/);
 });
}

test('F06 missing live resources still permit the exact frozen paid retry',async()=>{
 const submitted=[],attempt={key:'paid-frozen',personaId:'21',payload:{kind:'digital',avatarCloneId:'31',voiceCloneId:'32',text:'已付费确认中的原文案',title:'原始标题'}};
 const h=harness('studio/index.js',{personas:{page:async()=>[]},clones:{list:async()=>[]},tasks:{create:async(body,key)=>{submitted.push([plain(body),key]);throw {code:503,message:'继续确认'};}}});
 h.page.data.view='digital';h.storage.set('zs-digital-attempt:post-1',attempt);h.page.restoreDigitalAttempt();await h.page.loadDigitalProfiles('21');
 assert.equal(h.page.data.avatarCloneId,'');await h.page.generateDigital();assert.deepEqual(submitted,[[attempt.payload,attempt.key]]);assert.equal(h.page.__digitalAttempt.key,attempt.key);
});
