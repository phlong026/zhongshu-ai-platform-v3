import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtempSync, rmSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import sharp from 'sharp';
import { Core, Provider, Journal, runJob, runTraceReconciler, sha } from '../src/runtime.mjs';

const settings = { base: 'https://supplier.invalid/v1', key: 'fixture-token', model: 'original-text-model',
  temperature: 0, imageModel2K: 'original-image-model', imageSizeFlat: '1536x1024', imageSizeElevation: '1536x1024',
  imageQuality: 'high', providerCode: 'fixture', secret: 'trace-reconciliation-fixture' };
const job = { jobId: '9007199254740993', attemptNo: 1, fencingToken: 3, phase: 'FLAT', payload: { requestedCount: 2 } };
const response = value => new Response(JSON.stringify(value), { status: 200 });
const signal = () => new AbortController().signal;
function journal(t) {
  const directory = mkdtempSync(join(tmpdir(), 'trace-reconciliation-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return new Journal(directory, 30);
}
function trace(id = '1', state = 'DISPATCHED') {
  return { callId: 'plan-' + id, sourceAttemptNo: 1, sourceFencingToken: 3, stage: 'PLAN', slot: 0, state,
    request: { promptVersion: 'original-version', model: 'original-text-model', endpoint: '/chat/completions',
      systemPrompt: 'fixture system', promptText: 'original private requirement', temperature: 0, maxTokens: 1800, references: [] },
    response: null, errorCode: null };
}
function muteErrors(t) {
  const lines = [], original = console.error;
  console.error = value => lines.push(value);
  t.after(() => { console.error = original; });
  return lines;
}

test('HTTP trace failure followed by terminal job and a fresh Node process restores all original evidence without another paid call', { timeout: 15000 }, async t => {
  const record = journal(t), logs = muteErrors(t), traces = new Map(), requests = [], errors = [];
  const billing = { imageDebit: 10, promptReservations: 0, promptConfirmations: 0, promptCharge: 0 };
  let terminal = false, supplierCalls = 0, traceFailures = 0;
  const image = await sharp({ create: { width: 13, height: 7, channels: 3, background: '#cccccc' } }).png().toBuffer();
  const server = createServer((req, res) => {
    void (async () => {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks).toString('utf8'), value = JSON.parse(body);
      const timestamp = req.headers['x-zs-timestamp'];
      const expected = createHmac('sha256', settings.secret).update(timestamp + '\nPOST\n' + req.url + '\n' + sha(body)).digest('hex');
      assert.equal(req.headers['x-zs-signature'], expected, 'every replay is signed over the exact body and path');
      const prefix = '/internal-api/design/v1/ai-jobs/' + job.jobId + '/';
      assert.ok(req.url.startsWith(prefix));
      const type = req.url.slice(prefix.length); requests.push(type);
      let data = true;
      if (type === 'inputs') data = { schemaVersion: 1, phase: 'FLAT', images: [],
        requirements: { floorCount: 2, note: '一层老人房，二层书房' }, imageOptions: { resolution: '2K', orientation: 'LANDSCAPE' } };
      else if (type === 'call-traces') {
        if (value.state === 'SUCCEEDED') {
          traceFailures++;
          res.writeHead(503, { 'Content-Type': 'application/json' });
          res.end('{"error":"fixture private request must never reach logs"}'); return;
        }
        traces.set(value.callId, structuredClone(value));
      } else if (type === 'call-traces/reconciliations') {
        assert.equal(terminal, true, 'job has already reached its terminal receipt');
        const original = traces.get(value.callId);
        assert.ok(original);
        assert.deepEqual(value.request, original.request);
        assert.equal(value.attemptNo, original.sourceAttemptNo);
        assert.equal(value.fencingToken, original.sourceFencingToken);
        assert.equal(value.state, 'SUCCEEDED');
        traces.set(value.callId, structuredClone(value)); data = 'RECORDED';
      } else if (type === 'prompt-calls') billing.promptReservations++;
      else if (type === 'prompt-call-events') {
        if (value.outcome === 'SUCCEEDED') {
          assert.equal(value.responseHash, sha('原模型已经返回的计划'));
          billing.promptConfirmations++; billing.promptCharge += 2;
        }
      } else if (type === 'output-tickets') data = { objectKey: 'slot-' + value.slot, uploadUrl: 'fixture-unused' };
      else if (type === 'result-events') data = 'QUARANTINED';
      else if (type === 'completion-events') terminal = true;
      else assert.ok(['progress-events', 'lease-renewals'].includes(type), 'no claims or new business command during replay');
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: 0, data }));
    })().catch(error => { errors.push(error); res.writeHead(500); res.end('fixture failure'); });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => server.close());
  const coreSettings = { ...settings, core: 'http://127.0.0.1:' + server.address().port };
  const provider = new Provider(settings, record, async url => {
    supplierCalls++;
    return String(url).endsWith('/chat/completions')
      ? response({ choices: [{ message: { content: '原模型已经返回的计划' } }] })
      : response({ data: [{ b64_json: image.toString('base64') }] });
  });
  provider.upload = async () => {};
  assert.equal(await runJob(job, new Core(coreSettings), provider, settings), 'COMPLETED');
  assert.equal(terminal, true); assert.equal(supplierCalls, 3); assert.equal(traceFailures, 9);
  assert.equal(record.pendingTraceCalls().length, 3);
  assert.deepEqual(billing, { imageDebit: 10, promptReservations: 1, promptConfirmations: 1, promptCharge: 2 });
  const beforeBilling = structuredClone(billing), beforeRequests = requests.length;
  const durableBefore = Object.fromEntries(readdirSync(record.directory).filter(name => !name.startsWith('trace-'))
    .map(name => [name, sha(readFileSync(join(record.directory, name)))]));
  // A genuinely fresh process sees only the durable journal, with deliberately different current settings.
  const moduleUrl = new URL('../src/runtime.mjs', import.meta.url).href;
  const script = [
    'import { Core, Provider, Journal } from ' + JSON.stringify(moduleUrl) + ';',
    'const settings = ' + JSON.stringify({ ...coreSettings, model: 'changed-model', imageModel2K: 'changed-image-model' }) + ';',
    'let providerCalls = 0;',
    'const journal = new Journal(' + JSON.stringify(record.directory) + ', 30);',
    'const provider = new Provider(settings, journal, async () => { providerCalls++; throw Error("PAID_REPLAY_FORBIDDEN"); });',
    'const result = await provider.reconcileTraces(new Core(settings));',
    'process.stdout.write(JSON.stringify({ result, providerCalls, pending: journal.pendingTraceCalls().length }));'
  ].join('\n');
  const child = spawn(process.execPath, ['--input-type=module', '-e', script], { stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null) child.kill(); });
  let stdout = '', stderr = ''; child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
  const [exitCode] = await once(child, 'close');
  assert.equal(exitCode, 0, stderr);
  assert.deepEqual(JSON.parse(stdout), { result: { attempted: 3, recorded: 3, discarded: 0 }, providerCalls: 0, pending: 0 });
  assert.deepEqual(requests.slice(beforeRequests), Array(3).fill('call-traces/reconciliations'));
  assert.deepEqual(billing, beforeBilling); assert.equal(supplierCalls, 3);
  assert.deepEqual(Object.fromEntries(readdirSync(record.directory).filter(name => !name.startsWith('trace-'))
    .map(name => [name, sha(readFileSync(join(record.directory, name)))])), durableBefore);
  assert.equal(traces.get('plan-' + job.jobId).response.promptText, '原模型已经返回的计划');
  for (const value of traces.values()) {
    assert.equal(value.state, 'SUCCEEDED');
    assert.ok(value.request.model.startsWith('original-'));
  }
  assert.equal(errors.length, 0, errors[0]?.message);
  assert.doesNotMatch(logs.join('\n'), /fixture private request|original private requirement|fixture-token/);
});

