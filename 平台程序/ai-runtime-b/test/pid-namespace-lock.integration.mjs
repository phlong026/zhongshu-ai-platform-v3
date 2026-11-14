import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const id = randomBytes(6).toString('hex');
const image = `zhongshu-ai-runtime-b-lock-test:${id}`;
const volume = `zs-runtime-lock-${id}`, first = `zs-runtime-lock-a-${id}`, second = `zs-runtime-lock-b-${id}`;

function run(args, options = {}) {
  return spawnSync('docker', args, { encoding: 'utf8', ...options });
}
function requireSuccess(result, label) {
  if (result.status !== 0) throw Error(`${label}: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}

try {
  requireSuccess(run(['build', '--tag', image, '.']), 'build current runtime image');
  requireSuccess(run(['volume', 'create', volume]), 'create volume');
  requireSuccess(run(['run', '--rm', '--user', '0:0', '-v', `${volume}:/data`, '--entrypoint', '/bin/sh', image,
    '-c', 'chown node:node /data && chmod 0700 /data']), 'prepare volume');
  const holder = `import { Journal } from './src/runtime.mjs'; new Journal('/data', { singleWriter: true }); console.log('LOCKED'); setInterval(() => {}, 1000);`;
  requireSuccess(run(['run', '-d', '--name', first, '--hostname', 'same-runtime-host', '-v', `${volume}:/data`,
    '--entrypoint', 'node', image, '--input-type=module', '-e', holder]), 'start first holder');
  for (let attempt = 0; attempt < 40; attempt++) {
    if (run(['logs', first]).stdout.includes('LOCKED')) break;
    if (attempt === 39) throw Error('first holder did not acquire lock');
    await delay(100);
  }
  const contender = `import { Journal } from './src/runtime.mjs'; try { new Journal('/data', { singleWriter: true }); console.log('UNSAFE_ACQUIRED'); } catch (error) { console.error(error.message); process.exit(error.message === 'JOURNAL_LOCKED' ? 23 : 24); }`;
  const result = run(['run', '--rm', '--name', second, '--hostname', 'same-runtime-host', '-v', `${volume}:/data`,
    '--entrypoint', 'node', image, '--input-type=module', '-e', contender]);
  assert.equal(result.status, 23, `different PID namespace must fail closed; stdout=${result.stdout} stderr=${result.stderr}`);
  assert.match(result.stderr, /JOURNAL_LOCKED/);
  console.log(JSON.stringify({ result: 'PASS', scenario: 'same-hostname-different-pid-namespace' }));
} finally {
  run(['rm', '-f', first, second]);
  run(['volume', 'rm', '-f', volume]);
  run(['image', 'rm', '-f', image]);
}
