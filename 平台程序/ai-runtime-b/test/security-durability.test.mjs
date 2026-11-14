import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { chmodSync, mkdtempSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Journal, Provider, runCoreEventReconciler, runJob, sha } from '../src/runtime.mjs';
import { createInternalHandler, NOTE_POLISH_PATH } from '../src/internal-http.mjs';

const secret = 'security-fixture-secret-0123456789';
function request(payload) {
  const body = Buffer.from(JSON.stringify(payload));
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac('sha256', secret).update(`${timestamp}\nPOST\n${NOTE_POLISH_PATH}\n${sha(body)}`).digest('hex');
  return { method: 'POST', path: NOTE_POLISH_PATH, body,
    headers: { 'x-zs-timestamp': timestamp, 'x-zs-signature': signature } };
}

test('receiptId 使润色在并发和重启后只付费一次，并拒绝缺失、篡改和未知重投', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'polish-receipt-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  let calls = 0, release;
  const gate = new Promise(resolve => { release = resolve; });
  const provider = { async polishNote(note) { calls++; await gate; return `润色:${note}`; } };
  const first = createInternalHandler({ settings: { secret }, provider, journal: new Journal(dir) });
  const payload = { receiptId: 'company-1:content-9', note: '老人房在一楼', line: 'FLAT' };
  const pending = [first(request(payload)), first(request(payload))];
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls, 1);
  release();
  assert.deepEqual((await Promise.all(pending)).map(value => value.body.polished), ['润色:老人房在一楼', '润色:老人房在一楼']);
  const restarted = createInternalHandler({ settings: { secret }, provider, journal: new Journal(dir) });
  assert.equal((await restarted(request(payload))).body.polished, '润色:老人房在一楼');
  assert.equal(calls, 1);
  assert.equal((await restarted(request({ note: payload.note, line: payload.line }))).status, 400);
  assert.equal((await restarted(request({ ...payload, note: '被篡改的需求' }))).status, 409);

  const unknownPayload = { receiptId: 'company-1:content-unknown', note: '未知结果', line: 'FLAT' };
  let unknownCalls = 0;
  const uncertain = createInternalHandler({ settings: { secret }, journal: new Journal(dir),
    provider: { async polishNote() { unknownCalls++; throw Error('response lost'); } } });
  assert.equal((await uncertain(request(unknownPayload))).status, 502);
  assert.equal((await uncertain(request(unknownPayload))).status, 409);
  assert.equal(unknownCalls, 1);
});

test('UNKNOWN、NOT_SENT 与 completion 发送失败进入 outbox，重启仅补原栅栏而不调用供应商', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'core-outbox-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  const job = { jobId: '991', attemptNo: 7, fencingToken: 13, phase: 'FLAT', payload: { requestedCount: 1 } };
  let supplierCalls = 0;
  const provider = { journal, imageInput() { throw Error('unexpected'); }, async plan(_job, _input, _images, _signal, billing) {
    await billing.reserve(); await billing.dispatch(); supplierCalls++;
    try { throw Error('supplier response lost'); } finally { await billing.unknown(); }
  } };
  const failingCore = { async job(_identity, endpoint, payload) {
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: 'FLAT', images: [], requirements: {} };
    if (endpoint === 'prompt-calls' || (endpoint === 'prompt-call-events' && payload.outcome === 'DISPATCHED')) return true;
    throw Error('core unavailable');
  } };
  await assert.rejects(runJob(job, failingCore, provider, { providerCode: 'fixture' }), /INCOMPLETE/);
  const notSentJob = { ...job, jobId: '992', attemptNo: 8, fencingToken: 14 };
  const notSentProvider = { journal, imageInput() { throw Error('unexpected'); }, async plan(_job, _input, _images, _signal, billing) {
    await billing.reserve(); await billing.notSent(); throw Error('dispatch acknowledgement lost');
  } };
  await assert.rejects(runJob(notSentJob, failingCore, notSentProvider, { providerCode: 'fixture' }), /INCOMPLETE/);
  assert.equal(supplierCalls, 1);
  assert.equal(new Journal(dir).pendingCoreEvents().length, 4);

  const delivered = [];
  const restarted = new Journal(dir);
  await runCoreEventReconciler({ async job(identity, endpoint, payload) {
    delivered.push({ identity: structuredClone(identity), endpoint, payload: structuredClone(payload) });
    return true;
  } }, restarted);
  assert.equal(supplierCalls, 1);
  assert.equal(restarted.pendingCoreEvents().length, 0);
  assert.deepEqual(delivered.map(value => value.endpoint).sort(),
    ['completion-events', 'completion-events', 'prompt-call-events', 'prompt-call-events']);
  assert.deepEqual(delivered.filter(value => value.endpoint === 'prompt-call-events').map(value => value.payload.outcome).sort(),
    ['NOT_SENT', 'UNKNOWN']);
  assert.ok(delivered.some(value => value.identity.attemptNo === 7 && value.identity.fencingToken === 13));
  assert.ok(delivered.some(value => value.identity.attemptNo === 8 && value.identity.fencingToken === 14));
  assert.equal(restarted.healthy(0), true);
  assert.equal(restarted.healthy(Number.MAX_SAFE_INTEGER), false);
});