test('the idle reconciliation loop retries an HTTP failure without waiting for another claimed job', { timeout: 5000 }, async t => {
  const record = journal(t), logs = muteErrors(t), original = trace();
  record.write('trace-plan-1', { value: original });
  record.write('plan-1', { state: 'received', value: 'durable original result' });
  let calls = 0, providerCalls = 0;
  const provider = new Provider({ ...settings, model: 'changed-model' }, record, async () => { providerCalls++; throw Error('paid replay'); });
  const stop = new AbortController(), received = [];
  const core = { async job(identity, endpoint, value) {
    calls++; received.push(structuredClone(value));
    assert.equal(endpoint, 'call-traces/reconciliations');
    assert.deepEqual(identity, { jobId: '1', attemptNo: 1, fencingToken: 3 });
    if (calls === 1) throw Error('private-marker signed-url-marker');
    stop.abort(); return 'RECORDED';
  } };
  await runTraceReconciler(core, provider, { signal: stop.signal, intervalMs: 5 });
  assert.equal(calls, 2); assert.equal(providerCalls, 0);
  assert.equal(received[1].response.promptText, 'durable original result');
  assert.deepEqual(received[1].request, original.request);
  assert.deepEqual(received[1], received[0]); assert.equal(record.pendingTraceCalls().length, 0);
  assert.doesNotMatch(logs.join('\n'), /private-marker|signed-url-marker/);
});

