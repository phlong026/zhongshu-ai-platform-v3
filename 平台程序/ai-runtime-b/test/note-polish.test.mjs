import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Journal, Provider, sha } from '../src/runtime.mjs';
import { createInternalHandler, verifySignature } from '../src/internal-http.mjs';

// 补充需求「AI 优化」走 ai-runtime（方案 A）：Provider 密钥只留在本进程，后端以内部签名调用，不再持有密钥
const SECRET = 'test-internal-secret-0123456789abcdef';
const PATH = '/internal/note-polish';

function sign(body, { path = PATH, method = 'POST', timestamp = String(Math.floor(Date.now() / 1000)), secret = SECRET } = {}) {
  const signature = createHmac('sha256', secret).update(`${timestamp}\n${method}\n${path}\n${sha(body)}`).digest('hex');
  return { 'x-zs-timestamp': timestamp, 'x-zs-signature': signature };
}

function fixture({ polish, maxConcurrent = 4 } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'note-polish-'));
  const journal = new Journal(dir, 50);
  const provider = { polishNote: polish || (async (note, line) => `润色:${line}:${note}`) };
  const settings = { secret: SECRET };
  const handle = createInternalHandler({ settings, provider, journal, maxConcurrent });
  const call = (payload, headers, extra = {}) => {
    if (payload && typeof payload === 'object' && typeof payload.note === 'string' && payload.note.trim() && payload.receiptId === undefined) {
      payload = { receiptId: `fixture-${sha(JSON.stringify(payload)).slice(0, 24)}`, ...payload };
    }
    const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
    return handle({ method: 'POST', path: PATH, headers: headers ?? sign(body), body: Buffer.from(body), ...extra });
  };
  return { dir, journal, call, handle, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

// ===== 内部签名 =====

test('签名缺失、错误、过期或请求体被改动一律 401，且不调用供应商', async () => {
  let called = 0;
  const f = fixture({ polish: async () => { called++; return 'x'; } });
  const payload = { note: '老人房在一楼', line: 'FLAT' };
  assert.equal((await f.call(payload, {})).status, 401);
  assert.equal((await f.call(payload, sign(JSON.stringify(payload), { secret: 'another-secret-0123456789abcdef' }))).status, 401);
  assert.equal((await f.call(payload, sign(JSON.stringify(payload), { timestamp: String(Math.floor(Date.now() / 1000) - 3600) }))).status, 401);
  assert.equal((await f.call(payload, sign(JSON.stringify({ ...payload, note: '另一段' })))).status, 401);
  assert.equal((await f.call(payload, sign(JSON.stringify(payload), { path: '/internal/other' }))).status, 401);
  assert.equal(called, 0);
  f.cleanup();
});

test('签名正确时返回润色结果；响应只含 polished，不回显任何密钥', async () => {
  const f = fixture();
  const result = await f.call({ note: '老人房在一楼', line: 'ELEVATION' });
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { polished: '润色:ELEVATION:老人房在一楼' });
  assert.doesNotMatch(JSON.stringify(result.body), new RegExp(SECRET));
  f.cleanup();
});

test('非 POST 或其他路径返回 404/405', async () => {
  const f = fixture();
  assert.equal((await f.handle({ method: 'GET', path: PATH, headers: {}, body: Buffer.alloc(0) })).status, 405);
  assert.equal((await f.handle({ method: 'POST', path: '/internal/unknown', headers: sign('{}'), body: Buffer.from('{}') })).status, 404);
  f.cleanup();
});

// ===== 入参校验 =====

test('入参校验：note 必填且 ≤200 字，line 只能是 FLAT/ELEVATION（缺省按 FLAT）', async () => {
  const f = fixture();
  for (const bad of [{}, { note: '' }, { note: '   ' }, { note: 1 }, { note: 'x'.repeat(201) }, { note: '好', line: 'HACK' }]) {
    assert.equal((await f.call(bad)).status, 400, JSON.stringify(bad).slice(0, 40));
  }
  assert.equal((await f.call('not-json')).status, 400);
  assert.equal((await f.call({ note: '好' })).body.polished, '润色:FLAT:好');
  f.cleanup();
});

// ===== 公司积分前置校验后的短时并发保护 =====

test('润色无每日业务上限且不创建配额文件', async () => {
  const f = fixture();
  for (let index = 0; index < 12; index++) assert.equal((await f.call({ note: `需求${index}` })).status, 200);
  assert.equal(readdirSync(f.dir).some(name => /quota|reserved/.test(name)), false);
  f.cleanup();
});

test('并发超过上限时多余请求 429 BUSY，不排队占满进程', async () => {
  const release = [];
  const f = fixture({ maxConcurrent: 2, polish: () => new Promise(resolve => release.push(() => resolve('done'))) });
  const pending = [f.call({ note: 'a' }), f.call({ note: 'b' })];
  await new Promise(resolve => setImmediate(resolve));
  const third = await f.call({ note: 'c' });
  assert.equal(third.status, 429);
  assert.equal(third.body.error, 'BUSY');
  release.forEach(done => done());
  assert.deepEqual((await Promise.all(pending)).map(r => r.status), [200, 200]);
  f.cleanup();
});

