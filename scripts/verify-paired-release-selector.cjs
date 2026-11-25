const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { spawnSync } = require('node:child_process');
const { marker, selectPair } = require('./resolve-paired-release.cjs');
const FRONT = 'a'.repeat(40), BACK = 'b'.repeat(40), OVERRIDE = 'c'.repeat(40), MERGE = 'd'.repeat(40);
const pr = body => ({ pull_request: { head: { sha: FRONT }, body } });

test('PR metadata selects one exact backend commit and forces the external handover gate', () => {
  const result = selectPair('pull_request', pr('业务说明\r\n\r\nPaired-Backend-SHA: ' + BACK + '\r\n'), MERGE);
  assert.deepEqual(result, { frontend_sha: FRONT, backend_sha: BACK, backend_source: 'pr-marker', require_external_handover: true });
  assert.equal(marker('  Paired-Backend-SHA:\t' + BACK + '  '), BACK);
});

test('ordinary historical PRs and pushes default to backend-main without interpreting their own frontend SHA as an external requirement', () => {
  for (const body of [null, '', '普通说明，没有固定配对信息']) {
    const result = selectPair('pull_request', pr(body), MERGE);
    assert.equal(result.frontend_sha, FRONT); assert.equal(result.backend_sha, 'backend-main'); assert.equal(result.require_external_handover, false);
  }
  assert.deepEqual(selectPair('push', {}, FRONT), { frontend_sha: FRONT, backend_sha: 'backend-main', backend_source: 'backend-main', require_external_handover: false });
});

test('bad, duplicate and executable-looking metadata fails closed instead of falling back to backend-main', () => {
  for (const body of [
    'Paired-Backend-SHA: ' + BACK + '\nPaired-Backend-SHA: ' + BACK,
    'Paired-Backend-SHA: ' + BACK + '\nPaired-Backend-SHA: ' + OVERRIDE,
    'Paired-Backend-SHA: ' + BACK.toUpperCase(), 'Paired-Backend-SHA: main',
    'Paired-Backend-SHA: ' + BACK.slice(1), 'Paired-Backend-SHA: ' + BACK + '0',
    'Paired-Backend-SHA: ' + BACK + ' # explanation', 'paired-backend-sha: ' + BACK,
    'Paired-Backend-SHA: $(touch /tmp/should-not-execute)', 'Paired-Backend-SHA: ${{ secrets.ANYTHING }}',
    'Paired-Backend-SHA: ' + BACK + '\nMention Paired-Backend-SHA again', '`Paired-Backend-SHA: ' + BACK + '`'
  ]) assert.throws(() => selectPair('pull_request', pr(body), MERGE), /exactly one standalone/);
});

test('dispatch inputs win selection only after metadata validates, and an explicit backend SHA cannot downgrade the external gate', () => {
  const event = { ...pr('Paired-Backend-SHA: ' + BACK), inputs: { frontend_sha: OVERRIDE, backend_sha: FRONT, require_external_handover: false } };
  assert.deepEqual(selectPair('workflow_dispatch', event, MERGE), { frontend_sha: OVERRIDE, backend_sha: FRONT, backend_source: 'workflow-input', require_external_handover: true });
  assert.throws(() => selectPair('workflow_dispatch', { ...event, pull_request: { body: 'Paired-Backend-SHA: invalid' } }, MERGE));
  assert.equal(selectPair('workflow_dispatch', { inputs: { backend_sha: BACK, require_external_handover: 'false' } }, FRONT).require_external_handover, true);
  assert.equal(selectPair('workflow_dispatch', { ...event, inputs: { backend_sha: 'backend-main', require_external_handover: false } }, MERGE).require_external_handover, true);
});

