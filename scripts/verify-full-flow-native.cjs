const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync, spawnSync } = require('node:child_process');
const auto = require('miniprogram-automator');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/full-flow-acceptance-20261023');
const project = path.join(root, '.tools/full-flow-acceptance-20261023/native-project');
const port = Number(process.env.FULL_FLOW_PORT || 9983);
const wsEndpoint = 'ws://127.0.0.1:' + port;
const resultFile = path.join(directory, 'native-results.json');
const priorText = process.argv.includes('--resume') ? fs.readFileSync(resultFile, 'utf8') : '';
const prior = priorText ? JSON.parse(priorText) : null;
const report = { result: 'running', steps: [], source: {}, scope: '隔离微信原生模拟器；六类模拟人员；附件与外部生成采用模拟数据；不涉及真实支付、微信发布或生产通知' };
let priorBackedUp = false;
const persist = () => {
  if (priorText && !priorBackedUp) {
    fs.writeFileSync(path.join(directory, 'native-results.before-resume.json'), priorText);
    priorBackedUp = true;
  }
  fs.writeFileSync(resultFile, JSON.stringify(report, null, 2));
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const normalize = rows => JSON.stringify([...rows].sort((a, b) => a[0].localeCompare(b[0])));

function createResumeGate(previous) {
  let upstreamReran = false;
  return {
    decide(id, { sameSource, sameScript, alwaysRun = false }) {
      const passed = previous?.steps?.some(step => step.id === id && step.result === 'passed');
      const preserve = !alwaysRun && !upstreamReran && passed && sameSource && sameScript;
      if (!preserve) upstreamReran = true;
      return preserve;
    },
    canResume(id, { sameSource, hasCheckpoint }) {
      return !upstreamReran && sameSource && hasCheckpoint && previous?.steps?.some(step => step.id === id && step.result === 'failed');
    }
  };
}

const resumeGate = createResumeGate(prior);

function priorSourceMatches() {
  if (!prior?.source?.files?.length) return false;
  try {
    return prior.source.files.every(row => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, row.file))).digest('hex') === row.sha256);
  } catch (_) {
    return false;
  }
}

async function connect() {
  let error;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    try { return await auto.connect({ wsEndpoint }); }
    catch (failure) { error = failure; await sleep(1200); }
  }
  throw error;
}

function run(id, script, args = [], environment = {}, options = {}) {
  const scriptSha = crypto.createHash('sha256').update(fs.readFileSync(path.join(root, 'scripts', script))).digest('hex');
  const passed = prior?.steps.find(step => step.id === id && step.result === 'passed');
  const changedSource = prior?.source.files.filter(row => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, row.file))).digest('hex') !== row.sha256).map(row => row.file) || [];
  const sameSource = !changedSource.length || ['six-role-login','business-chain'].includes(id) && changedSource.every(file => file === 'miniprogram/pages/build/record/index.wxml');
  const sameScript = !!passed && (passed.scriptSha === scriptSha || !passed.scriptSha && execFileSync('git', ['diff', prior.source.commit, '--', 'scripts/' + script], { cwd: root, encoding: 'utf8' }) === '');
  if (resumeGate.decide(id, { sameSource, sameScript, alwaysRun: options.alwaysRun })) {
    report.steps.push({ ...passed, scriptSha, preserved: true, excludedSourceChanges:changedSource }); persist(); console.log('PRESERVED ' + id); return;
  }
  console.log('START ' + id);
  const log = path.join(directory, id + '.log');
  const fd = fs.openSync(log, 'w');
  const result = spawnSync(process.execPath, [path.join(root, 'scripts', script), ...args], {
    cwd: root,
    env: { ...process.env, FRANCHISE_WS: wsEndpoint, FULL_FLOW_OUT: directory, ...environment },
    stdio: ['ignore', fd, fd],
    timeout: 1200000
  });
  fs.closeSync(fd);
  report.steps.push({ id, script, scriptSha, result: result.status === 0 ? 'passed' : 'failed', log, error: result.error?.message });
  persist();
  if (result.status !== 0) throw new Error(id + ': ' + fs.readFileSync(log, 'utf8').slice(-6500));
  console.log('PASS ' + id);
}