// ===== 供应商失败：不外泄内部信息 =====

test('供应商失败返回 502 通用错误，响应体不含上游报错文本', async () => {
  const f = fixture({ polish: async () => { throw Error('connect ECONNREFUSED 10.0.0.8:443 key=sk-secret'); } });
  const result = await f.call({ note: '好' });
  assert.equal(result.status, 502);
  assert.deepEqual(result.body, { error: 'PROVIDER_FAILED' });
  f.cleanup();
});

// ===== Provider.polishNote =====

test('polishNote：按线路拼场景提示、固定 max_tokens，输出限制 200 字，空内容报错', async () => {
  const bodies = [];
  const answers = ['长'.repeat(300), '   '];
  const settings = { base: 'https://api.apilio.ai/v1', key: 'k', model: 'fixture-model', temperature: 0.7 };
  const provider = new Provider(settings, { once: async (_k, op) => op() }, async (url, init) => {
    bodies.push({ url, body: JSON.parse(init.body), auth: init.headers.Authorization });
    return new Response(JSON.stringify({ choices: [{ message: { content: answers.shift() } }] }), { status: 200 });
  });
  const signal = new AbortController().signal;
  const text = await provider.polishNote('白墙灰瓦', 'ELEVATION', signal);
  assert.equal(text.length, 200);
  assert.equal(bodies[0].url, 'https://api.apilio.ai/v1/chat/completions');
  assert.equal(bodies[0].body.model, 'fixture-model');
  assert.equal(bodies[0].body.max_tokens, 400);
  assert.match(bodies[0].body.messages[0].content, /聚焦建筑外观/);
  assert.equal(bodies[0].body.messages[1].content, '白墙灰瓦');
  assert.equal(bodies[0].auth, 'Bearer k');
  await assert.rejects(provider.polishNote('老人房', 'FLAT', signal), /POLISH_EMPTY/);
  assert.match(bodies[1].body.messages[0].content, /聚焦平面布置/);
});

// ===== 真实 HTTP 监听：读取上限、头部映射、/health 行为不变 =====

import { createServer } from 'node:http';
import { createRequestListener } from '../src/internal-http.mjs';

async function withServer(listenerOptions, run) {
  const f = fixture();
  const internal = createInternalHandler({ settings: { secret: SECRET }, journal: f.journal,
    provider: { polishNote: async note => `润色:${note}` } });
  const server = createServer(createRequestListener({ internal, healthy: () => listenerOptions.healthy, version: '1.0.0' }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await run(base); } finally { server.close(); f.cleanup(); }
}

test('HTTP：/health 行为不变；润色经真实请求往返；超过 4KB 的请求体 413；未知路径 404', async () => {
  const options = { healthy: true };
  await withServer(options, async base => {
    let response = await fetch(`${base}/health`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'UP', version: '1.0.0' });
    options.healthy = false;
    assert.equal((await fetch(`${base}/health`)).status, 503);

    const body = JSON.stringify({ receiptId: 'fixture-http-roundtrip', note: '老人房在一楼', line: 'FLAT' });
    response = await fetch(base + PATH, { method: 'POST', body, headers: { 'content-type': 'application/json', ...sign(body) } });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { polished: '润色:老人房在一楼' });

    const huge = JSON.stringify({ note: 'x'.repeat(5000) });
    response = await fetch(base + PATH, { method: 'POST', body: huge, headers: { 'content-type': 'application/json', ...sign(huge) } });
    assert.equal(response.status, 413);

    assert.equal((await fetch(`${base}/other`)).status, 404);
    assert.equal((await fetch(base + PATH, { method: 'POST', body })).status, 401, '无签名头必须 401');
  });
});

// ===== 跨语言黄金向量：与后端 NotePolishServiceTest 使用同一组固定输入，任何一端改了签名口径都会红 =====

test('黄金向量：固定 secret/时间戳/请求体得到固定签名，verifySignature 接受它并拒绝任何偏差', () => {
  const secret = 'golden-secret-0123456789abcdef', timestamp = '1760000000', body = Buffer.from('{"note":"老人房在一楼","line":"FLAT"}');
  const signature = '363c3ff8e9d0ecf0f2626b7a4c54b5f334a3cc428094c5a26ce381a0df1dd454';
  const base = { secret, method: 'POST', path: PATH, body, now: Number(timestamp) * 1000,
    headers: { 'x-zs-timestamp': timestamp, 'x-zs-signature': signature } };
  assert.equal(verifySignature(base), true);
  assert.equal(verifySignature({ ...base, path: '/internal/other' }), false);
  assert.equal(verifySignature({ ...base, method: 'GET' }), false);
  assert.equal(verifySignature({ ...base, body: Buffer.from('{}') }), false);
  assert.equal(verifySignature({ ...base, secret: secret + 'x' }), false);
  assert.equal(verifySignature({ ...base, now: base.now + 301_000 }), false, '超出 300 秒重放窗口');
});
