const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../miniprogram');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const tokens = read('styles/tokens.wxss');
for (const [token, value] of Object.entries({
  '--zs-font-body': '28rpx',
  '--zs-space-page': '28rpx',
  '--zs-space-card': '24rpx',
  '--zs-space-section': '24rpx',
  '--zs-control-height': '88rpx'
})) assert(tokens.includes(`${token}: ${value};`), `${token} 必须使用统一设计值 ${value}`);
assert.equal(read('styles/component-tokens.wxss'), tokens.replace('page {', '.zs-theme {'));
assert(!read('styles/unified.wxss').includes('margin-top:-'), '点击区域不得通过负边距侵入相邻内容');
assert(!read('styles/unified.wxss').includes('margin-bottom:-'), '点击区域不得通过负边距侵入相邻内容');
const controls = read('styles/controls.wxss');
assert(controls.includes('height:auto'), '长按钮文案须允许自然增高');
assert(controls.includes('white-space:normal'), '主操作文字允许完整换行');
assert(controls.includes('overflow-wrap:anywhere'), '仅在必要时折断连续长动作名称');
for (const file of ['pages/account/header/index.wxss', 'pages/admin/header/index.wxss', 'pages/build/header/index.wxss']) {
  const source = read(file);
  assert(!/font-weight:(?:400|700)/.test(source), `${file} 导航统一使用600字重`);
  assert(source.includes('var(--zs-font-family)'), `${file} 导航须引用系统字体令牌`);
}
assert(!/Songti|STSong|SimSun/.test(read('pages/workflow/index.wxss')), '业务标题不保留旧宋体覆盖');
for (const [file, rootClass] of [
  ['components/brand-header/index.wxml', 'header'],
  ['components/business-header/index.wxml', 'header'],
  ['components/creation-header/index.wxml', 'creation-header']
]) assert(read(file).includes(`class="${rootClass} zs-theme`), `${file} 实际头部根节点需要独立设计令牌，不能只给占位节点赋值`);
assert(read('pages/build/record/index.wxss').includes('@media(max-width:360px){.record-node-menu{grid-template-columns:repeat(2,minmax(0,1fr))}'), '窄屏施工节点菜单使用两列');
assert(read('pages/collaboration/index.wxss').includes('.collab-page .solid[disabled],.collab-page .outline[disabled]'), '协同工作台禁用按钮必须有可辨识的统一状态');
assert(read('styles/project-flow.wxss').includes('line-height:36rpx!important;min-height:var(--zs-control-height)'), '后导入的业务流程样式不得将文字按钮缩回64rpx');
assert(read('styles/project-flow.wxss').includes('padding:var(--zs-space-card);margin-bottom:var(--zs-space-section)'), '后导入的流程卡片仍须使用统一间距');
console.log('PASS 全局字体、间距、导航、长按钮和点击区布局契约');
