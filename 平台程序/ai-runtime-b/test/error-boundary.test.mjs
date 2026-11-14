import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { bounded, MAX_ERROR_BODY_BYTES, runJob, safeFailureCode, sha } from '../src/runtime.mjs';
import { createInternalHandler, NOTE_POLISH_PATH } from '../src/internal-http.mjs';

const sensitive = 'fixture-private-input Authorization=Bearer fixture-sensitive-token https://storage.example/ref?signature=fixture-signature';

function streamingResponse({ status = 503, chunkBytes = 1024, length, stall = false } = {}) {
  const state = { pulls: 0, cancelled: false };
  const body = new ReadableStream({
    pull(controller) { state.pulls++; if (!stall) controller.enqueue(Buffer.alloc(chunkBytes, 65)); },
    cancel() { state.cancelled = true; }
  }, { highWaterMark: 0 });
  return { state, response: { ok: status >= 200 && status < 300, status,
    headers: new Headers(length === undefined ? {} : { 'content-length': String(length) }), body,
    text() { throw Error('Unbounded text() must never be called'); } } };
}

test('non-2xx missing-length infinite stream stops at the byte cap and cancels without text()', async () => {
  const { response, state } = streamingResponse();
  await assert.rejects(bounded(response, 20 * 1024 * 1024), { message: 'HTTP_503' });
  assert.equal(state.pulls, MAX_ERROR_BODY_BYTES / 1024 + 1);
  assert.equal(state.cancelled, true);
});

test('success and error share cumulative byte bounds, retaining the original HTTP status for errors', async () => {
  for (const status of [200, 429]) {
    const { response, state } = streamingResponse({ status, chunkBytes: 3 });
    await assert.rejects(bounded(response, 8), { message: status === 200 ? 'BODY_LIMIT' : 'HTTP_429' });
    assert.equal(state.pulls, 3);
    assert.equal(state.cancelled, true);
  }
});

test('oversized declared bodies are cancelled before reading, for both HTTP failure and success', async () => {
  for (const status of [200, 502]) {
    const { response, state } = streamingResponse({ status, length: 100 });
    await assert.rejects(bounded(response, 8), { message: status === 200 ? 'BODY_LIMIT' : 'HTTP_502' });
    assert.equal(state.pulls, 0);
    assert.equal(state.cancelled, true);
  }
});

test('small upstream error bodies are never included in errors or diagnostic codes', async () => {
  await assert.rejects(bounded(new Response(sensitive, { status: 400 }), 1024), error => {
    assert.equal(error.message, 'HTTP_400');
    assert.equal(safeFailureCode(error), 'HTTP_400');
    assert.doesNotMatch(JSON.stringify(error), /fixture-private|sensitive-token|signature=/);
    return true;
  });
});

test('successful chunks preserve the exact UTF-8 bytes and permit an empty response', async () => {
  const expected = Buffer.from('二层平面图');
  const body = new ReadableStream({ start(controller) {
    controller.enqueue(expected.subarray(0, 2)); controller.enqueue(expected.subarray(2)); controller.close();
  } });
  assert.deepEqual(await bounded(new Response(body), expected.length), expected);
  assert.deepEqual(await bounded(new Response(null, { status: 204 }), 1), Buffer.alloc(0));
});

test('a stalled read is cancelled promptly and preserves explicit abort or timeout classification', async () => {
  for (const name of ['AbortError', 'TimeoutError']) {
    const { response, state } = streamingResponse({ stall: true });
    const controller = new AbortController();
    const pending = bounded(response, 16, controller.signal);
    const reason = new DOMException(sensitive, name);
    controller.abort(reason);
    await assert.rejects(pending, error => {
      assert.equal(error, reason);
      assert.equal(safeFailureCode(error), name === 'TimeoutError' ? 'REQUEST_TIMEOUT' : 'REQUEST_ABORTED');
      return true;
    });
    assert.equal(state.cancelled, true);
  }
});

test('log code allowlist rejects arbitrary error messages and suffixes while retaining known diagnoses', () => {
  for (const message of [sensitive, 'HTTP_503 ' + sensitive, 'PROVIDER_' + sensitive, 'MISSING_FIXTURE_SECRET_VALUE']) {
    assert.equal(safeFailureCode(Error(message)), 'RUNTIME_ERROR');
  }
  for (const message of ['HTTP_429', 'CORE_1072000001', 'PROVIDER_OUTCOME_UNCERTAIN', 'BODY_LIMIT', 'MISSING_ZS_AI_API_KEY']) {
    assert.equal(safeFailureCode(Error(message)), message);
  }
});

test('slot and job failure logs exclude provider echoes and keep job and phase without replaying generation', async t => {
  const logs = []; t.mock.method(console, 'error', line => logs.push(JSON.parse(line)));
  const job = { jobId: '901', attemptNo: 1, fencingToken: 2, phase: 'FLAT', payload: { requestedCount: 1 } };
  let generations = 0;
  const core = { async job(_job, endpoint) {
    return endpoint === 'inputs' ? { schemaVersion: 1, phase: 'FLAT', requirements: {}, images: [] } : true;
  } };
  const provider = { journal: { enqueueCoreEvent() {}, acknowledgeCoreEvent() {} },
    plan: async () => 'plan', async generate() { generations++; throw Error(sensitive); } };
  await assert.rejects(runJob(job, core, provider, {}), /JOB_ATTEMPT_INCOMPLETE/);
  assert.equal(generations, 1);
  assert.equal(logs.length, 2);
  assert.equal(logs[0].reason, 'RUNTIME_ERROR');
  assert.equal(logs[0].jobId, '901'); assert.equal(logs[0].phase, 'FLAT');
  assert.doesNotMatch(JSON.stringify(logs), /fixture-private|sensitive-token|signature=/);
});

test('note-polish provider failures also use the same safe diagnostics', async () => {
  const secret = 'error-boundary-fixture';
  const body = Buffer.from(JSON.stringify({ receiptId: 'fixture-error-boundary', note: '老人房在一层', line: 'FLAT' }));
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac('sha256', secret).update(`${timestamp}\nPOST\n${NOTE_POLISH_PATH}\n${sha(body)}`).digest('hex');
  const logs = [];
  const handle = createInternalHandler({ settings: { secret }, journal: {},
    provider: { async polishNote() { throw Error(sensitive); } }, log: value => logs.push(value) });
  const result = await handle({ method: 'POST', path: NOTE_POLISH_PATH, body,
    headers: { 'x-zs-timestamp': timestamp, 'x-zs-signature': signature } });
  assert.equal(result.status, 502);
  assert.equal(logs[0].reason, 'RUNTIME_ERROR');
  assert.doesNotMatch(JSON.stringify([result, logs]), /fixture-private|sensitive-token|signature=/);
});
