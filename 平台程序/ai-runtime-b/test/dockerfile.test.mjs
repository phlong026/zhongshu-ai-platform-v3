import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// sharp 只为测试生成图片；生产 Runtime 不动态加载它，也不把测试依赖打进镜像。
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dockerfile = readFileSync(join(root, 'Dockerfile'), 'utf8');
const dockerignore = readFileSync(join(root, '.dockerignore'), 'utf8');
const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));

// 按 FROM 切分；文件开头的注释段不是阶段，只保留以镜像名开头的片段
const stages = dockerfile.split(/^FROM /m).filter(part => /^node:/.test(part));

test('sharp 只属于测试依赖', () => {
  assert.equal(Object.keys(manifest.dependencies || {}).length, 0);
  assert.ok(Object.hasOwn(manifest.devDependencies, 'sharp'));
});

test('生产镜像固定 Node digest 且不携带 node_modules', () => {
  assert.equal(stages.length, 1);
  assert.match(dockerfile, /node:24\.19\.0-alpine3\.24@sha256:[a-f0-9]{64}/);
  assert.doesNotMatch(dockerfile, /COPY[^\n]*node_modules|RUN npm (?:ci|install)/);
  assert.doesNotMatch(dockerfile, /apk upgrade/);
  assert.match(dockerfile, /chmod 0700 \/data/);
});

test('运行阶段保持精简：不带 npm/yarn、非 root、不在运行阶段安装依赖', () => {
  const runtime = stages[0];
  assert.match(runtime, /rm -f \/usr\/local\/bin\/npm/);
  assert.match(runtime, /^USER node$/m);
  assert.doesNotMatch(runtime, /npm ci|npm install/);
});

test('.dockerignore 放行锁文件（否则依赖阶段拿不到 package-lock.json）', () => {
  assert.match(dockerignore, /^!package-lock\.json$/m);
  assert.match(dockerignore, /^!package\.json$/m);
  assert.doesNotMatch(dockerignore, /^!node_modules/m, '本机 node_modules 不得被打进镜像，平台二进制不同');
});
