#!/usr/bin/env node
/**
 * 卫生债巡检：扫描 scripts/verify-*.cjs，对照 package.json scripts 找出无 npm 入口的孤儿脚本。
 * 口径：verify 脚本被任一 npm script 命令行引用，或被其他 scripts/*.cjs require（verify.cjs 的
 * 内联 helper 先例），视为有入口；两者皆无 → 孤儿。有孤儿输出清单并以退出码 1 结束。
 * 用法：node scripts/check-verify-orphan.cjs（npm run check:verify-orphan）
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const scriptDir = path.join(root, 'scripts');

// npm scripts 全量命令文本（check 链/capture 链里的 node scripts/verify-*.cjs 都算入口）
const npmText = Object.values(pkg.scripts || {}).join('\n');
// scripts 目录内 require 引用（./verify-xxx.cjs 形态）
const requireText = fs.readdirSync(scriptDir)
  .filter(f => f.endsWith('.cjs') && f !== 'check-verify-orphan.cjs')
  .map(f => fs.readFileSync(path.join(scriptDir, f), 'utf8')).join('\n');

const orphans = fs.readdirSync(scriptDir)
  .filter(f => /^verify-.+\.cjs$/.test(f))
  .filter(f => !npmText.includes(f) && !new RegExp("require\\([^)]*'\\./" + f + "'\\)").test(requireText))
  .sort();

if (orphans.length) {
  console.log('verify 孤儿脚本（无 npm 入口、无脚本内引用）：' + orphans.length + ' 个');
  for (const f of orphans) console.log('  - scripts/' + f);
  console.log('处置：挂 npm scripts 独立入口、并入既有链，或确认废弃后删除。');
  process.exit(1);
}
console.log('verify 孤儿脚本巡检通过：scripts/verify-*.cjs 均有 npm 入口或脚本内引用');