test('bounded batches rotate past a live-source RETRY and clear a server-confirmed deletion without recreating it', async t => {
  const record = journal(t);
  for (const id of ['1', '2', '3']) record.write('trace-plan-' + id, { value: trace(id) });
  const provider = new Provider(settings, record, async () => { throw Error('paid replay'); }), ids = [];
  const core = { async job(identity, endpoint, value) {
    ids.push(identity.jobId); assert.equal(endpoint, 'call-traces/reconciliations');
    assert.equal(value.state, 'UNKNOWN');
    return identity.jobId === '1' ? 'RETRY' : identity.jobId === '2' ? 'DISCARDED' : 'RECORDED';
  } };
  assert.deepEqual(await provider.reconcileTraces(core, { maxRecords: 1 }), { attempted: 1, recorded: 0, discarded: 0 });
  assert.deepEqual(await provider.reconcileTraces(core, { maxRecords: 1 }), { attempted: 1, recorded: 0, discarded: 1 });
  assert.deepEqual(await provider.reconcileTraces(core, { maxRecords: 1 }), { attempted: 1, recorded: 1, discarded: 0 });
  assert.deepEqual(ids, ['1', '2', '3']); assert.deepEqual(record.pendingTraceCalls(), ['plan-1']);
  await assert.rejects(provider.reconcileTraces(core, { maxRecords: 0 }), /TRACE_BATCH_LIMIT/);
});

test('an actively dispatched request is not mistaken for a crashed operation by the background loop', async t => {
  const record = journal(t);
  let enter, finish, providerCalls = 0, reconciliations = 0;
  const entered = new Promise(resolve => { enter = resolve; }), waiting = new Promise(resolve => { finish = resolve; });
  const provider = new Provider(settings, record, async () => { providerCalls++; enter(); return waiting; });
  const generating = provider.plan(job, { phase: 'FLAT', requirements: { floorCount: 2 } }, [], signal(), undefined, async () => {});
  await entered;
  assert.equal(record.read('trace-plan-' + job.jobId).value.state, 'DISPATCHED');
  const result = await provider.reconcileTraces({ async job() { reconciliations++; return 'RECORDED'; } });
  assert.deepEqual(result, { attempted: 0, recorded: 0, discarded: 0 }); assert.equal(reconciliations, 0);
  finish(response({ choices: [{ message: { content: 'actual response' } }] }));
  assert.equal(await generating, 'actual response'); assert.equal(providerCalls, 1);
  assert.equal(record.pendingTraceCalls().length, 0);
});

