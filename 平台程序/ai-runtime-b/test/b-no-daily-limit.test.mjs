import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config, Journal, sha } from '../src/runtime.mjs';
import { createInternalHandler, NOTE_POLISH_PATH } from '../src/internal-http.mjs';

const SECRET = 'fixture-internal-secret-0123456789';

function runtimeEnv() {
  return {
    ZS_AI_STORAGE_ORIGINS: 'https://storage.example',
    ZS_AI_CORE_URL: 'http://core:48080',
    ZS_INTERNAL_SECRET: SECRET,
    ZS_AI_API_KEY: 'fixture-provider-key',
    ZS_AI_IMAGE_MODEL: 'fixture-image-model',
    ZS_AI_JOURNAL_DIR: '/tmp/fixture-journal',
    ZS_AI_JOURNAL_FS_PROOF_SHA256: 'a'.repeat(64)
  };
}

function signedRequest(note) {
  const body = Buffer.from(JSON.stringify({ receiptId: `fixture-${sha(note).slice(0, 24)}`, note, line: 'FLAT' }));
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac('sha256', SECRET)
    .update(`${timestamp}\nPOST\n${NOTE_POLISH_PATH}\n${sha(body)}`).digest('hex');
  return { method: 'POST', path: NOTE_POLISH_PATH, body,
    headers: { 'x-zs-timestamp': timestamp, 'x-zs-signature': signature } };
}

test('B 配置不要求、不读取每日调用上限', () => {
  const settings = config({ ...runtimeEnv(), ZS_AI_DAILY_CALL_LIMIT: '0', ZS_AI_NOTE_POLISH_DAILY_LIMIT: '0' });
  assert.equal('dailyLimit' in settings, false);
  assert.equal('notePolishDailyLimit' in settings, false);
});

test('B journal 无每日计数，但 completed 复用和 UNKNOWN 防重保持不变', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'zs-b-runtime-'));
  try {
    const first = new Journal(dir);
    let calls = 0;
    for (let index = 0; index < 20; index++) {
      assert.equal(await first.once(`call-${index}`, async () => { calls++; return index; }), index);
    }
    assert.equal(await new Journal(dir).once('call-0', async () => { calls++; }), 0);
    await assert.rejects(first.once('uncertain', async () => { calls++; throw Error('fixture provider response lost'); }));
    await assert.rejects(new Journal(dir).once('uncertain', async () => { calls++; }), /OUTCOME_UNCERTAIN/);
    assert.equal(calls, 21);
    assert.equal(readdirSync(dir).some(name => /quota|reserved/.test(name)), false);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('润色只受短时并发保护，不受每日业务次数限制', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'zs-b-polish-'));
  try {
    let calls = 0;
    const handler = createInternalHandler({ settings: { secret: SECRET }, journal: new Journal(dir),
      provider: { polishNote: async note => { calls++; return `润色:${note}`; } }, maxConcurrent: 2 });
    for (let index = 0; index < 12; index++) {
      const result = await handler(signedRequest(`需求${index}`));
      assert.equal(result.status, 200);
    }
    assert.equal(calls, 12);
    assert.equal(readdirSync(dir).some(name => /quota|reserved/.test(name)), false);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