test('DISPATCHED 回执连续丢失时资金 UNKNOWN 进入 outbox，trace NOT_SENT 且重启不调用供应商', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dispatch-ack-outbox-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir);
  let supplierCalls = 0, dispatchAttempts = 0;
  const provider = new Provider({ base: 'https://supplier.invalid', key: 'fixture', model: 'fixture', temperature: 0,
    capacityGuard: false }, journal, async () => { supplierCalls++; throw Error('supplier must not run'); });
  const job = { jobId: '994', attemptNo: 5, fencingToken: 17, phase: 'FLAT', payload: { requestedCount: 1 } };
  const core = { async job(_identity, endpoint, payload) {
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: 'FLAT', images: [], requirements: {} };
    if (endpoint === 'prompt-calls' || endpoint === 'call-traces') return true;
    if (endpoint === 'prompt-call-events' && payload.outcome === 'DISPATCHED') {
      dispatchAttempts++;
      throw Error('ack lost after possible commit');
    }
    throw Error('core unavailable');
  } };
  await assert.rejects(runJob(job, core, provider, { model: 'fixture', providerCode: 'fixture' }), /INCOMPLETE/);
  assert.equal(dispatchAttempts, 3);
  assert.equal(supplierCalls, 0);
  assert.equal(journal.read('trace-plan-994'), null);
  assert.deepEqual(journal.pendingCoreEvents().map(event => [event.endpoint, event.payload.outcome]),
    [['prompt-call-events', 'UNKNOWN'], ['completion-events', undefined]]);

  const delivered = [];
  await runCoreEventReconciler({ async job(identity, endpoint, payload) {
    delivered.push([structuredClone(identity), endpoint, structuredClone(payload)]);
    return true;
  } }, new Journal(dir));
  assert.equal(supplierCalls, 0);
  assert.deepEqual(delivered.map(([, endpoint, payload]) => [endpoint, payload.outcome]),
    [['prompt-call-events', 'UNKNOWN'], ['completion-events', undefined]]);
  assert.equal(delivered[0][0].attemptNo, 5);
  assert.equal(delivered[0][0].fencingToken, 17);
});

test('磁盘水位不足时拒绝新的润色供应商调用', async () => {
  let calls = 0;
  const handler = createInternalHandler({ settings: { secret }, journal: { healthy: () => false },
    provider: { async polishNote() { calls++; return 'forbidden'; } } });
  const result = await handler(request({ receiptId: 'low-disk-fixture', note: '老人房', line: 'FLAT' }));
  assert.equal(result.status, 503);
  assert.equal(calls, 0);
});

test('付费外呼前实际分配同文件系统容量，ENOSPC fail-closed，并发各自持有物理空间', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'paid-capacity-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  chmodSync(dir, 0o777);
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  assert.equal(statSync(dir).mode & 0o777, 0o700);
  let calls = 0;
  journal.allocateCapacityFile = () => { const error = Error('fixture full'); error.code = 'ENOSPC'; throw error; };
  await assert.rejects(journal.once('paid-full', async () => { calls++; }, 64 * 1024), /JOURNAL_CAPACITY/);
  assert.equal(calls, 0);
  assert.equal(readdirSync(dir).some(name => name.includes('paid-full') && name.endsWith('.started')), false);

  delete journal.allocateCapacityFile;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const pending = ['paid-a', 'paid-b'].map(key => journal.once(key, async () => { calls++; await gate; return key; }, 64 * 1024));
  await new Promise(resolve => setImmediate(resolve));
  const reservations = readdirSync(dir).filter(name => name.endsWith('.capacity') && !name.startsWith('emergency-'));
  assert.equal(reservations.length, 2);
  assert.equal(journal.capacityReservations().length, 2);
  assert.ok(reservations.every(name => statSync(join(dir, name)).blocks * 512 >= 64 * 1024));
  release();
  assert.deepEqual((await Promise.all(pending)).sort(), ['paid-a', 'paid-b']);
});

