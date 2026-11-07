const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../miniprogram');
const tokenFile = path.join(root, 'styles/tokens.wxss');
assert(fs.existsSync(tokenFile), '设计令牌必须由独立样式文件统一维护');
const tokens = fs.readFileSync(tokenFile, 'utf8');
const componentTokens = fs.readFileSync(path.join(root, 'styles/component-tokens.wxss'), 'utf8');
assert.equal(componentTokens, tokens.replace('page {', '.zs-theme {'), '组件宿主令牌必须与全局令牌同步');
const files = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const file = path.join(directory, entry.name);
  return entry.isDirectory() ? files(file) : file.endsWith('.wxss') ? [file] : [];
});
for (const file of files(root).filter(file => file !== tokenFile && !file.endsWith('/component-tokens.wxss'))) {
  const definition = file.replace(/\.wxss$/, '.json');
  if (fs.existsSync(definition) && JSON.parse(fs.readFileSync(definition, 'utf8')).component) {
    assert(fs.readFileSync(file, 'utf8').includes('styles/component-tokens.wxss'), file + ' 组件须直接引入宿主令牌');
  }
  const source = fs.readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  assert(!/#(?:89512a|75451f|9a9187|b3402a|2e7d52|8a5a12)\b/i.test(source), file + ' 应引用品牌及状态令牌');
  assert(!/font-size\s*:\s*[\d.]+rpx/.test(source), file + ' 应引用统一字阶');
}
assert(!/Songti|STSong|SimSun|[, ]serif;/.test(fs.readFileSync(path.join(root, 'app.wxss'), 'utf8')), '全局标题使用系统黑体');
function luminance(hex) {
  const values = hex.match(/[\da-f]{2}/g).map(value => parseInt(value, 16) / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return values[0] * 0.2126 + values[1] * 0.7152 + values[2] * 0.0722;
}
const muted = tokens.match(/--zs-text-3:\s*#([\da-f]{6})/)[1];
assert((luminance('faf8f4') + 0.05) / (luminance(muted) + 0.05) >= 4.5, '辅助正文在米白底上达到可读对比度');
console.log('PASS 统一颜色令牌、字阶、系统黑体与辅助文字对比度');
