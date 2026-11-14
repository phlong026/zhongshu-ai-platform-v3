import assert from 'node:assert/strict';
import { createServer as createHttpServer, request as httpRequest } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { closeSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import net from 'node:net';

const runtimeRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const platformRoot = resolve(runtimeRoot, '..');
const javaHome = '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home';
const java = join(javaHome, 'bin/java');
const localJavaOptions = ['-DsocksNonProxyHosts=localhost|127.*|[::1]', '-Dhttp.nonProxyHosts=localhost|127.*|[::1]'];
const jar = join(platformRoot, 'yudao-design-provider/target/yudao-design-provider.jar');
const secret = 'cross-process-runtime-secret-0123456789';
const capabilitySecret = Buffer.alloc(32, 7);
const png = Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010802000000907753de0000000c49444154789c63f8cfc0000003010100c9fe92ef0000000049454e44ae426082', 'hex');

const temp = mkdtempSync(join(tmpdir(), 'zs-runtime-provider-it-'));
const id = randomBytes(5).toString('hex');
const pgName = `zs-runtime-pg-${id}`, redisName = `zs-runtime-redis-${id}`;
const children = [], servers = [], logs = [];

function command(file, args, options = {}) {
  const result = spawnSync(file, args, { encoding: 'utf8', ...options });
  if (result.status !== 0) throw Error(`${file} failed (${result.status}): ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
async function port() {
  const server = net.createServer();
  await new Promise((resolveListen, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolveListen));
  const value = server.address().port;
  await new Promise(resolveClose => server.close(resolveClose));
  return value;
}
async function waitFor(check, label, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs; let last;
  while (Date.now() < deadline) {
    try { const value = await check(); if (value) return value; } catch (error) { last = error; }
    await delay(250);
  }
  throw Error(`${label} timeout${last ? `: ${last.message}` : ''}`);
}
function processWithLog(file, args, env, name) {
  const path = join(temp, `${name}.log`), fd = openSync(path, 'w'); logs.push([fd, path]);
  const child = spawn(file, args, { cwd: runtimeRoot, env: { ...process.env, ...env }, stdio: ['ignore', fd, fd] });
  children.push(child); return child;
}
function psql(sql, { tuples = false } = {}) {
  const args = ['exec', '-i', pgName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'provider'];
  if (tuples) args.push('-At');
  return command('docker', args, { input: sql });
}
async function signedRuntimePost(path, input) {
  const body = JSON.stringify(input), timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac('sha256', secret)
    .update(`${timestamp}\nPOST\n${path}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex');
  const response = await fetch(`http://127.0.0.1:8081${path}`, { method: 'POST', headers: {
    'content-type': 'application/json', 'x-zs-timestamp': timestamp, 'x-zs-signature': signature }, body });
  return { status: response.status, body: await response.json() };
}
async function signedCapabilityPost(providerPort, operation, requestKey, payload) {
  const path = `/internal-api/design/v1/platform/${operation.toLowerCase().replaceAll('_', '-')}`;
  const body = JSON.stringify({ callerAppId: 'platform-b-test', orgId: 1,
    actor: { actorUserId: 101, actorPostId: 201, actorRoleCode: 'EMPLOYEE' }, operation, requestKey,
    nonce: `nonce-${randomUUID()}`, payload });
  const timestamp = String(Math.floor(Date.now() / 1000));
  const signature = createHmac('sha256', capabilitySecret)
    .update(`${timestamp}\nPOST\n${path}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex');
  const response = await fetch(`http://127.0.0.1:${providerPort}${path}`, { method: 'POST', headers: {
    'content-type': 'application/json', 'x-zs-key-id': 'test',
    'x-zs-timestamp': timestamp, 'x-zs-signature': signature }, body });
  return { status: response.status, body: await response.json() };
}
async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  child.kill('SIGTERM');
  await Promise.race([new Promise(resolveExit => child.once('exit', resolveExit)), delay(5_000)]);
  if (child.exitCode === null) child.kill('SIGKILL');
}
async function cleanup() {
  for (const child of children.reverse()) await stopChild(child);
  for (const server of servers.reverse()) await new Promise(resolveClose => server.close(resolveClose));
  for (const [fd] of logs) { try { closeSync(fd); } catch { /* already closed */ } }
  spawnSync('docker', ['rm', '-f', pgName, redisName], { stdio: 'ignore' });
}