test('outbox 强制 prompt 终态 ACK 后才发 completion；失败、逆序和损坏均 fail-closed', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'ordered-outbox-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir);
  const identity = { schemaVersion: 1, jobId: '880', attemptNo: 2, fencingToken: 5, requireTrue: true };
  const completion = { ...identity, endpoint: 'completion-events', payload: {} };
  const terminal = { ...identity, endpoint: 'prompt-call-events', payload: { outcome: 'UNKNOWN', callId: 'plan-880' } };
  journal.enqueueCoreEvent(completion);
  journal.enqueueCoreEvent(terminal);
  const first = [];
  await runCoreEventReconciler({ async job(_job, endpoint) { first.push(endpoint); if (endpoint === 'prompt-call-events') throw Error('ack lost'); return true; } }, journal);
  assert.deepEqual(first, ['prompt-call-events']);
  const second = [];
  await runCoreEventReconciler({ async job(_job, endpoint) { second.push(endpoint); return true; } }, new Journal(dir));
  assert.deepEqual(second, ['prompt-call-events', 'completion-events']);

  const corrupt = join(dir, `core-event-${'a'.repeat(64)}.json`);
  writeFileSync(corrupt, '{broken');
  journal.enqueueCoreEvent({ ...identity, jobId: '881', endpoint: 'completion-events', payload: {} });
  let completionCalls = 0;
  await runCoreEventReconciler({ async job() { completionCalls++; return true; } }, journal);
  assert.equal(completionCalls, 0);
  assert.equal(statSync(corrupt).isFile(), true);
});

test('物理块统计缺失、NaN 或不足均在供应商外呼前 fail-closed', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'invalid-blocks-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  for (const invalid of [undefined, Number.NaN, 1024]) {
    const journal = new Journal(dir, { capacityProofVerifier: () => true }); let calls = 0;
    journal.allocatedBytes = () => invalid;
    await assert.rejects(journal.once(`blocks-${String(invalid)}`, async () => { calls++; }, 64 * 1024), /JOURNAL_CAPACITY/);
    assert.equal(calls, 0);
  }
});

test('付费容量门禁要求卷内能力证明文件与配置SHA一致', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'capacity-proof-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const bytes = Buffer.from(JSON.stringify({ version: 1, filesystem: 'ext4', allocation: 'thick',
    exclusiveWriter: true, volumeId: 'fixture-volume' }));
  const proof = join(dir, '.capacity-proof.json'); writeFileSync(proof, bytes, { mode: 0o600 }); chmodSync(proof, 0o600);
  const accepted = new Journal(dir, { capacityProofSha256: sha(bytes) });
  assert.equal(accepted.verifyCapacityProof(), true);
  assert.equal(accepted.prepareCapacity(), true);
  assert.equal(accepted.capacityHealthy(), true);
  accepted.removeFile(join(dir, 'emergency-2.capacity'));
  assert.equal(accepted.capacityHealthy(), false);
  const rejected = new Journal(dir, { capacityProofSha256: '0'.repeat(64) }); let calls = 0;
  await assert.rejects(rejected.once('proof-rejected', async () => { calls++; }, 4096), /JOURNAL_FS_PROOF/);
  assert.equal(calls, 0);
});

test('outbox 按 BigInt jobId 和数值 attempt/fencing 排序，并由所有更早未ACK资金终态阻断 completion', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'numeric-outbox-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir), base = { schemaVersion: 1, jobId: '90071992547409930', requireTrue: true };
  journal.enqueueCoreEvent({ ...base, attemptNo: 10, fencingToken: 10, endpoint: 'completion-events', payload: {} });
  journal.enqueueCoreEvent({ ...base, attemptNo: 2, fencingToken: 2, endpoint: 'prompt-call-events',
    payload: { outcome: 'UNKNOWN', callId: `plan-${base.jobId}` } });
  const calls = [];
  await runCoreEventReconciler({ async job(identity, endpoint) {
    calls.push([identity.attemptNo, endpoint]);
    if (endpoint === 'prompt-call-events') throw Error('still unavailable');
    return true;
  } }, journal);
  assert.deepEqual(calls, [[2, 'prompt-call-events']]);
});

