#!/usr/bin/env node
// NON PRODUCTION. Real Core HTTP, runJob, journal, trace and local storage; only provider fetch is deterministic.
import { readFileSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

function requireLoopback(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.pathname !== '/'
      || url.username || url.password || url.search || url.hash) throw Error('LOCAL_CORE_REQUIRED');
  return url.origin;
}

async function main() {
  const [runtimeDirectory, privateConfig] = process.argv.slice(2);
  if (!runtimeDirectory || !privateConfig) throw Error('ARGUMENTS_REQUIRED');
  if (statSync(privateConfig).mode & 0o077) throw Error('PRIVATE_CONFIG_PERMISSIONS');
  const input = JSON.parse(readFileSync(privateConfig, 'utf8'));
  if (input.fixture !== 'NON_PRODUCTION') throw Error('FIXTURE_MARKER_REQUIRED');
  const runtimeRoot = resolve(runtimeDirectory);
  const { Core, Provider, Journal, runJob, safeFailureCode } = await import(pathToFileURL(join(runtimeRoot, 'src/runtime.mjs')));
  const { createInternalHandler, createRequestListener } = await import(pathToFileURL(join(runtimeRoot, 'src/internal-http.mjs')));
  const require = createRequire(join(runtimeRoot, 'package.json'));
  const sharp = require('sharp'); // Already used by ai-runtime and its tests; never install dependencies here.
  const settings = {
    core: requireLoopback(input.core), secret: readFileSync(input.secretFile, 'utf8').trim(),
    base: 'https://provider.invalid', key: randomUUID(), model: 'fixture-text', imageModel: 'fixture-image',
    temperature: 0, storageMode: 'local-fs', storageRoot: resolve(input.storageRoot), storageOrigins: [],
    journalPath: resolve(input.journalPath), dailyLimit: 100, notePolishDailyLimit: 100, requestTimeoutMs: 30000,
    imageSizeFlat: '1536x1024', imageSizeElevation: '1536x1024', providerCode: 'apilio',
    workerId: 'fixture-' + randomUUID()
  };
  const seconds = input.seconds ?? 180;
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 1800 || settings.secret.length < 32
      || !Number.isInteger(input.listenPort) || input.listenPort < 1024 || input.listenPort > 65535) throw Error('CONFIG_INVALID');
  const stop = new AbortController();
  for (const event of ['SIGINT', 'SIGTERM']) process.once(event, () => stop.abort());
  // The real Core implementation owns signing and bounded HTTP. This fence rejects accidental nonlocal calls.
  const core = new Core(settings, (url, init) => {
    if (new URL(url).origin !== settings.core) throw Error('NONLOCAL_CORE_DENIED');
    return fetch(url, init);
  });
  let providerCalls = 0;
  const journal = new Journal(settings.journalPath, settings.dailyLimit);
  const provider = new Provider(settings, journal, async (url, init) => {
    const parsed = new URL(url);
    if (parsed.origin !== settings.base || init.method !== 'POST') throw Error('PROVIDER_NETWORK_DENIED');
    init.signal?.throwIfAborted();
    providerCalls++;
    if (parsed.pathname === '/chat/completions') {
      return Response.json({ choices: [{ message: { content: '非生产测试平面示意图：两层，每层保留中文房间名称。' } }] });
    }
    if (parsed.pathname === '/images/generations') {
      const request = JSON.parse(init.body);
      const [width, height] = String(request.size).split('x').map(Number);
      if (![width, height].every(value => Number.isInteger(value) && value >= 256 && value <= 4096)) throw Error('FIXTURE_SIZE');
      const bytes = await sharp({ create: { width, height, channels: 3, background: '#e5e7eb' } }).png().toBuffer();
      return Response.json({ data: [{ b64_json: bytes.toString('base64') }] });
    }
    throw Error('UNSUPPORTED_FIXTURE_PROVIDER_OPERATION');
  });
  const internal = createInternalHandler({ settings, provider, journal,
    log: event => console.error(JSON.stringify({ event: event.event, reason: event.reason })) });
  const server = createServer(createRequestListener({ internal, healthy: () => !stop.signal.aborted, version: 'local-fixture' }));
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.setTimeout(15000, socket => socket.destroy());
  const deadline = setTimeout(() => stop.abort(), seconds * 1000);
  const startedAt = Date.now();
  let coreReady = false;
  try {
    await new Promise((done, fail) => {
      server.once('error', fail);
      server.listen(input.listenPort, '127.0.0.1', done);
    });
    console.error('{"event":"fixture_runtime_receipts_ready"}');
    while (!stop.signal.aborted) {
      let jobs;
      try {
        jobs = await core.post('/internal-api/design/v1/ai-jobs/claims', { workerId: settings.workerId, maxJobs: 1 }, stop.signal);
        coreReady = true;
      } catch (error) {
        if (coreReady || Date.now() - startedAt > 120000 || stop.signal.aborted) throw error;
        console.error(JSON.stringify({ event: 'fixture_runtime_waiting_for_A', reason: safeFailureCode(error) }));
        await new Promise(done => setTimeout(done, 1000));
        continue;
      }
      if (!Array.isArray(jobs) || jobs.length > 1) throw Error('CLAIM_CONTRACT');
      for (const job of jobs) {
        await runJob(job, core, provider, settings, { signal: stop.signal });
        console.error(JSON.stringify({ event: 'fixture_runtime_attempt_completed', jobId: job.jobId, provider: 'deterministic-test-only' }));
      }
      await provider.reconcileTraces(core, { signal: stop.signal, maxRecords: 20 });
      if (!stop.signal.aborted) await new Promise(done => {
        const finish = () => { clearTimeout(timer); stop.signal.removeEventListener('abort', finish); done(); };
        const timer = setTimeout(finish, 1000);
        stop.signal.addEventListener('abort', finish, { once: true });
      });
    }
  } catch (error) {
    if (!stop.signal.aborted) {
      console.error(JSON.stringify({ event: 'fixture_runtime_failed', reason: safeFailureCode(error) }));
      process.exitCode = 1;
    }
  } finally {
    clearTimeout(deadline);
    server.closeAllConnections();
    await new Promise(done => server.close(done));
    console.error(JSON.stringify({ event: 'fixture_runtime_stopped', providerCalls, actualModelCalls: 0, coreReady }));
  }
}

main().catch(() => {
  console.error('{"event":"fixture_runtime_setup_failed","details":"withheld"}');
  process.exitCode = 1;
});
