#!/usr/bin/env node
/**
 * M5 R3 P1-B：reviseReceipt 行为级变异测试（真实执行，非字符串搜索）。
 *
 * 沙箱构造：mock http（捕获 Idempotency-Key）注入 api/finance.js 源（require 替换），
 * child_process 执行两个场景：
 *   场景 A（修复后实现）：idemKey 缺省 → 键回退生成且透传（捕获到 fin-revision- 前缀键）
 *   场景 B（变异：恢复原缺陷表达式 idemKey || idemKey('revision')）：idemKey 缺省 → TypeError
 * 通过条件：A 键透传非遮蔽 + B 抛 TypeError（变异可检出）。
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const src = fs.readFileSync(path.join(ROOT, 'miniprogram/api/finance.js'), 'utf8');

const scenario = mutated => {
  let body = 'const captured=[];';
  body += "const http = { post(path, body, header){ captured.push(header && header['Idempotency-Key']); return Promise.resolve({}); }, get(){return Promise.resolve([]);}, base(){return 'http://x';}, token(){return 'tok';} };";
  let src0 = src;
  if (mutated) {
    src0 = src.replace("idemKey || ('fin-revision-' + receiptId + '-' + Date.now())", "idemKey || idemKey('revision')");
    if (src0 === src) throw new Error('变异替换未命中');
  }
  body += src0.replace("const http = require('./http');", ''); // 移除源内 require，保留 mock
  body += '(async()=>{ try { await reviseReceipt(123,1,100,"note"); console.log("RESULT:"+JSON.stringify({threw:false,keys:captured})); } catch(e){ console.log("RESULT:"+JSON.stringify({threw:true,message:e.message,keys:captured})); } })();';
  return body;
};

const run = mutated => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'm5-behavior-'));
  const sandbox = path.join(dir, 'sandbox.js');
  fs.writeFileSync(sandbox, scenario(mutated), 'utf8');
  const res = spawnSync(process.execPath, [sandbox], { encoding: 'utf8', timeout: 15000 });
  fs.rmSync(dir, { recursive: true, force: true });
  const line = (res.stdout || '').split('\n').find(l => l.startsWith('RESULT:'));
  return line ? JSON.parse(line.slice(7)) : { threw: true, message: (res.stderr || res.stdout || '').slice(0, 200), parseFail: true };
};

let pass = 0, fail = 0;
const ok = (cond, name) => { if (cond) { pass++; console.log('  ok - ' + name); } else { fail++; console.log('  FAIL - ' + name); } };

// 场景 A：修复后实现——缺省键回退生成并透传
const a = run(false);
ok(!a.threw && (a.keys || []).some(k => String(k).indexOf('fin-revision-123-') === 0),
  '场景 A：idemKey 缺省回退生成键且透传（非遮蔽）');

// 场景 B：变异（恢复原缺陷表达式）——抛 TypeError（变异可检出）
const b = run(true);
ok(b.threw && /idemKey is not a function/.test(b.message || ''),
  '场景 B：变异恢复原缺陷后抛 TypeError（行为可检出）');

console.log(`\n行为级变异测试：${pass} 通过，${fail} 失败`);
process.exit(fail ? 1 : 0);