test('合法JSON但schema非法视为corrupt；目录扫描异常只记录并允许下一轮重试', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'schema-corrupt-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir);
  writeFileSync(join(dir, `core-event-${'b'.repeat(64)}.json`), JSON.stringify({ value: { endpoint: 'completion-events' } }));
  journal.enqueueCoreEvent({ schemaVersion: 1, jobId: '777', attemptNo: 1, fencingToken: 1,
    endpoint: 'completion-events', payload: {}, requireTrue: true });
  let calls = 0;
  await runCoreEventReconciler({ async job() { calls++; return true; } }, journal);
  assert.equal(calls, 0);
  const original = journal.pendingCoreEventRecords.bind(journal); let scans = 0;
  journal.pendingCoreEventRecords = () => { scans++; if (scans === 1) throw Error('EIO'); return original(); };
  await runCoreEventReconciler({ async job() { calls++; return true; } }, journal);
  await runCoreEventReconciler({ async job() { calls++; return true; } }, journal);
  assert.equal(scans, 2);
  assert.equal(calls, 0, 'schema损坏仍在时不得发送completion');
});

test('供应商受理后trace、UNKNOWN与completion连续遇ENOSPC仍使用独立紧急预留落盘', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'post-dispatch-enospc-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  journal.ensureEmergencyReserve();
  let accepted = false, supplierCalls = 0;
  const originalPersist = journal.persistAtomic.bind(journal), failed = new Set();
  journal.persistAtomic = (temp, path, data) => {
    if (accepted && !failed.has(path)) { failed.add(path); const error = Error('disk full'); error.code = 'ENOSPC'; throw error; }
    return originalPersist(temp, path, data);
  };
  const settings = { base: 'https://supplier.invalid', key: 'fixture', model: 'fixture', temperature: 0,
    providerCode: 'fixture', capacityGuard: false };
  const provider = new Provider(settings, journal, async () => { accepted = true; supplierCalls++; throw Error('response lost'); });
  const events = [];
  const core = { async job(job, endpoint, payload) {
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: job.phase, images: [], requirements: {} };
    if (endpoint === 'prompt-calls') return true;
    if (endpoint === 'prompt-call-events' || endpoint === 'completion-events' || endpoint === 'call-traces') {
      events.push([endpoint, structuredClone(payload)]); return true;
    }
    throw Error(`unexpected:${endpoint}`);
  } };
  const job = { jobId: '993', attemptNo: 3, fencingToken: 9, phase: 'FLAT', payload: { requestedCount: 1 } };
  await assert.rejects(runJob(job, core, provider, settings), /INCOMPLETE/);
  assert.equal(supplierCalls, 1);
  assert.ok(failed.size >= 3, `expected trace/outbox/completion ENOSPC, saw ${failed.size}`);
  assert.ok(events.some(([endpoint, payload]) => endpoint === 'prompt-call-events' && payload.outcome === 'UNKNOWN'));
  assert.ok(events.some(([endpoint]) => endpoint === 'completion-events'));
  assert.equal(journal.pendingCoreEvents().length, 0);
});

test('DISPATCHED 持久化消耗 emergency 后持续 ENOSPC，必须以 NOT_SENT 在首次供应商外呼前失败', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'pre-dispatch-enospc-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  const originalAllocate = journal.allocateCapacityFile.bind(journal); let dispatchedWriteFailed = false;
  journal.allocateCapacityFile = (path, bytes) => {
    if (dispatchedWriteFailed && path.includes('emergency-')) { const error = Error('still full'); error.code = 'ENOSPC'; throw error; }
    return originalAllocate(path, bytes);
  };
  const originalPersist = journal.persistAtomic.bind(journal);
  journal.persistAtomic = (temp, path, data) => {
    if (!dispatchedWriteFailed && data.includes('"state":"DISPATCHED"')) {
      dispatchedWriteFailed = true;
      const error = Error('full'); error.code = 'ENOSPC'; throw error;
    }
    return originalPersist(temp, path, data);
  };
  let supplierCalls = 0;
  const provider = new Provider({ base: 'https://supplier.invalid', key: 'fixture', model: 'fixture', temperature: 0,
    capacityGuard: true }, journal, async () => { supplierCalls++; return Response.json({ choices: [{ message: { content: 'forbidden' } }] }); });
  const money = [], sequence = [];
  const billing = { reserve: async () => { money.push('reserve'); sequence.push('reserve'); },
    dispatch: async () => { money.push('dispatch'); sequence.push('dispatch'); },
    confirm: async () => money.push('confirm'), unknown: async () => money.push('unknown'),
    notSent: async () => { money.push('not-sent'); sequence.push('not-sent'); } };
  await assert.rejects(provider.plan({ jobId: '995', attemptNo: 1, fencingToken: 1, phase: 'FLAT', payload: { requestedCount: 1 } },
    { phase: 'FLAT', requirements: {} }, [], new AbortController().signal, billing,
    async trace => { sequence.push(`trace:${trace.state}`); if (trace.state === 'NOT_SENT') throw Error('terminal acknowledgement lost'); }), /JOURNAL_CAPACITY/);
  assert.equal(supplierCalls, 0);
  assert.deepEqual(money, ['reserve', 'not-sent']);
  assert.deepEqual(sequence, ['trace:REQUESTED', 'reserve', 'trace:DISPATCHED', 'trace:NOT_SENT', 'not-sent']);
  assert.equal(journal.read('trace-plan-995').value.state, 'NOT_SENT');
  const replayed = [];
  await new Provider({ model: 'changed' }, new Journal(dir), async () => { supplierCalls++; throw Error('forbidden'); })
    .reconcileTraces({ async job(_identity, endpoint, payload) { replayed.push([endpoint, payload.state]); return 'RECORDED'; } });
  assert.deepEqual(replayed, [['call-traces/reconciliations', 'NOT_SENT']]);
  assert.equal(supplierCalls, 0);
});

