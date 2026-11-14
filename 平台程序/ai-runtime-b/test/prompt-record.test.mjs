import test from 'node:test';
import assert from 'node:assert/strict';
import { runJob, sha } from '../src/runtime.mjs';

// 需求四二期：plan 阶段成功后，把最终提示词与所用模型随计费成功事件回传，供后台详情复盘。
const job = { jobId: '9007199254740993', attemptNo: 1, fencingToken: 2, phase: 'FLAT', payload: { requestedCount: 1 } };
const durableJournal = { enqueueCoreEvent() {}, acknowledgeCoreEvent() {} };

function run({ settings = { providerCode: 'apilio', model: 'text-model-fixture' }, prompt = '两层别墅，白墙灰瓦', confirmFailures = 0 } = {}) {
  const calls = [];
  let failuresLeft = confirmFailures;
  const core = { async job(task, endpoint, payload) {
    calls.push([endpoint, payload]);
    if (endpoint === 'inputs') return { schemaVersion: 1, phase: task.phase, images: [], requirements: { width: 10 } };
    if (endpoint === 'output-tickets') return { objectKey: 'quarantine/' + payload.slot, uploadUrl: 'https://storage.example/upload' };
    if (endpoint === 'result-events') return 'QUARANTINED';
    if (endpoint === 'prompt-call-events' && payload.outcome === 'SUCCEEDED' && failuresLeft-- > 0) throw Error('network');
    return true;
  } };
  const provider = { journal: durableJournal,
    imageInput() { throw Error('unexpected'); },
    async plan(task, input, images, signal, billing) {
      await billing.reserve(); await billing.dispatch(); await billing.confirm(prompt); return prompt;
    },
    upload: async () => {},
    generate: async () => ({ sha256: 'digest', sizeBytes: 10, mimeType: 'image/png' })
  };
  return runJob(job, core, provider, settings).then(() => calls.filter(x => x[0] === 'prompt-call-events').map(x => x[1]));
}

test('成功事件带上最终提示词与模型名，哈希口径不变', async () => {
  const events = await run();
  const succeeded = events.find(e => e.outcome === 'SUCCEEDED');
  assert.equal(succeeded.promptText, '两层别墅，白墙灰瓦');
  assert.equal(succeeded.model, 'text-model-fixture');
  assert.equal(succeeded.responseHash, sha('两层别墅，白墙灰瓦'));
  assert.equal(succeeded.callId, 'plan-9007199254740993');
});

test('只有成功事件携带提示词，已发出/未知等状态事件不带', async () => {
  const events = await run();
  const others = events.filter(e => e.outcome !== 'SUCCEEDED');
  assert.ok(others.length >= 1);
  for (const event of others) {
    assert.equal(Object.hasOwn(event, 'promptText'), false, event.outcome);
    assert.equal(Object.hasOwn(event, 'model'), false, event.outcome);
  }
});

test('确认事件网络失败重试时每次都带同样的提示词', async () => {
  const events = await run({ confirmFailures: 2 });
  const succeeded = events.filter(e => e.outcome === 'SUCCEEDED');
  assert.equal(succeeded.length, 3);
  for (const event of succeeded) assert.equal(event.promptText, '两层别墅，白墙灰瓦');
});

test('未配置模型名时不发送 model 字段，而不是发送 undefined', async () => {
  const events = await run({ settings: { providerCode: 'apilio' } });
  const succeeded = events.find(e => e.outcome === 'SUCCEEDED');
  assert.equal(Object.hasOwn(succeeded, 'model'), false);
  assert.equal(succeeded.promptText, '两层别墅，白墙灰瓦');
});