test('an acknowledgement for older metadata cannot delete a newer received response', async t => {
  const record = journal(t), old = { ...trace(), state: 'UNKNOWN', errorCode: 'PROVIDER_OUTCOME_UNCERTAIN' };
  record.write('trace-plan-1', { value: old });
  const provider = new Provider(settings, record, async () => { throw Error('paid replay'); });
  let release, entered;
  const started = new Promise(resolve => { entered = resolve; }), ack = new Promise(resolve => { release = resolve; });
  const pending = provider.reconcileTraces({ async job() { entered(); return ack; } });
  await started;
  const latest = { ...old, state: 'SUCCEEDED', errorCode: null, response: { promptText: 'late durable response', sha256: sha('late durable response') } };
  record.write('trace-plan-1', { value: latest });
  release('RECORDED');
  assert.deepEqual(await pending, { attempted: 1, recorded: 0, discarded: 0 });
  assert.deepEqual(record.read('trace-plan-1').value, latest);
  await provider.reconcileTraces({ async job(_identity, _endpoint, value) { assert.deepEqual(value, latest); return 'RECORDED'; } });
  assert.equal(record.pendingTraceCalls().length, 0);
});

test('lost registration acknowledgement retains a NOT_SENT correction while preventing supplier and billing reservation', async t => {
  const record = journal(t); let providerCalls = 0, reservations = 0, original;
  const provider = new Provider(settings, record, async () => { providerCalls++; throw Error('paid replay'); });
  await assert.rejects(provider.plan(job, { phase: 'FLAT', requirements: {} }, [], signal(),
    { reserve: async () => { reservations++; }, notSent: async () => {} },
    async value => { original = structuredClone(value); throw Error('HTTP_503'); }), /HTTP_503/);
  assert.equal(providerCalls, 0); assert.equal(reservations, 0);
  const pending = record.read('trace-plan-' + job.jobId).value;
  assert.equal(pending.state, 'NOT_SENT'); assert.deepEqual(pending.request, original.request);
  await provider.reconcileTraces({ async job(_identity, endpoint, value) {
    assert.equal(endpoint, 'call-traces/reconciliations'); assert.equal(value.state, 'NOT_SENT'); return 'RECORDED';
  } });
  assert.equal(record.pendingTraceCalls().length, 0);
  assert.equal(providerCalls, 0); assert.equal(reservations, 0);
});

test('crash recovery distinguishes durable receipt, dispatched uncertainty and no supplier dispatch using only original journal data', async t => {
  const record = journal(t), values = [];
  record.write('trace-plan-1', { value: trace('1', 'REQUESTED') });
  record.write('trace-plan-2', { value: trace('2', 'DISPATCHED') });
  record.write('trace-plan-3', { value: trace('3', 'DISPATCHED') });
  record.write('plan-3', { state: 'received', value: 'persisted prompt' });
  const provider = new Provider({ ...settings, model: 'changed-model' }, record, async () => { throw Error('paid replay'); });
  await provider.reconcileTraces({ async job(_identity, _endpoint, value) { values.push(value); return 'RECORDED'; } });
  assert.deepEqual(values.map(value => value.state), ['NOT_SENT', 'UNKNOWN', 'SUCCEEDED']);
  assert.equal(values[0].errorCode, 'TRACE_NOT_DISPATCHED');
  assert.equal(values[1].errorCode, 'PROVIDER_OUTCOME_UNCERTAIN');
  assert.deepEqual(values[2].response, { promptText: 'persisted prompt', sha256: sha('persisted prompt') });
  assert.ok(values.every(value => value.request.model === 'original-text-model' && value.request.promptVersion === 'original-version'));
});
