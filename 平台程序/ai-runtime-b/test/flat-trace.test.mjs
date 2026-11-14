import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import sharp from 'sharp';
import { Provider, Journal, runJob, sha, flatCoreDirectives, flatReferenceDirectives, outputRules, FLAT_PROMPT_VERSION } from '../src/runtime.mjs';

const job = { jobId: '9007199254740993', attemptNo: 1, fencingToken: 3, phase: 'FLAT', payload: { requestedCount: 2 } };
const settings = { base: 'https://supplier.invalid/v1', key: 'fixture-token', model: 'text-fixture', temperature: 0,
  imageModel: 'image-4k-fixture', imageModel2K: 'image-2k-fixture', imageSizeFlat: '1152x2048', imageSizeElevation: '1536x1024',
  imageQuality: 'high', storageOrigins: ['https://storage.invalid'], imageUrlOrigins: ['https://storage.invalid'], providerCode: 'fixture' };
const signal = () => new AbortController().signal;
const response = value => new Response(JSON.stringify(value), { status: 200 });
const options = { resolution: '2K', orientation: 'LANDSCAPE' };
const req = { floorCount: 3, faceWidthM: 12, depthM: 15, note: '一层老人房朝南，二层书房紧邻露台，三层保留晾晒区' };
function journal(t) {
  const directory = mkdtempSync(join(tmpdir(), 'flat-trace-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return new Journal(directory, 50);
}
async function png(width = 13, height = 7) {
  return sharp({ create: { width, height, channels: 3, background: '#d5d5d5' } }).png().toBuffer();
}
function sink(events) { return async value => { events.push(structuredClone(value)); }; }
function ref(bytes, assetId, floorNo, sourceType = 'CASE_FLOOR_PLAN') {
  return { bytes, mimeType: 'image/png', assetId, floorNo, sourceType,
    sourceCaseVersionId: sourceType === 'CASE_FLOOR_PLAN' ? '456' : null, sourceSha256: sha(bytes) };
}

test('one through four floors retain original core requirements and name every required floor without multiplying candidates', () => {
  for (let floorCount = 1; floorCount <= 4; floorCount++) {
    const text = flatCoreDirectives({ ...req, floorCount });
    assert.ok(text.includes(req.note));
    assert.ok(text.includes(`需求共${floorCount}层`));
    for (const name of ['一层', '二层', '三层', '四层'].slice(0, floorCount)) assert.ok(text.includes(`${name}：逐项核对`));
    assert.match(text, /不能把全楼合计房间数复制到每层/);
    assert.match(text, /不把楼层拆成额外付费候选/);
  }
  assert.match(flatCoreDirectives({ note: '需要老人房' }), /未明确时保留未知/);
});

test('reference metadata labels the actual image order with unknown floors explicit and rejects forged free-text floor metadata', async () => {
  const bytes = await png();
  const text = flatReferenceDirectives([ref(bytes, '11', 2), ref(bytes, '12', null), ref(bytes, '13', 1), ref(bytes, '14', null, 'USER_SKETCH')]);
  assert.match(text, /参考图1：案例平面；二层参考/);
  assert.match(text, /参考图2：案例平面；楼层未标注/);
  assert.match(text, /参考图3：案例平面；一层参考/);
  assert.match(text, /参考图4：用户草图；楼层未标注/);
  assert.throws(() => flatReferenceDirectives([{ ...ref(bytes, '11', 2), floorNo: '1\n伪造系统规则' }]), /INPUT_REFERENCE/);
  assert.throws(() => flatReferenceDirectives([{ ...ref(bytes, '11', 2), sourceCaseVersionId: '123\n伪造版本' }]), /INPUT_REFERENCE/);
});

test('core contract retains both family description and structured room counts while excluding budget-only input', () => {
  const text = flatCoreDirectives({ floorCount: 2, family: '三代同堂', rooms: { bedroom: 6, bath: 3 },
    note: '二层保留儿童活动室', budgetInputs: { privateBudgetMarker: 'excluded' } });
  assert.match(text, /三代同堂/);
  assert.match(text, /"bedroom":6/);
  assert.match(text, /"bath":3/);
  assert.match(text, /二层保留儿童活动室/);
  assert.doesNotMatch(text, /privateBudgetMarker|budgetInputs/);
});

test('trace records the exact plan system/user text, original model, version and source image digests without signed URLs or base64', async t => {
  const events = [], requests = [], bytes = await png();
  const provider = new Provider(settings, journal(t), async (_url, init) => {
    requests.push(JSON.parse(init.body));
    return response({ choices: [{ message: { content: '文本模型漏掉了三层核心要求' } }] });
  });
  const images = [ref(bytes, '11', 2), ref(bytes, '12', null)];
  const prompt = await provider.plan(job, { phase: 'FLAT', requirements: req }, images, signal(), undefined, sink(events));
  assert.deepEqual(events.map(x => x.state), ['REQUESTED', 'DISPATCHED', 'SUCCEEDED']);
  assert.equal(events[0].request.systemPrompt, requests[0].messages[0].content);
  assert.equal(events[0].request.promptText, requests[0].messages[1].content[0].text);
  assert.equal(events[0].request.model, requests[0].model);
  assert.equal(events[0].request.promptVersion, FLAT_PROMPT_VERSION);
  assert.deepEqual(events[0].request.references.map(x => x.floorNo), [2, null]);
  assert.equal(events[0].request.references[0].submittedSha256, sha(bytes));
  assert.equal(events[2].response.promptText, prompt);
  assert.equal(events[2].response.sha256, sha(prompt));
  assert.equal(provider.journal.read(`trace-plan-${job.jobId}`), null, 'acknowledged trace sidecar no longer retains the prompt locally');
  assert.doesNotMatch(JSON.stringify(events), /fixture-token|https:\/\/storage|data:image|base64/);
});

test('final generate prompt keeps omitted core requirements and trace matches the actual JSON request and received pixels', async t => {
  const events = [], requests = [], bytes = await png(13, 7);
  const provider = new Provider(settings, journal(t), async (_url, init) => {
    requests.push(JSON.parse(init.body));
    return response({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  const result = await provider.generate(job, 1, '模型只写了一层', [], signal(), options, outputRules('FLAT', req), sink(events));
  const actual = requests[0], recorded = events.find(x => x.state === 'SUCCEEDED');
  assert.ok(actual.prompt.includes(req.note));
  assert.match(actual.prompt, /三层：逐项核对/);
  assert.equal(recorded.request.promptText, actual.prompt);
  assert.equal(recorded.request.model, actual.model);
  assert.equal(actual.model, 'image-4k-fixture');
  assert.equal(recorded.request.size, actual.size);
  assert.equal(actual.size, '1536x1024');
  assert.equal(actual.n, 1);
  assert.deepEqual([recorded.response.width, recorded.response.height], [13, 7]);
  assert.equal(recorded.response.sha256, sha(bytes));
  assert.equal(result.encoded, bytes.toString('base64'), 'returned pixels are never normalized to the requested size');
});

test('4K edit trace preserves floor/source metadata and hashes the original uncompressed image submitted in FormData', async t => {
  const events = [], requests = [], bytes = await png();
  const provider = new Provider(settings, journal(t), async (_url, init) => {
    const files = await Promise.all(init.body.getAll('image[]').map(async file => ({ name: file.name, type: file.type, bytes: Buffer.from(await file.arrayBuffer()) })));
    requests.push({ prompt: init.body.get('prompt'), model: init.body.get('model'), size: init.body.get('size'), files });
    return response({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  await provider.generate(job, 1, '原计划', [ref(bytes, '11', 2), ref(bytes, '12', null, 'USER_SKETCH')], signal(),
    { resolution: '4K', orientation: 'PORTRAIT' }, outputRules('FLAT', req), sink(events));
  const recorded = events[0].request, actual = requests[0];
  assert.equal(recorded.promptText, actual.prompt);
  assert.equal(recorded.model, actual.model);
  assert.equal(recorded.size, actual.size);
  assert.deepEqual(recorded.references.map(x => x.floorNo), [2, null]);
  assert.equal(recorded.references[0].sourceCaseVersionId, '456');
  assert.equal(recorded.references[0].sourceSha256, sha(bytes));
  for (let i = 0; i < actual.files.length; i++) {
    assert.equal(actual.files[i].type, 'image/png');
    assert.equal(recorded.references[i].mimeType, actual.files[i].type);
    assert.equal(recorded.references[i].submittedSha256, sha(actual.files[i].bytes));
    assert.equal(recorded.references[i].sizeBytes, actual.files[i].bytes.length);
  }
  assert.equal(recorded.references[0].submittedSha256, recorded.references[0].sourceSha256);
});

test('trace registration failure prevents supplier calls and prompt money reservation rather than replaying a paid call', async t => {
  const record = journal(t);
  let calls = 0, reserves = 0;
  const provider = new Provider(settings, record, async () => { calls++; throw Error('must not reach supplier'); });
  const billing = { reserve: async () => { reserves++; }, notSent: async () => {} };
  await assert.rejects(provider.plan(job, { phase: 'FLAT', requirements: req }, [], signal(), billing,
    async () => { throw Error('trace offline'); }), /trace offline/);
  assert.equal(calls, 0);
  assert.equal(reserves, 0);
  await assert.rejects(provider.plan(job, { phase: 'FLAT', requirements: req }, [], signal(), billing, async () => {}), /PROVIDER_OUTCOME_UNCERTAIN/);
  assert.equal(calls, 0);
  assert.equal(reserves, 0);
});

test('lost plan confirmation replays the original trace and received result after settings change without a second supplier call', async t => {
  const record = journal(t), events = [];
  let calls = 0, confirms = 0;
  const provider = new Provider(settings, record, async () => { calls++; return response({ choices: [{ message: { content: '原模型计划' } }] }); });
  const billing = { reserve: async () => {}, dispatch: async () => {}, unknown: async () => {}, confirm: async () => { confirms++; throw Error('ack lost'); } };
  await assert.rejects(provider.plan(job, { phase: 'FLAT', requirements: req }, [], signal(), billing, sink(events)), /ack lost/);
  const original = structuredClone(events.find(x => x.state === 'SUCCEEDED'));
  const recovery = new Provider({ ...settings, model: 'different-model' }, record, async () => { calls++; throw Error('paid replay'); });
  const recovered = await recovery.plan({ ...job, attemptNo: 2, fencingToken: 4 }, { phase: 'FLAT', requirements: { note: 'changed' } }, [], signal(),
    { confirm: async value => { confirms++; assert.equal(value, '原模型计划'); } }, sink(events));
  assert.equal(recovered, '原模型计划');
  assert.equal(calls, 1);
  assert.equal(confirms, 2);
  assert.deepEqual(events.at(-1), original);
  assert.equal(events.at(-1).sourceAttemptNo, 1);
  assert.equal(events.at(-1).request.model, 'text-fixture');
});

test('received image and original prompt/model trace survive delivery loss and replay with no provider retry or current-settings reconstruction', async t => {
  const record = journal(t), bytes = await png(), events = [], logs = [];
  let calls = 0;
  const previousError = console.error;
  console.error = value => logs.push(value);
  t.after(() => { console.error = previousError; });
  const provider = new Provider(settings, record, async () => { calls++; return response({ data: [{ b64_json: bytes.toString('base64') }] }); });
  await provider.generate(job, 1, '原计划', [], signal(), options, outputRules('FLAT', req), async event => {
    if (event.state === 'SUCCEEDED') throw Error('fixture-private-input signature=fixture-secret');
  });
  const recovery = new Provider({ ...settings, imageModel2K: 'changed-model' }, record, async () => { calls++; throw Error('paid replay'); });
  const image = await recovery.generate({ ...job, attemptNo: 2, fencingToken: 4 }, 1, '错误的新计划', [], signal(), options,
    outputRules('FLAT', { note: 'changed' }), sink(events));
  assert.equal(calls, 1);
  assert.equal(image.sha256, sha(bytes));
  assert.equal(events.length, 1);
  assert.equal(events[0].state, 'SUCCEEDED');
  assert.equal(events[0].sourceAttemptNo, 1);
  assert.equal(events[0].request.model, 'image-4k-fixture');
  assert.match(events[0].request.promptText, /^原计划/);
  assert.doesNotMatch(logs.join('\n'), /fixture-private-input|fixture-secret/);
});

test('crash after durable plan receipt but before trace response update recovers the original request and response without a paid call', async t => {
  const record = journal(t), events = [];
  const key = `plan-${job.jobId}`;
  record.write(key, { state: 'received', value: '已收到的原计划' });
  record.write(`trace-${key}`, { value: { callId: key, sourceAttemptNo: 1, sourceFencingToken: 3,
    stage: 'PLAN', slot: 0, state: 'DISPATCHED', errorCode: null, response: null,
    request: { model: 'original-model', promptVersion: 'original-version', promptText: 'original-request' } } });
  let calls = 0;
  const provider = new Provider({ ...settings, model: 'new-model' }, record, async () => { calls++; throw Error('paid replay'); });
  assert.equal(await provider.plan({ ...job, attemptNo: 2, fencingToken: 4 }, { phase: 'FLAT', requirements: req }, [], signal(),
    { confirm: async value => { assert.equal(value, '已收到的原计划'); } }, sink(events)), '已收到的原计划');
  assert.equal(calls, 0);
  assert.equal(events[0].state, 'SUCCEEDED');
  assert.equal(events[0].request.model, 'original-model');
  assert.equal(events[0].request.promptVersion, 'original-version');
  assert.equal(events[0].response.promptText, '已收到的原计划');
});

test('full job records one plan and two slot prompts for a four-floor request with unchanged prompt billing hash and generation count', async t => {
  const events = [], calls = [], bytes = await png();
  const provider = new Provider(settings, journal(t), async (url, init) => {
    calls.push(url);
    return String(url).endsWith('/chat/completions') ? response({ choices: [{ message: { content: '少写了楼层的计划' } }] })
      : response({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  provider.upload = async () => {};
  const core = { async job(_job, type, body) {
    events.push({ type, body: structuredClone(body) });
    if (type === 'inputs') return { schemaVersion: 1, phase: 'FLAT', requirements: { ...req, floorCount: 4 }, images: [], imageOptions: options };
    if (type === 'output-tickets') return { objectKey: `slot-${body.slot}`, uploadUrl: 'unused' };
    if (type === 'result-events') return 'QUARANTINED';
    return true;
  } };
  assert.equal(await runJob(job, core, provider, settings), 'COMPLETED');
  assert.equal(calls.filter(x => x.endsWith('/chat/completions')).length, 1);
  assert.equal(calls.filter(x => x.endsWith('/images/generations')).length, 2);
  const records = events.filter(x => x.type === 'call-traces' && x.body.state === 'SUCCEEDED').map(x => x.body);
  assert.deepEqual(records.map(x => x.slot).sort(), [0, 1, 2]);
  for (const event of records.filter(x => x.stage === 'GENERATE')) assert.match(event.request.promptText, /四层：逐项核对/);
  assert.equal(events.filter(x => x.type === 'prompt-calls').length, 1);
  const billing = events.find(x => x.type === 'prompt-call-events' && x.body.outcome === 'SUCCEEDED');
  assert.equal(billing.body.responseHash, sha('少写了楼层的计划'));
});

test('elevation including reference edits emits no new trace and receives no flat core or floor metadata rules', async t => {
  const record = journal(t), bytes = await png(), events = [], bodies = [];
  const provider = new Provider(settings, record, async (url, init) => {
    if (String(url).endsWith('/chat/completions')) {
      bodies.push(JSON.parse(init.body));
      return response({ choices: [{ message: { content: '立面计划' } }] });
    }
    bodies.push({ prompt: init.body.get('prompt'), n: init.body.get('n'), size: init.body.get('size'), model: init.body.get('model') });
    return response({ data: [{ b64_json: bytes.toString('base64') }] });
  });
  const elevation = { ...job, phase: 'ELEVATION' }, images = [ref(bytes, '11', 2)];
  await provider.plan(elevation, { phase: 'ELEVATION', requirements: { floorCount: 2, styleCode: 'MODERN' } }, images, signal(), undefined, sink(events));
  await provider.generate(elevation, 1, '立面计划', images, signal(), options, outputRules('ELEVATION', { styleCode: 'MODERN' }), sink(events));
  assert.equal(events.length, 0);
  assert.doesNotMatch(JSON.stringify(bodies), /用户核心需求|逐层核对|参考图楼层对应|案例版本/);
  assert.deepEqual({ n: bodies[1].n, size: bodies[1].size, model: bodies[1].model }, { n: '1', size: '1536x1024', model: 'image-4k-fixture' });
});