async function main() {
  fs.mkdirSync(directory, { recursive: true });
  fs.mkdirSync(project, { recursive: true });
  fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), { recursive: true });
  const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
  config.projectname = 'v3-six-role-acceptance-20261023';
  fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
  const files = [];
  function fingerprint(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) fingerprint(file);
      else files.push({ file: path.relative(root, file), sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') });
    }
  }
  fingerprint(path.join(root, 'miniprogram'));
  report.source = { commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(), files, project, wsEndpoint };
  persist();
  const cliLog = execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', ['auto', '--project', project, '--auto-port', String(port)], { timeout: 45000, encoding: 'utf8' });
  fs.writeFileSync(path.join(directory, 'native-start.log'), cliLog);
  let mp = await connect();
  const before = await mp.evaluate(() => wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]));
  fs.writeFileSync(path.join(project, 'before-storage.json'), JSON.stringify(before));
  await mp.disconnect();
  try {
    run('six-role-login', 'verify-demo-login-picker-native.cjs', [], { FRANCHISE_NATIVE_OUT: path.join(directory, 'native-login') });
    const resumeBusiness = resumeGate.canResume('business-chain', { sameSource: priorSourceMatches(), hasCheckpoint: fs.existsSync(path.join(directory,'native-business/fixtures/B05.json')) });
    run('business-chain', 'verify-six-role-flow.cjs', ['--native'], { FLOW_RESUME_BUSINESS: resumeBusiness ? '1' : '0' });
    const continuation = path.join(directory, 'native-business/engineering-continuation.json');
    const resumeEngineering = resumeGate.canResume('engineering-chain', { sameSource: priorSourceMatches(), hasCheckpoint: fs.existsSync(path.join(directory,'native-engineering/fixtures/step-04.json')) });
    run('engineering-chain', 'verify-engineering-records-native.cjs', ['continuous', ...(resumeEngineering ? ['--resume'] : [])], {
      ENGINEERING_NATIVE_WS: wsEndpoint,
      ENGINEERING_NATIVE_OUT: path.join(directory, 'native-engineering'),
      ENGINEERING_CONTINUATION_SOURCE: continuation
    });
    const business = JSON.parse(fs.readFileSync(continuation));
    const final = JSON.parse(fs.readFileSync(path.join(directory, 'native-engineering/continuous-final-state.json')));
    const customer = final.find(([key]) => key === 'zhongshu-business-v3')[1].customers.find(row => row.id === business.id);
    const engineering = final.find(([key]) => key === 'zhongshu-engineering-v1')[1].projects.find(row => row.sourceCustomerId === business.id);
    assert.equal(customer.projectId, business.projectId);
    assert.equal(engineering.projectId, customer.projectId);
    assert.equal(customer.ownerId, 'sales-dual');
    assert.equal(engineering.managerId, 'engineering-dual');
    assert.equal(engineering.drawingVersion, 'V2');
    assert.equal(engineering.recordFlow.contract.total, 10000000);
    assert.equal(engineering.collections.reduce((sum, row) => sum + row.cents, 0), 10000000);
    assert(customer.acceptance && engineering.archived);
    report.chain = { customerId: customer.id, projectId: customer.projectId, engineeringId: engineering.id, businessOwner: customer.owner, engineeringOwner: engineering.manager, drawingVersion: engineering.drawingVersion, contractCents: engineering.recordFlow.contract.total, collectedCents: engineering.collections.reduce((sum, row) => sum + row.cents, 0), archived: engineering.archived };
    persist();
    run('design-and-export', 'verify-six-role-design-native.cjs');
    run('flow-boundaries', 'verify-flow-boundaries-native.cjs', [], { FLOW_BOUNDARIES_NATIVE_OUT: path.join(directory, 'native-boundaries') });
    run('engineering-corrections', 'verify-engineering-corrections-native.cjs', [], { ENGINEERING_CORRECTIONS_NATIVE_OUT: path.join(directory, 'native-corrections') });
    run('owner-p0', 'verify-owner-p0-native.cjs', [], {
      ENGINEERING_CONTINUATION_SOURCE: path.join(directory, 'native-engineering/continuous-final-state.json'),
      OWNER_P0_NATIVE_OUT: path.join(directory, 'native-owner'),
      OWNER_P0_RESUME: resumeGate.canResume('owner-p0', { sameSource: priorSourceMatches(), hasCheckpoint: fs.existsSync(path.join(directory,'native-owner/fixtures/M03.json')) }) ? '1' : '0',
      FRANCHISE_NATIVE_TRACE: path.join(root, '.tools/full-flow-acceptance-20261023/owner-native-trace.log')
    });
    run('cross-device-collaboration', 'verify-design-collaboration-native.cjs');
    run('data-invariants', 'verify-full-flow-fixtures.cjs', [], {}, { alwaysRun: true });
    assert(files.every(row => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, row.file))).digest('hex') === row.sha256), '验收期间源码发生变化，应使用最终版本重新运行受影响检查');
    report.result = 'passed';
  } catch (error) {
    report.result = 'failed'; report.error = error.stack; throw error;
  } finally {
    try {
      mp = await connect();
      await mp.evaluate(() => new Promise(resolve => wx.reLaunch({ url: '/pages/account/index?view=policy', complete: resolve })));
      await mp.evaluate(rows => { wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).forEach(key => wx.removeStorageSync(key)); for (const [key, value] of rows) wx.setStorageSync(key, value); }, before);
      const after = await mp.evaluate(() => wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]));
      report.storageRestored = normalize(before) === normalize(after);
      assert(report.storageRestored, '原生验收结束必须恢复隔离项目原数据');
    } catch (error) { report.restoreError = error.message; report.result = 'failed'; throw error; }
    finally { if (mp) await mp.disconnect(); persist(); }
  }
}

if (require.main === module) main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });

module.exports = { createResumeGate };