test('manual backend-main retains the legacy default and permits an explicit true gate without implicit frontend forcing', () => {
  assert.equal(selectPair('workflow_dispatch', { inputs: { frontend_sha: FRONT, backend_sha: 'backend-main' } }, MERGE).require_external_handover, false);
  for (const value of [true, 'true']) assert.equal(selectPair('workflow_dispatch', { inputs: { backend_sha: 'backend-main', require_external_handover: value } }, FRONT).require_external_handover, true);
  for (const value of ['TRUE', 'yes', 1, {}, null]) assert.throws(() => selectPair('workflow_dispatch', { inputs: { require_external_handover: value } }, FRONT), /must be true or false/);
  assert.equal(selectPair('pull_request', { ...pr(''), inputs: { backend_sha: BACK, require_external_handover: true } }, MERGE).require_external_handover, false);
});

test('frontend and backend inputs reject arbitrary refs, unsafe types and output injection', () => {
  for (const key of ['frontend_sha', 'backend_sha']) for (const value of ['main', '-bad-ref', FRONT + '\nbackend_sha=evil', ' ' + FRONT, FRONT.toUpperCase(), 42, 0, true, false, null, {}]) {
    assert.throws(() => selectPair('workflow_dispatch', { inputs: { [key]: value } }, FRONT), /40-character lowercase commit/);
  }
  assert.throws(() => selectPair('pull_request', { pull_request: { body: '' } }, FRONT));
  assert.throws(() => selectPair('pull_request_target', pr(''), FRONT));
  assert.throws(() => selectPair('workflow_dispatch', { inputs: null }, FRONT));
});

test('the CLI reads JSON through the runner path, writes only validated outputs, and emits nothing on invalid metadata', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'paired-selection-'));
  try {
    const event = path.join(dir, 'event.json'), output = path.join(dir, 'output'), summary = path.join(dir, 'summary');
    fs.writeFileSync(event, JSON.stringify(pr('需求内容包含 $(untrusted text)\nPaired-Backend-SHA: ' + BACK)));
    const env = { ...process.env, GITHUB_EVENT_NAME: 'pull_request', GITHUB_EVENT_PATH: event, GITHUB_SHA: MERGE, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary };
    const run = () => spawnSync(process.execPath, [path.join(__dirname, 'resolve-paired-release.cjs')], { env, encoding: 'utf8' });
    const ok = run(); assert.equal(ok.status, 0, ok.stderr); assert.equal(ok.stdout, '');
    assert.equal(fs.readFileSync(output, 'utf8'), 'frontend_sha=' + FRONT + '\nbackend_sha=' + BACK + '\nbackend_source=pr-marker\nrequire_external_handover=true\n');
    assert.match(fs.readFileSync(summary, 'utf8'), /missing capabilities must fail/);
    fs.writeFileSync(event, JSON.stringify(pr('Paired-Backend-SHA: ' + BACK + '\nPaired-Backend-SHA: bad')));
    fs.writeFileSync(output, ''); const bad = run(); assert.equal(bad.status, 1); assert.match(bad.stderr, /exactly one standalone/); assert.equal(fs.readFileSync(output, 'utf8'), '');
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('workflow binds validated outputs, rechecks edited metadata, and separates the immutable executor from the business backend', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../.github/workflows/paired-release.yml'), 'utf8');
  const executor = /uses: phlong026\/zhongshu-ai-platform-v3\/\.github\/workflows\/paired-release.yml@([0-9a-f]{40})/.exec(workflow);
  assert.ok(executor); assert.match(workflow, new RegExp('executor_sha: ' + executor[1] + '(?:\\r?\\n|$)'));
  assert.match(workflow, /types: \[opened, synchronize, reopened, edited\]/);
  assert.match(workflow, /needs: resolve-pair/); assert.match(workflow, /backend_sha: \$\{\{ needs\.resolve-pair\.outputs\.backend_sha \}\}/);
  assert.match(workflow, /require_external_handover: \$\{\{ needs\.resolve-pair\.outputs\.require_external_handover == 'true' \}\}/);
  assert.match(workflow, /run: node --test scripts\/verify-paired-release-selector\.cjs/);
  assert.match(workflow, /run: node scripts\/resolve-paired-release\.cjs/);
  assert.equal(workflow.includes('github.event.pull_request.body'), false);
  assert.equal(/run:[\s\S]*\$\{\{\s*(?:inputs\.|github.event.pull_request.body)/.test(workflow), false);
});
