const assert = require('node:assert/strict');
const { verifyComments, verifyDirectory } = require('./verify-wxss-comments.cjs');
assert.throws(() => verifyComments('/* 以 fin-*/eng-* 基准 */ page {}', 'fixture.wxss'), /提前闭合/);
assert.throws(() => verifyComments('/* 未完成', 'fixture.wxss'), /未闭合/);
verifyComments('/* 正常 */ .a { content: "/* */"; }', 'fixture.wxss');
verifyDirectory(require('node:path').resolve(__dirname, '../miniprogram'));
console.log('PASS 注释回归与当前样式检查');