test('图像 DISPATCHED 持久化后容量补齐失败仍是 NOT_SENT，重启不得补发供应商请求', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'image-pre-dispatch-enospc-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  const originalAllocate = journal.allocateCapacityFile.bind(journal); let dispatchedWriteFailed = false;
  journal.allocateCapacityFile = (path, bytes) => {
    if (dispatchedWriteFailed && path.includes('emergency-')) { const error = Error('still full'); error.code = 'ENOSPC'; throw error; }
    return originalAllocate(path, bytes);
  };
  const originalPersist = journal.persistAtomic.bind(journal);
  journal.persistAtomic = (temp, path, data) => {
    if (!dispatchedWriteFailed && data.includes('"state":"DISPATCHED"')) {
      dispatchedWriteFailed = true;
      const error = Error('full'); error.code = 'ENOSPC'; throw error;
    }
    return originalPersist(temp, path, data);
  };
  let supplierCalls = 0;
  const provider = new Provider({ base: 'https://supplier.invalid', key: 'fixture', imageModel: 'fixture',
    imageSizeFlat: '1024x1536', capacityGuard: true }, journal,
  async () => { supplierCalls++; return Response.json({ data: [] }); });
  await assert.rejects(provider.generate({ jobId: '997', attemptNo: 2, fencingToken: 4, phase: 'FLAT', payload: { requestedCount: 1 } },
    1, 'prompt', [], new AbortController().signal, undefined, '',
    async trace => { if (trace.state === 'NOT_SENT') throw Error('terminal acknowledgement lost'); }), /JOURNAL_CAPACITY/);
  assert.equal(supplierCalls, 0);
  assert.equal(journal.read('trace-image-997-1').value.state, 'NOT_SENT');
  const replayed = [];
  await new Provider({ model: 'changed' }, new Journal(dir), async () => { supplierCalls++; throw Error('forbidden'); })
    .reconcileTraces({ async job(_identity, endpoint, payload) { replayed.push([endpoint, payload.state]); return 'RECORDED'; } });
  assert.deepEqual(replayed, [['call-traces/reconciliations', 'NOT_SENT']]);
  assert.equal(supplierCalls, 0);
});

test('主模型503后每次fallback前重新补齐emergency，补齐失败不得发第二次供应商请求', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'fallback-capacity-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const journal = new Journal(dir, { capacityProofVerifier: () => true });
  let supplierCalls = 0;
  const provider = new Provider({ base: 'https://supplier.invalid', key: 'fixture', imageModel: 'primary',
    imageModelFallback: 'fallback', imageSizeFlat: '1024x1536', capacityGuard: true }, journal, async () => {
    supplierCalls++;
    if (supplierCalls === 1) {
      for (let index = 1; index <= 3; index++) journal.removeFile(join(dir, `emergency-${index}.capacity`));
      journal.allocateCapacityFile = () => { const error = Error('still full'); error.code = 'ENOSPC'; throw error; };
      return new Response('{}', { status: 503 });
    }
    return Response.json({ data: [] });
  });
  await assert.rejects(provider.generate({ jobId: '996', phase: 'FLAT', payload: { requestedCount: 1 } }, 1,
    'prompt', [], new AbortController().signal), /JOURNAL_CAPACITY/);
  assert.equal(supplierCalls, 1);
});
