import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config, bounded, Core, Journal, Provider, runJob, sha } from '../src/runtime.mjs';
import sharp from 'sharp';
import { createHmac } from 'node:crypto';

const job = { jobId: '9007199254740993', attemptNo: 1, fencingToken: 2, phase: 'FLAT', payload: { requestedCount: 2 } };
const durableJournal = { enqueueCoreEvent() {}, acknowledgeCoreEvent() {} };
function scenario(failure) {
  const calls = [];
  const core = { async job(task, endpoint, payload) {
    calls.push([endpoint, payload]);
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: task.phase, images: [], requirements: { width: 10 } };
    if (endpoint === 'output-tickets') return { objectKey: 'quarantine/' + payload.slot, uploadUrl: 'https://storage.example/upload' };
    if (endpoint === 'result-events') {
      if (failure === 'response-lost' && calls.filter(x => x[0] === endpoint).length === 1) throw Error('network');
      return 'QUARANTINED';
    }
    if (endpoint === 'lease-renewals' && failure === 'lease') return false;
    return true;
  } };
  const provider = { journal: durableJournal, imageInput() { throw Error('unexpected'); }, plan: async () => 'plan', upload: async () => {},
    generate: async (task, slot, prompt, images, signal) => {
      if (failure === 'second' && slot === 2) throw Error('timeout');
      if (failure === 'lease') await new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(Error('abort')), { once: true }));
      return { sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' };
    } };
  return { core, provider, calls };
}

