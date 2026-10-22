const assert = require('node:assert/strict');

const { createResumeGate } = require('./verify-full-flow-native.cjs');

const passedSteps = [
  'six-role-login',
  'business-chain',
  'engineering-chain',
  'design-and-export',
  'flow-boundaries',
  'engineering-corrections',
  'owner-p0',
  'cross-device-collaboration'
].map(id => ({ id, result: 'passed' }));

{
  const gate = createResumeGate({ steps: passedSteps });
  for (const step of passedSteps) {
    assert.equal(gate.decide(step.id, { sameSource: true, sameScript: true }), true, `${step.id} 应保留`);
  }
  assert.equal(gate.decide('data-invariants', { sameSource: true, sameScript: true, alwaysRun: true }), false, '数据一致性门禁不得保留');
}

{
  const gate = createResumeGate({ steps: passedSteps });
  assert.equal(gate.decide('business-chain', { sameSource: true, sameScript: false }), false, '脚本变化须重跑本步骤');
  assert.equal(gate.decide('engineering-chain', { sameSource: true, sameScript: true }), false, '上游重跑后工程不得保留旧ID');
  assert.equal(gate.decide('owner-p0', { sameSource: true, sameScript: true }), false, '上游重跑后管理快照不得保留');
}

{
  const prior = { steps: [{ id: 'engineering-chain', result: 'failed' }, { id: 'owner-p0', result: 'failed' }] };
  const clean = createResumeGate(prior);
  assert(clean.canResume('engineering-chain', { sameSource: true, hasCheckpoint: true }), '源码一致且上游未重跑时允许工程断点续跑');
  assert(!clean.canResume('engineering-chain', { sameSource: false, hasCheckpoint: true }), '源码变化时禁止工程断点续跑');
  const dirty = createResumeGate(prior);
  dirty.decide('business-chain', { sameSource: true, sameScript: false });
  assert(!dirty.canResume('engineering-chain', { sameSource: true, hasCheckpoint: true }), '上游重跑时禁止工程断点续跑');
  assert(!dirty.canResume('owner-p0', { sameSource: true, hasCheckpoint: true }), '上游重跑时禁止管理断点续跑');
}

console.log('PASS 原生总验收续跑门禁：上游失效、断点约束和数据一致性强制执行');