try {
  const [pgPort, redisPort, providerPort, proxyPort, supplierPort] = await Promise.all([port(), port(), port(), port(), port()]);
  command('docker', ['run', '-d', '--name', pgName, '-e', 'POSTGRES_PASSWORD=pg-test-password',
    '-e', 'POSTGRES_DB=provider', '-p', `127.0.0.1:${pgPort}:5432`, 'postgres:16.15-alpine']);
  command('docker', ['run', '-d', '--name', redisName, '-p', `127.0.0.1:${redisPort}:6379`,
    'redis:7.4.11-alpine', 'redis-server', '--requirepass', 'redis-test-password']);
  await waitFor(() => spawnSync('docker', ['exec', pgName, 'pg_isready', '-U', 'postgres', '-d', 'provider']).status === 0, 'postgres');
  await waitFor(() => spawnSync('docker', ['exec', redisName, 'redis-cli', '-a', 'redis-test-password', 'ping']).stdout?.toString().includes('PONG'), 'redis');

  execFileSync('./mvnw', ['-pl', 'yudao-design-provider', '-am', '-DskipTests', 'package'], {
    cwd: platformRoot, env: { ...process.env, JAVA_HOME: javaHome }, stdio: 'inherit' });
  const migrationEnv = { DP_MIGRATION_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/provider`,
    DP_MIGRATION_USERNAME: 'postgres', DP_MIGRATION_PASSWORD: 'pg-test-password' };
  execFileSync(java, [...localJavaOptions, '-jar', jar, 'migrate'], {
    env: { ...process.env, ...migrationEnv, NO_PROXY: 'localhost,127.0.0.1,::1' }, stdio: 'inherit' });

  psql(`
    INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES ('platform-b-test',1,1,true);
    INSERT INTO account(id,status) VALUES (8001,'ACTIVE');
    INSERT INTO design_project(id,user_id,source_type,stage) VALUES (7001,8001,'SELF_UPLOAD','FLAT');
    INSERT INTO platform_cost_price(product,version,unit,prompt_micros,image_micros,usage_micros) VALUES
      ('FLAT:2K',1,'MICRO_CNY',3,11,0),('FLAT:4K',1,'MICRO_CNY',3,11,0),
      ('ELEVATION:2K',1,'MICRO_CNY',3,11,0),('ELEVATION:4K',1,'MICRO_CNY',3,11,0),
      ('BUDGET_ESTIMATE',1,'MICRO_CNY',0,0,1),('NOTE_POLISH',1,'MICRO_CNY',0,0,1);
    INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,request_key,task_key,
      fingerprint,product,price_version,unit,prompt_micros,image_micros,usage_micros,requested_count,phase,project_ref,
      resolution,state,job_status,reserved_micros)
    VALUES ('cost-9001','platform-b-test',1,1,8001,'request-9001','svc:job-9001','fp-9001','FLAT:2K',1,'MICRO_CNY',3,11,0,1,'FLAT','7001','2K','EXECUTED','QUEUED',14),
           ('cost-9002','platform-b-test',1,1,8001,'request-9002','svc:job-9002','fp-9002','FLAT:2K',1,'MICRO_CNY',3,11,0,1,'FLAT','7001','2K','EXECUTED','QUEUED',14);
    INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix,unit_point_cost,total_point_cost,
      input_snapshot,prompt_pricing_version,request_resolution,billing_mode,cost_business_id,tenant_id)
    VALUES (9001,8001,'7001','FLAT','QUEUED',1,'ai-quarantine/9001',0,0,
      '{"phase":"FLAT","requirements":{},"assetIds":[]}'::jsonb,1,'2K','SERVICE_COST','cost-9001',1),
           (9002,8001,'7001','FLAT','QUEUED',1,'ai-quarantine/9002',0,0,
      '{"phase":"FLAT","requirements":{},"assetIds":[]}'::jsonb,1,'2K','SERVICE_COST','cost-9002',1);
    UPDATE platform_cost_business SET job_id=9001 WHERE id='cost-9001';
    UPDATE platform_cost_business SET job_id=9002 WHERE id='cost-9002';
  `);

  const cert = join(temp, 'supplier.crt'), key = join(temp, 'supplier.key');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=127.0.0.1',
    '-keyout', key, '-out', cert], { stdio: 'ignore' });
  const supplierCalls = [];
  const supplier = createHttpsServer({ key: readFileSync(key), cert: readFileSync(cert) }, (request, response) => {
    const chunks = []; request.on('data', chunk => chunks.push(chunk)); request.on('end', () => {
      supplierCalls.push(request.url);
      response.setHeader('content-type', 'application/json');
      if (request.url === '/v1/chat/completions') response.end(JSON.stringify({ choices: [{ message: { content: '现代乡墅平面提示词' } }] }));
      else if (request.url === '/v1/images/generations') response.end(JSON.stringify({ data: [{ b64_json: png.toString('base64') }] }));
      else { response.statusCode = 404; response.end('{}'); }
    });
  });
  await new Promise(resolveListen => supplier.listen(supplierPort, '127.0.0.1', resolveListen)); servers.push(supplier);

  let providerTargetPort = providerPort, droppedDispatchResponses = 0;
  const proxy = createHttpServer((incoming, outgoing) => {
    const chunks = []; incoming.on('data', chunk => chunks.push(chunk)); incoming.on('end', () => {
      const body = Buffer.concat(chunks), parsed = (() => { try { return JSON.parse(body); } catch { return {}; } })();
      const drop = incoming.url === '/internal-api/design/v1/ai-jobs/9002/prompt-call-events'
        && parsed.outcome === 'DISPATCHED';
      const forwarded = httpRequest({ hostname: '127.0.0.1', port: providerTargetPort, path: incoming.url,
        method: incoming.method, headers: incoming.headers }, response => {
        const responseChunks = []; response.on('data', chunk => responseChunks.push(chunk)); response.on('end', () => {
          if (drop) { droppedDispatchResponses++; outgoing.destroy(); return; }
          outgoing.writeHead(response.statusCode, response.headers); outgoing.end(Buffer.concat(responseChunks));
        });
      });
      forwarded.on('error', () => { if (!outgoing.destroyed) { outgoing.statusCode = 503; outgoing.end(); } });
      forwarded.end(body);
    });
  });
  await new Promise(resolveListen => proxy.listen(proxyPort, '127.0.0.1', resolveListen)); servers.push(proxy);

  const journal = join(temp, 'journal'), storage = join(temp, 'storage');
  writeFileSync(join(temp, 'placeholder'), 'fixture');
  mkdirSync(journal, { recursive: true });
  mkdirSync(storage, { recursive: true });
  const proofBytes = Buffer.from(JSON.stringify({ version: 1, filesystem: 'ext4', allocation: 'thick',
    exclusiveWriter: true, volumeId: `fixture-${id}` }));
  writeFileSync(join(journal, '.capacity-proof.json'), proofBytes, { mode: 0o600 });
  const proofSha = createHash('sha256').update(proofBytes).digest('hex');
  const runtimeEnv = { ZS_AI_STORAGE_MODE: 'local-fs', ZS_AI_STORAGE_ROOT: storage,
    ZS_AI_CORE_URL: `http://127.0.0.1:${proxyPort}`, ZS_INTERNAL_SECRET: secret,
    ZS_AI_API_KEY: 'fake-supplier-key', ZS_AI_BASE_URL: `https://127.0.0.1:${supplierPort}/v1`,
    ZS_AI_MODEL: 'fixture-text-model', ZS_AI_IMAGE_MODEL: 'fixture-image-model',
    ZS_AI_IMAGE_MODEL_FALLBACK: 'fixture-image-model', ZS_AI_JOURNAL_DIR: journal,
    ZS_AI_JOURNAL_FS_PROOF_SHA256: proofSha, NODE_TLS_REJECT_UNAUTHORIZED: '0' };
  let runtime = processWithLog(process.execPath, ['src/main.mjs'], runtimeEnv, 'runtime-1');
  await waitFor(async () => (await fetch('http://127.0.0.1:8081/health')).status === 200, 'runtime health');

  const providerEnv = { DP_SERVER_PORT: String(providerPort),
    DP_PG_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/provider`, DP_PG_USERNAME: 'postgres', DP_PG_PASSWORD: 'pg-test-password',
    DP_REDIS_HOST: '127.0.0.1', DP_REDIS_PORT: String(redisPort), DP_REDIS_USERNAME: 'default', DP_REDIS_PASSWORD: 'redis-test-password',
    DP_STORAGE_PROVIDER: 'local', DP_MODERATION_PROVIDER: 'stub', DP_DRIVER_ENABLED: 'true',
    DP_DRIVER_DELAY_MS: '500', DP_DRIVER_INITIAL_DELAY_MS: '500', DP_RUNTIME_SHARED_SECRET: secret };
  const provider = processWithLog(java, [...localJavaOptions, '-jar', jar,
    `--zhongshu.design.asset.storage-root=${storage}`,
    '--zhongshu.ai.note-polish.runtime-url=http://127.0.0.1:8081',
    '--zhongshu.ai.internal-replay-window=600',
    '--zhongshu.design.capability.keys.test.caller-app-id=platform-b-test',
    `--zhongshu.design.capability.keys.test.secret-base64=${capabilitySecret.toString('base64')}`,
    '--zhongshu.design.capability.keys.test.operations=LIST_PROJECTS,CREATE_PROJECT,NOTE_POLISH,QUERY_RECEIPT'],
  { ...providerEnv, NO_PROXY: 'localhost,127.0.0.1,::1' }, 'provider');
  await waitFor(async () => (await fetch(`http://127.0.0.1:${providerPort}/actuator/health`)).status === 200, 'provider health');

  const polishRequestKey = 'note-cross-process', polishPayload = { note: '客厅更温馨', line: 'FLAT' };
  const polish = await signedCapabilityPost(providerPort, 'NOTE_POLISH', polishRequestKey, polishPayload);
  assert.equal(polish.status, 200);
  assert.equal(polish.body.code, 0);
  assert.equal(polish.body.receipt.status, 'SUCCEEDED');
  const polishReplay = await signedCapabilityPost(providerPort, 'NOTE_POLISH', polishRequestKey, polishPayload);
  assert.equal(polishReplay.status, 200);
  assert.equal(polishReplay.body.replayed, true);
  assert.equal(polishReplay.body.result, polish.body.result);
  const providerReceipt = await signedCapabilityPost(providerPort, 'QUERY_RECEIPT', `query-${polishRequestKey}`,
    { operation: 'NOTE_POLISH', requestKey: polishRequestKey });
  assert.equal(providerReceipt.status, 200);
  assert.equal(providerReceipt.body.result.status, 'SUCCEEDED');
  assert.equal(providerReceipt.body.result.result, polish.body.result);
  assert.equal(supplierCalls.filter(path => path === '/v1/chat/completions').length, 1,
    'Provider replay and receipt lookup must not repeat the supplier call');
  assert.equal(psql("SELECT state||':'||charged_micros FROM platform_cost_business WHERE product='NOTE_POLISH'", { tuples: true }), 'EXECUTED:1');

  const unsigned = await fetch(`http://127.0.0.1:${providerPort}/internal-api/design/v1/ai-jobs/claims`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"workerId":"unsigned","maxJobs":1}' });
  assert.ok([401, 403].includes(unsigned.status), `unsigned runtime call unexpectedly returned ${unsigned.status}`);

  await waitFor(() => {
    const rows = psql("SELECT id||':'||status FROM ai_job WHERE id IN (9001,9002) ORDER BY id", { tuples: true });
    return rows.includes('9001:SUCCEEDED') && rows.includes('9002:FAILED');
  }, 'jobs terminal', 120_000);
  assert.equal(supplierCalls.filter(path => path === '/v1/chat/completions').length, 2);
  assert.equal(supplierCalls.filter(path => path === '/v1/images/generations').length, 1);
  assert.ok(droppedDispatchResponses >= 1);
  assert.equal(psql("SELECT outcome FROM design_provider_prompt_event_receipt WHERE job_id=9001 ORDER BY outcome", { tuples: true }), 'DISPATCHED\nSUCCEEDED');
  assert.equal(psql("SELECT outcome FROM design_provider_prompt_event_receipt WHERE job_id=9002 ORDER BY outcome", { tuples: true }), 'DISPATCHED\nUNKNOWN');
  assert.equal(psql("SELECT prompt_state FROM platform_cost_business WHERE job_id=9001", { tuples: true }), 'CHARGED');
  assert.equal(psql("SELECT prompt_state FROM platform_cost_business WHERE job_id=9002", { tuples: true }), 'UNKNOWN');

  const callsBeforeRestart = supplierCalls.length;
  await stopChild(runtime);
  runtime = processWithLog(process.execPath, ['src/main.mjs'], runtimeEnv, 'runtime-2');
  await waitFor(async () => (await fetch('http://127.0.0.1:8081/health')).status === 200, 'runtime restart');
  const runtimeReceiptId = psql("SELECT task_key FROM platform_cost_business WHERE product='NOTE_POLISH'", { tuples: true });
  const receiptAfterRestart = await signedRuntimePost('/internal/note-polish/receipt', { receiptId: runtimeReceiptId });
  assert.equal(receiptAfterRestart.status, 200);
  assert.equal(receiptAfterRestart.body.status, 'SUCCEEDED');
  assert.equal(receiptAfterRestart.body.polished, polish.body.result);
  await delay(7_000);
  assert.equal(supplierCalls.length, callsBeforeRestart, 'restart must not replay a paid supplier call');
  assert.equal(psql('SELECT count(*) FROM design_provider_prompt_event_receipt WHERE state<>\'APPLIED\'', { tuples: true }), '0');
  console.log(JSON.stringify({ result: 'PASS', supplierCalls: supplierCalls.length, droppedDispatchResponses,
    jobs: psql("SELECT id||':'||status FROM ai_job WHERE id IN (9001,9002) ORDER BY id", { tuples: true }).split('\n') }));
} catch (error) {
  for (const [, path] of logs) {
    try { process.stderr.write(`\n--- ${path} ---\n${readFileSync(path, 'utf8').slice(-12000)}\n`); } catch { /* missing */ }
  }
  throw error;
} finally {
  await cleanup();
  rmSync(temp, { recursive: true, force: true });
}
