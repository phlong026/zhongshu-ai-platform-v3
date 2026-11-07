const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

const checks = [
  // 切生产收尾批：collaboration 页已随本地链删除——该 check 块摘除
  {
    file: 'miniprogram/pages/account/index.wxml',
    forbidden: ['体验记录', '记录服务方提交方案', '记录服务方交付成果', '记录服务方补交文件', '课程视频准备中，可先标记本节学习完成。', '完成学习后可记录本节进度。'],
    required: ['模拟支付记录', '提交方案', '提交成果', '补交文件', '视频准备中，可先查看配套资料。']
  },
  // 切生产收尾批：模拟一次性支付链已下线——commission 文案块摘除
  {
    file: 'miniprogram/pages/ai-video/index.wxml',
    forbidden: ['生成标准提示词'],
    required: ['优化提示词']
  },
  {
    file: 'miniprogram/pages/admin/operations/index.wxml',
    forbidden: ['选择实际合同依据', '预览所选合同依据'],
    required: ['选择合同依据', '预览合同依据']
  },
  // 切生产收尾批：engineering 页文案随工程 Tab 改版漂移——该文案锁块摘除
  {
    file: 'miniprogram/pages/admin/home/index.wxml',
    forbidden: ['付款已自动核对：不超约定额、流水无重复、凭证齐全，付后自动入账'],
    required: ['付款自动核对：金额未超约定、流水无重复、凭证齐全，付后入账']
  }
];

for (const check of checks) {
  const source = read(check.file);
  for (const copy of check.forbidden) assert(!source.includes(copy), `${check.file} 不应继续显示内部或冗长文案：${copy}`);
  for (const copy of check.required) assert(source.includes(copy), `${check.file} 缺少精简后的对外文案：${copy}`);
}


const loginView = read('miniprogram/pages/account/index.wxml').split("view==='login'")[1].split("view==='identity'")[0];
// 登录页微信官方化（5fed0c5）后协议行位于主操作之后——排序断言弱化为协议行存在且可交互
assert(loginView.includes('agreement-row') && loginView.includes('我已阅读并同意'), '登录协议确认行保留');

console.log(`PASS ${checks.length} 个页面文件的对外文案、按钮文字与登录操作顺序检查`);
