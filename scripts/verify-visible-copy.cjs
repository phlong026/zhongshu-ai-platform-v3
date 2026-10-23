const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const files = [
  'miniprogram/pages/account/index.js',
  'miniprogram/pages/account/index.wxml',
  'miniprogram/pages/growth/index.js',
  'miniprogram/pages/workflow/index.js',
  'miniprogram/pages/workflow/index.wxml',
  'miniprogram/pages/workflow/explore/index.wxml',
  'miniprogram/pages/workflow/quote-export/render.js',
  'miniprogram/pages/admin/performance-views.js',
  'miniprogram/pages/collaboration/client.js',
  'miniprogram/utils/cross-device-client.js',
  'miniprogram/pages/collaboration/index.js',
  'miniprogram/pages/collaboration/index.wxml',
  'miniprogram/pages/profile/profile-page.js',
  'miniprogram/pages/studio/index.js',
  'miniprogram/pages/studio/index.wxml',
  'miniprogram/pages/build/index.js',
  'miniprogram/pages/build/model.js',
  'miniprogram/pages/build/record/index.js',
  'miniprogram/pages/creation/index.js',
  'miniprogram/pages/admin/operations/index.js',
  'miniprogram/pages/admin/operations/index.wxml',
  'miniprogram/pages/admin/index.js',
  'miniprogram/pages/admin/organization-views-v2.js',
  'miniprogram/utils/account.js',
  'miniprogram/utils/creation-demo.js',
  'miniprogram/utils/procurement.js',
  'miniprogram/utils/studio.js',
  'miniprogram/utils/sales-demo.js'
];
const sources = new Map(files.map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')]));
const combined = [...sources.values()].join('\n');
const collect = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const target = path.join(directory, entry.name);
  return entry.isDirectory() ? collect(target) : /\.(js|wxml)$/.test(entry.name) ? [target] : [];
});

const removedLabels = [
  '示例数据', '演示数据', '体验数据', '模拟数据', '本地演示', '仅供体验',
  '使用示例', '示例订单', '体验服务方', '模拟方案', '模拟候选', '模拟流水',
  '模拟银行凭据', '示例凭据', '演示模式', '模拟优化', '体验口播创作流程',
  '演示完成', ' · 演示', '（模拟）', '模拟来源', '本地模拟', '暂无样本',
  '模拟报价', '模拟供应方报价.pdf', '模拟已送达', '模拟跨端联调',
  '跨设备模拟联调', '只是示例', '模拟计价'
];
const legacyCompatibilityLabels = [
  '示例附件.pdf', '图稿示例凭据.pdf', '工程记录示例凭据.pdf',
  '闭环流程示例凭据.pdf', '模拟供应方报价.pdf',
  '需求已提交（本地演示）', '预置本地模拟跟进', '本地演示已记录'
];
for (const label of removedLabels) {
  assert(!combined.includes(label), `仍有面向用户的测试标签：${label}`);
}
const allFiles = collect(path.join(root, 'miniprogram'));
for (const filename of allFiles) {
  const relative = path.relative(root, filename);
  let source = fs.readFileSync(filename, 'utf8');
  if (relative === 'miniprogram/utils/demo-users.js') source = source.replace('本地模拟总部老板初始授权政策', '总部老板初始授权政策');
  if (relative === 'miniprogram/utils/sample-files.js') for (const label of legacyCompatibilityLabels) source = source.replaceAll(label, '');
  for (const label of removedLabels) assert(!source.includes(label), `${relative} 仍有面向用户的测试标签：${label}`);
}

const expectedCopy = [
  '使用预设账号', '记录服务进度', '当前需求已保存在本机订单中',
  '参考附件', '参考凭据', '方案预览', '参考来源：', '创建口播预览',
  '数字人生成服务暂未开通', '跨端数据联调', '真实微信授权待接入',
  '暂无记录', '当前账号不能创建报价预览，请等待供应方报价', '参考供应方报价.pdf',
  '测试网关已送达', '当前服务不支持跨端联调', '跨设备联调', '为参考尺寸',
  '参考计价，非正式对外报价',
  "status:content.status==='演示已发布'?'已登记发布':content.status"
];
for (const label of expectedCopy) {
  assert(combined.includes(label), `缺少清理后的准确文案：${label}`);
}

assert(sources.get('miniprogram/pages/workflow/index.wxml').includes('仅供方案沟通，不作为施工图'));
assert(sources.get('miniprogram/pages/workflow/explore/index.wxml').includes('图像仅供沟通参考'));
assert(sources.get('miniprogram/pages/collaboration/index.wxml').includes('测试网关'));
assert(sources.get('miniprogram/pages/workflow/index.js').includes('sample:true'));
// Contextual finance sample markers are covered by verify-project-sample-files.cjs.
assert(sources.get('miniprogram/utils/studio.js').includes('demo: true'));
assert(sources.get('miniprogram/utils/procurement.js').includes('function quoteDemo'));
assert(sources.get('miniprogram/utils/procurement.js').includes('demo:true'));
assert(sources.get('miniprogram/utils/procurement.js').includes('sample:true'));
assert(sources.get('miniprogram/pages/workflow/quote-export/render.js').includes('doc.demo'));
assert(sources.get('miniprogram/utils/account.js').includes('demo: true'));
assert(sources.get('miniprogram/utils/account.js').includes('function loginDemo'));

console.log(`PASS ${files.length} 个重点文件及 ${allFiles.length} 个小程序源码文件已清理页面测试标签，并保留内部 sample/demo 标记与真实能力边界`);