test('completion follows all slots; replay uses identical event ID and payload', async () => {
  const s = scenario('response-lost'); await runJob(job, s.core, s.provider, { providerCode: 'apilio' });
  const events = s.calls.filter(x => x[0] === 'result-events');
  assert.equal(events.length, 3);
  // 槽位并发出图，完成顺序不固定：按槽位聚合，重试槽位的两次上报载荷必须逐字段一致
  const bySlot = new Map();
  for (const entry of events) {
    const slot = entry[1].candidateSlotNo, list = bySlot.get(slot) || [];
    list.push(entry); bySlot.set(slot, list);
  }
  assert.deepEqual([...bySlot.keys()].sort((a, b) => a - b), [1, 2]);
  const [single, repeated] = [...bySlot.values()].sort((a, b) => a.length - b.length);
  assert.equal(single.length, 1);
  assert.deepEqual(repeated[0], repeated[1]);
  assert.equal(s.calls.at(-1)[0], 'completion-events');
});
test('提示词扣点端点在实际 plan 调用前只请求一次', async () => {
  const s = scenario(); const order = [];
  const originalJob = s.core.job;
  s.core.job = async (task, endpoint, payload) => { order.push(endpoint); return originalJob(task, endpoint, payload); };
  s.provider.plan = async (job, input, images, signal, billing) => { await billing.reserve(); order.push('provider-plan'); await billing.dispatch(); await billing.confirm('plan'); return 'plan'; };
  await runJob(job, s.core, s.provider, { providerCode: 'apilio' });
  assert.equal(order.filter(value => value === 'prompt-calls').length, 1);
  assert.ok(order.indexOf('prompt-calls') < order.indexOf('provider-plan'));
});
test('partial provider failure sends a completion barrier without regenerating a paid slot', async () => {
  const s = scenario('second'); await assert.rejects(runJob(job, s.core, s.provider, { providerCode: 'apilio' }), /INCOMPLETE/);
  assert.equal(s.calls.filter(x => x[0] === 'result-events').length, 1);
  assert.equal(s.calls.at(-1)[0], 'completion-events');
});
test('slots generate concurrently: the second slot must start before the first completes', async () => {
  const s = scenario();
  const started = [];
  let releaseAll;
  const gate = new Promise(resolve => { releaseAll = resolve; });
  s.provider.generate = async (_task, slot) => {
    started.push(slot);
    if (started.length === job.payload.requestedCount) releaseAll();
    await gate;
    return { sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' };
  };
  const pending = runJob(job, s.core, s.provider, { providerCode: 'apilio' });
  // 串行实现下第二槽永远不会在第一槽挂起时启动：1 秒后仍不齐即判不并发
  await Promise.race([gate, new Promise(resolve => setTimeout(resolve, 1000))]);
  const concurrent = started.length === job.payload.requestedCount;
  releaseAll();  // 无论实现是否并发都放行，保证用例必然结束
  await pending;
  assert.equal(concurrent, true);
});
test('lost lease aborts provider and prevents result or completion writes', async () => {
  const s = scenario('lease'); await assert.rejects(runJob(job, s.core, s.provider, { providerCode: 'apilio' }, { heartbeatMs: 5 }), /LEASE_LOST/);
  assert.equal(s.calls.filter(x => ['result-events', 'completion-events'].includes(x[0])).length, 0);
});
test('FLAT with no reference images uses text-to-image generations and frozen 2K size', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'zs-flat-pure-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const bytes = await sharp({ create: { width: 2, height: 2, channels: 3, background: '#ffffff' } }).png().toBuffer();
  let seen;
  const provider = new Provider({ base: 'https://provider.invalid', key: 'fixture', model: 'fixture',
    temperature: 0, imageModel: 'fixture-image', imageSizeFlat: '1024x1536', imageSizeElevation: '1536x1024' },
  new Journal(dir, 20), async (url, init) => {
    seen = { url: String(url), body: JSON.parse(init.body) };
    return Response.json({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  const image = await provider.generate({ jobId: '9401', phase: 'FLAT', attemptNo: 1, fencingToken: 1,
    payload: { requestedCount: 1 } }, 1, 'pure text prompt', [], new AbortController().signal,
  { resolution: '2K', orientation: 'LANDSCAPE' }, '', undefined);
  assert.match(seen.url, /\/images\/generations$/);
  assert.equal(seen.body.size, '1536x1024');
  assert.equal(image.mimeType, 'image/png');
});

test('elevation without reference images runs as pure text-to-image (需求① 拆分)', async () => {
  const s = scenario(); let planned = false; s.provider.plan = async () => { planned = true; };
  let generateImages;
  s.provider.generate = async (_task, _slot, _prompt, images) => { generateImages = images; return { sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' }; };
  await runJob({ ...job, phase: 'ELEVATION' }, s.core, s.provider, { providerCode: 'apilio' });
  assert.equal(planned, true);
  assert.deepEqual(generateImages, []);
  assert.equal(s.calls.at(-1)[0], 'completion-events');
});
test('input imageOptions 冻结传给每个槽位，无字段的历史任务保持兼容', async () => {
  const seen = [];
  const options = { resolution: '4K', orientation: 'LANDSCAPE' };
  const s = scenario();
  const originalJob = s.core.job;
  s.core.job = async (task, endpoint, payload) => endpoint === 'inputs'
    ? { schemaVersion: 1, phase: task.phase, images: [], requirements: {}, imageOptions: options }
    : originalJob(task, endpoint, payload);
  s.provider.generate = async (_task, _slot, _prompt, _images, _signal, imageOptions) => {
    seen.push(imageOptions);
    return { sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' };
  };
  await runJob(job, s.core, s.provider, { providerCode: 'apilio' });
  assert.deepEqual(seen, [options, options]);
  assert.equal(Object.isFrozen(seen[0]), true);
});
test('input 存在非法 imageOptions 时在 provider 调用前拒绝', async () => {
  const s = scenario(); let called = false;
  s.core.job = async (task, endpoint) => endpoint === 'inputs'
    ? { schemaVersion: 1, phase: task.phase, images: [], requirements: {}, imageOptions: { resolution: '4K', orientation: 'SQUARE' } }
    : true;
  s.provider.plan = async () => { called = true; };
  await assert.rejects(runJob(job, s.core, s.provider, { providerCode: 'apilio' }), /INCOMPLETE/);
  assert.equal(called, false);
});
test('journal restart reuses completed work and uncertain paid call never repeats', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'zs-runtime-'));
  try {
    let calls = 0; const a = new Journal(dir, 2), b = new Journal(dir, 2);
    assert.equal(await a.once('one', async () => { calls++; return 'image'; }), 'image');
    assert.equal(await b.once('one', async () => { calls++; }), 'image');
    await assert.rejects(a.once('two', async () => { calls++; throw Error('timeout'); }));
    await assert.rejects(b.once('two', async () => { calls++; }), /UNCERTAIN/);
    assert.equal(calls, 2);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('streaming body cap works with missing content length and cancels the stream', async () => {
  await assert.rejects(bounded(new Response(new Uint8Array(32)), 8), /BODY_LIMIT/);
  assert.equal((await bounded(new Response('ok'), 8)).toString(), 'ok');
});
test('internal signature covers exact UTF-8 body and request path, no provider credential sent', async () => {
  const core = new Core({ core: 'http://core', secret: 'test-secret' }, async (url, request) => {
    assert.equal(url, 'http://core/internal-api/design/v1/ai-jobs/claims');
    const canonical = `${request.headers['X-ZS-Timestamp']}\nPOST\n/internal-api/design/v1/ai-jobs/claims\n${sha(request.body)}`;
    assert.equal(request.headers['X-ZS-Signature'], createHmac('sha256', 'test-secret').update(canonical).digest('hex'));
    assert.equal(request.headers.Authorization, undefined); return Response.json({ code: 0, data: [] });
  });
  await core.post('/internal-api/design/v1/ai-jobs/claims', { workerId: '中文测试', maxJobs: 1 });
});
test('provider URL responses to non-whitelisted origins are rejected without a fetch', async () => {
  const provider = new Provider({ storageOrigins: ['https://storage.example'], base: 'https://provider.example', key: 'test-key', imageModel: 'fixture',
    imageSizeFlat: '1024x1536', imageSizeElevation: '1536x1024' },
    { once: (key, operation) => operation() }, async () => Response.json({ data: [{ url: 'http://127.0.0.1/secret' }] }));
  // T15 适配：URL 回包仅允许白名单 HTTPS origin；非 HTTPS/未配置白名单在下载前拒绝
  await assert.rejects(provider.generate(job, 1, 'plan', [], new AbortController().signal), /PROVIDER_IMAGE_URL_ORIGIN/);
  await assert.rejects(provider.imageInput({ url: 'https://evil.example/img' }, new AbortController().signal), /STORAGE_ORIGIN/);
});
test('live config requires image model and explicit trusted storage origins, without a daily business limit', () => {
  assert.throws(() => config({}), /MISSING/);
  const env = { ZS_AI_DAILY_CALL_LIMIT: '10', ZS_AI_STORAGE_ORIGINS: 'https://storage.example', ZS_AI_CORE_URL: 'http://core:48080',
    ZS_INTERNAL_SECRET: 'test-secret', ZS_AI_API_KEY: 'test-key', ZS_AI_IMAGE_MODEL: 'reviewed-image-model', ZS_AI_JOURNAL_DIR: '/data',
    ZS_AI_JOURNAL_FS_PROOF_SHA256: 'a'.repeat(64) };
  assert.equal(config(env).base, 'https://api.apilio.ai/v1');
  for (const size of ['2048x1152', '1152x2048', '3840x2160', '2160x3840']) {
    assert.equal(config({ ...env, ZS_AI_IMAGE_SIZE_FLAT: size }).imageSizeFlat, size);
  }
  assert.equal(config({ ...env, ZS_AI_IMAGE_SIZE_FLAT: '1536x1536' }).imageSizeFlat, '1536x1536');
  assert.throws(() => config({ ...env, ZS_AI_BASE_URL: 'http://provider.example' }), /INVALID/);
  assert.equal('dailyLimit' in config({ ...env, ZS_AI_DAILY_CALL_LIMIT: '0' }), false);
});
test('all provider model names fail fast on values the B Provider receipt contract rejects', () => {
  const env = { ZS_AI_STORAGE_ORIGINS: 'https://storage.example', ZS_AI_CORE_URL: 'http://core:48080',
    ZS_INTERNAL_SECRET: 'test-secret', ZS_AI_API_KEY: 'test-key', ZS_AI_IMAGE_MODEL: 'org/image:model-2.5',
    ZS_AI_JOURNAL_DIR: '/data', ZS_AI_JOURNAL_FS_PROOF_SHA256: 'a'.repeat(64) };
  const defaults = config(env);
  assert.equal(defaults.model, 'gpt-5.6-sol');
  assert.equal(defaults.imageModel, 'org/image:model-2.5');
  assert.equal(defaults.imageModelFallback, 'gpt-image-2-all');
  assert.equal(defaults.imageModel2K, null);
  for (const name of ['ZS_AI_MODEL', 'ZS_AI_IMAGE_MODEL', 'ZS_AI_IMAGE_MODEL_FALLBACK', 'ZS_AI_IMAGE_MODEL_2K']) {
    assert.throws(() => config({ ...env, [name]: 'bad model' }), new RegExp(`INVALID_${name}`));
    assert.throws(() => config({ ...env, [name]: ` ${name}` }), new RegExp(`INVALID_${name}`));
    assert.throws(() => config({ ...env, [name]: 'm'.repeat(65) }), new RegExp(`INVALID_${name}`));
  }
});
