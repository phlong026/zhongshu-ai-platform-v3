const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

const checks = [
  {
    file: 'miniprogram/pages/collaboration/index.wxml',
    forbidden: ['跨设备联调', '接入联调工作台', '本轮联调', '联调流水编号', '联调渠道', '微信通知联调记录', '暂无联调投递记录', '服务端分配的测试身份', '微信提醒联调', '当前仅验证发送流程', '退出本机联调会话'],
    required: ['协同工作台', '联调服务地址', '本人测试接入码', '模拟消息网关', '真实微信授权待接入', '到账提醒', '退出当前会话']
  },
  {
    file: 'miniprogram/pages/account/index.wxml',
    forbidden: ['体验记录', '记录服务方提交方案', '记录服务方交付成果', '记录服务方补交文件', '课程视频准备中，可先标记本节学习完成。', '完成学习后可记录本节进度。'],
    required: ['模拟支付记录', '提交方案', '提交成果', '补交文件', '视频准备中，可先查看配套资料。']
  },
  {
    file: 'miniprogram/pages/workflow/commission/index.wxml',
    forbidden: ['体验版一次性支付'],
    required: ['模拟一次性支付']
  },
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
  {
    file: 'miniprogram/pages/engineering/index.wxml',
    forbidden: ['钱没到，交付自动暂停'],
    required: ['未到账时自动暂停交付']
  },
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
assert(loginView.indexOf('agreement-row') < loginView.indexOf('data-handler="login"'), '登录协议确认必须排在主操作之前');

console.log(`PASS ${checks.length} 个页面文件的对外文案、按钮文字与登录操作顺序检查`);
