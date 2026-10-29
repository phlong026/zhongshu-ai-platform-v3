const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');

const fixedFixtures = [
  {
    name: '旧业务同人策略回归',
    source: 'scripts/fixtures/legacy-policy-same-person.json.gz.b64',
    target: 'qa/business-followups-20261019/policy-same-person/intermediate-state.json',
    sha256: 'eb4e4414f7ffd02d485937199a0a01047e7317f7e13b7e515954dedbca8e1d70',
    validate(value) {
      assert(value.customer?.id, '旧业务夹具缺少客户');
      assert(value.account, '旧业务夹具缺少账号');
      assert(Array.isArray(value.engineering?.projects), '旧业务夹具缺少工程项目');
      assert(value.management, '旧业务夹具缺少管理账簿');
    }
  },
  {
    name: '旧财务可见文案回归',
    source: 'scripts/fixtures/legacy-finance-hq-staff.json.gz.b64',
    target: 'qa/finance-implementation-20261021/fixtures.json',
    sha256: '1a050719470ab7e33b3e04c0069a69a241c6d05892e45c30f0cf1abdffc450ba',
    validate(value) {
      assert(Array.isArray(value.fixtures?.['hq-staff']), '旧财务夹具缺少 hq-staff 场景');
    }
  }
];

function decodeFixture(fixture) {
  const encoded = fs.readFileSync(path.join(root, fixture.source), 'utf8').replace(/\s/g, '');
  const raw = zlib.gunzipSync(Buffer.from(encoded, 'base64'));
  assert.equal(crypto.createHash('sha256').update(raw).digest('hex'), fixture.sha256, fixture.name + '夹具校验失败');
  const value = JSON.parse(raw.toString('utf8'));
  fixture.validate(value);
  return { raw, value };
}

function materialize(fixture) {
  const { raw, value } = decodeFixture(fixture);
  const target = path.join(root, fixture.target);
  if (fs.existsSync(target)) {
    const existing = JSON.parse(fs.readFileSync(target, 'utf8'));
    fixture.validate(existing);
    const existingHash = crypto.createHash('sha256').update(JSON.stringify(existing)).digest('hex');
    if (existingHash === fixture.sha256) {
      console.log('REUSE ' + fixture.name + ' ' + fixture.target);
      return;
    }
  }
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, raw);
  fixture.validate(value);
  console.log('CREATE ' + fixture.name + ' ' + fixture.target);
}

function generate(script) {
  const env = { ...process.env };
  delete env.ENGINEERING_CONTINUATION_SOURCE;
  delete env.ENGINEERING_PAGE_OUT;
  const result = spawnSync(process.execPath, [script], { cwd: root, env, stdio: 'inherit', timeout: 300000 });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, script + ' 生成失败');
}

fixedFixtures.forEach(materialize);

// Page scenes track current source behavior and are intentionally regenerated instead of committed.
generate('scripts/verify-sales-offline-flow.cjs');
generate('scripts/verify-engineering-records.cjs');

console.log('PASS 全流程固定旧链夹具与当前工程场景已就绪');
