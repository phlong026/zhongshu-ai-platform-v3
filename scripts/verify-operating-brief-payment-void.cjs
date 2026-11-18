/**
 * P1 批6：老板月度经营简报 + 付款单撤销 + Web 协同提示（纯逻辑 + 代码级静态断言）。
 *
 * 覆盖：
 *  A. 简报数据整形（utils/platform-operating-brief.js）：分→元、比率、趋势、收支构成、账龄六桶、降级
 *  B. 快照归档规则：仅过去月可归档（后端 PerfOperatingService.snapshot 008 实证）
 *  C. 快照列表：version / supersedes 替代关系 / asOf / 归档截止说明
 *  D. 付款单撤销：角色矩阵（仅 finance-lead）、原因校验、联动提示、错误码文案
 *  E. 代码级接线断言：端点封装、页面注册、WXML 无方法调用/find 插值、按钮与提示落位
 *
 * 运行：node scripts/verify-operating-brief-payment-void.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

let passed = 0;
function ok(condition, name) {
  assert.ok(condition, name);
  passed += 1;
}
function eq(actual, expected, name) {
  assert.equal(actual, expected, name);
  passed += 1;
}

const Brief = require('../miniprogram/utils/platform-operating-brief');
const PF = require('../miniprogram/utils/platform-finance');

// ===================== A. 金额/比率/文案口径 =====================
eq(Brief.centsText(1234567), '¥12,345.67', '分→元：1234567 → ¥12,345.67');
eq(Brief.centsText(0), '¥0.00', '零值显示 ¥0.00（不吞零）');
eq(Brief.centsText(null), '—', 'null → 占位符');
eq(Brief.centsText(undefined), '—', 'undefined → 占位符');
eq(Brief.centsText(-50000), '-¥500.00', '负值保留负号（支出退回冲减）');
eq(Brief.centsText('abc'), '—', '非法值 → 占位符');
eq(Brief.percentText(0.8512), '85.1%', '0~1 比率 → 百分比一位小数');
eq(Brief.percentText(1), '100.0%', '满额 100.0%');
eq(Brief.percentText(null), '—', 'null 比率 → 占位符');
eq(Brief.ratioText(1.2345), '1.23×', '资金覆盖倍数两位小数');
eq(Brief.ratioText(0), '0.00×', '零覆盖显示 0.00×');
eq(Brief.ratioText(null), '—', 'null 覆盖 → 占位符');
eq(Brief.periodLabel('2026-11'), '2026年11月', 'period → 中文月份');
eq(Brief.periodLabel('2026-1'), '2026-1', '非法 period 原样返回');
ok(Brief.validPeriod('2026-11') && !Brief.validPeriod('2026-13') && !Brief.validPeriod('202611'),
  'period 严格 YYYY-MM');

// ===================== B. 归档规则（仅过去月） =====================
eq(Brief.isArchiveable('2026-10', '2026-11'), true, '上月可归档');
eq(Brief.isArchiveable('2026-11', '2026-11'), false, '当月不可归档（实时口径）');
eq(Brief.isArchiveable('2026-12', '2026-11'), false, '未来月不可归档');
eq(Brief.isArchiveable('2025-12', '2026-01'), true, '跨年上月可归档');
const currentPlan = Brief.archivePlan({ period: '2026-11', currentPeriod: '2026-11', hasSnapshot: false });
eq(currentPlan.disabled, true, '当月归档按钮禁用');
ok(/当月为实时简报/.test(currentPlan.reason), '当月禁用原因=实时简报不落快照');
const pastPlan = Brief.archivePlan({ period: '2026-10', currentPeriod: '2026-11', hasSnapshot: false });
eq(pastPlan.disabled, false, '过去月归档可用');
eq(pastPlan.label, '归档本月快照', '无快照时按钮=归档本月快照');
const revisePlan = Brief.archivePlan({ period: '2026-10', currentPeriod: '2026-11', hasSnapshot: true });
eq(revisePlan.label, '重新归档（生成新版本）', '已有快照时按钮=重新归档');

// ===================== C. 简报表单整形 =====================
const analytics = {
  trend: [
    { period: '2026-06', incomeCents: 100000, paidCents: 50000, netCents: 50000 },
    { period: '2026-07', incomeCents: 200000, paidCents: 100000, netCents: 100000 },
    { period: '2026-08', incomeCents: 300000, paidCents: 400000, netCents: -100000 },
    { period: '2026-09', incomeCents: 400000, paidCents: 200000, netCents: 200000 },
    { period: '2026-10', incomeCents: 500000, paidCents: 250000, netCents: 250000 },
    { period: '2026-11', incomeCents: 600000, paidCents: 300000, netCents: 300000 }
  ],
  incomeComposition: [
    { kind: 'design', cents: 300000 },
    { kind: 'manual', cents: 300000 }
  ],
  // 符号口径（后端 PerfOperatingService.paidComposition 实证）：付款与客户退款均为正流出，
  // 仅 expense-refund（支出退回）以负值冲减；净支出=400000+50000-50000=400000 分
  paidComposition: [
    { kind: 'payment', cents: 400000 },
    { kind: 'refund', cents: 50000 },
    { kind: 'expense-refund', cents: -50000 }
  ],
  totals: {
    receivableCents: 800000,
    payableOutstandingCents: 400000,
    collectionRate: 0.625,
    net6MonthsCents: 800000,
    coverage: 2
  },
  receivableAging: { notDue: 500000, d1_30: 300000, d31_60: 0, d61_90: 0, d90Plus: 0, undated: 0 },
  payableAging: { notDue: 200000, d90Plus: 200000, unknownBucket: 999 }
};
const snapshots = [
  { id: '9002', version: 2, supersedes: '9001', asOf: '2026-10-31', note: '修订后归档' },
  { id: '9001', version: 1, supersedes: null, asOf: '2026-10-31', note: '' }
];
const view = Brief.briefView({
  period: '2026-11', currentPeriod: '2026-11', operating: analytics, snapshots, snapshotsStatus: 'ready'
});
eq(view.mode, 'live', '当月 → 实时简报模式');
eq(view.periodLabel, '2026年11月', '简报标题月份');
eq(view.metrics.length, 6, '指标卡六项（净流入/应收/应付/回款率/覆盖/近6月净流入）');
eq(view.metrics[0].label, '当月净流入', '指标 1=当月净流入');
eq(view.metrics[0].value, '¥3,000.00', '当月净流入取选中月趋势行（非最后一行兜底）');
eq(view.metrics[1].value, '¥8,000.00', '应收余额=analytics.totals.receivableCents（后端字段实证）');
eq(view.metrics[2].value, '¥4,000.00', '应付余额=payableOutstandingCents');
eq(view.metrics[3].value, '62.5%', '回款率=collectionRate');
eq(view.metrics[4].value, '2.00×', '资金覆盖=coverage');
eq(view.metrics[5].value, '¥8,000.00', '近 6 月净流入=net6MonthsCents');
eq(view.trend.length, 6, '趋势六行');
eq(view.trend[5].incomeText, '¥6,000.00', '趋势行金额按元显示');
eq(view.trend[0].incomeWidth, 17, '趋势条宽度按 6 月最大收入归一（100000/600000）');
eq(view.trend[2].netTone, 'negative', '负净流入标记 negative（样式不用表达式计算）');
eq(view.trend[5].netTone, '', '正净流入无 negative 标记');
eq(view.income.rows.length, 2, '收入构成两行');
eq(view.income.rows[0].label, '设计款', '收入来源 design → 设计款');
eq(view.income.rows[1].label, '手工登记', '收入来源 manual → 手工登记');
eq(view.income.rows[0].percentText, '50.0%', '收入构成占比（按收入合计）');
eq(view.income.totalText, '¥6,000.00', '收入构成合计');
eq(view.paid.rows.length, 3, '支出构成三行（付款/退款/支出退回）');
eq(view.paid.rows[1].label, '客户退款', '支出 kind refund → 客户退款');
eq(view.paid.rows[2].label, '支出退回', '支出 kind expense-refund → 支出退回');
eq(view.paid.rows[1].valueText, '¥500.00', '客户退款为正流出（不得写成负值）');
eq(view.paid.rows[2].valueText, '-¥500.00', '支出退回负值原样展示（冲减语义）');
eq(view.paid.totalText, '¥4,000.00', '支出构成合计=净支出（付款+退款-支出退回=4000.00）');
eq(view.aging.rows.length, 6, '账龄固定六桶（未知桶忽略，与 Web agingRows 一致）');
eq(view.aging.rows[0].label, '未到期', '账龄桶 1=未到期');
eq(view.aging.rows[5].label, '未约定', '账龄桶 6=未约定');
eq(view.aging.rows[0].receivableText, '¥5,000.00', '应收账龄 notDue');
eq(view.aging.rows[0].payableText, '¥2,000.00', '应付账龄 notDue');
eq(view.aging.rows[4].payableText, '¥2,000.00', '应付账龄 d90Plus');
eq(view.aging.receivableTotalText, '¥8,000.00', '应收账龄合计');
eq(view.aging.payableTotalText, '¥4,000.00', '应付账龄合计（未知桶不计入）');
eq(view.snapshots.rows.length, 2, '快照两版');
eq(view.snapshots.rows[0].versionText, 'v2', '快照版本倒序：v2 在前');
eq(view.snapshots.rows[0].relationText, '替代 v1', 'supersedes 雪花 id 解析为版本号');
eq(view.snapshots.rows[1].relationText, '首版', '首版无替代关系');
eq(view.snapshots.rows[0].asOfText, '2026-10-31', 'asOf 截止日期');
eq(view.snapshots.rows[1].noteText, '—', '空备注占位');
ok(/仅可归档已结束月份/.test(view.snapshots.cutoffNote), '快照卡含归档截止说明');
eq(view.archive.disabled, true, '当月简报归档禁用');
const historicalView = Brief.briefView({
  period: '2026-10', currentPeriod: '2026-11', operating: analytics, snapshots, snapshotsStatus: 'ready'
});
eq(historicalView.mode, 'archive', '历史月 → 归档模式');
eq(historicalView.metrics[0].value, '¥2,500.00', '历史月净流入取 2026-10 行');
eq(historicalView.archive.disabled, false, '历史月归档可用');
eq(historicalView.archive.label, '重新归档（生成新版本）', '已有快照时提示生成新版本');
const missingRow = Brief.briefView({
  period: '2026-04', currentPeriod: '2026-11',
  operating: { trend: [{ period: '2026-11', incomeCents: 1, paidCents: 0, netCents: 1 }], totals: {} },
  snapshots: [], snapshotsStatus: 'ready'
});
eq(missingRow.metrics[0].value, '¥0.00', '趋势缺该月时按 0 计（不复用其它月）');
eq(missingRow.metrics[1].value, '—', 'totals 缺失 → 占位符');
const degradedView = Brief.briefView({ period: '2026-11', currentPeriod: '2026-11', operating: null, snapshots: [], snapshotsStatus: 'error' });
eq(degradedView.degraded, true, 'analytics 失败 → 降级态');
ok(/经营数据/.test(degradedView.degradedText), '降级文案说明经营数据加载失败');
eq(degradedView.metrics.length, 0, '降级态不伪造指标卡');
eq(degradedView.snapshots.status, 'error', '快照拉取失败单独标记');

// 空数据不炸
const emptyView = Brief.briefView({ period: '2026-11', currentPeriod: '2026-11', operating: {}, snapshots: [], snapshotsStatus: 'ready' });
eq(emptyView.trend.length, 0, '空 analytics 不造趋势行');
eq(emptyView.income.totalText, '¥0.00', '空构成合计为 ¥0.00');
eq(emptyView.snapshots.empty, true, '无快照时空态标记');

// 归档错误码映射
ok(/只能归档过去月份/.test(Brief.archiveErrorText({ code: 1116000008 })), '008 → 只能归档过去月份');
ok(/数据无修订/.test(Brief.archiveErrorText({ code: 1116000002 })), '002 → 签名未变化已有快照');
ok(/无权/.test(Brief.archiveErrorText({ code: 1116000000 })), '000 → 越权文案');
eq(Brief.archiveErrorText({ code: 500, msg: '服务端异常' }), '服务端异常', '未识别错误码透传 msg');

// ===================== D. 付款单撤销规则 =====================
eq(PF.canVoidPayment('finance-lead', 'payment', '已核实'), true, 'finance-lead + 已核实付款 → 可撤销');
eq(PF.canVoidPayment('finance', 'payment', '已核实'), false, 'finance 无 payment-void 码 → 不可撤销');
eq(PF.canVoidPayment('boss', 'payment', '已核实'), false, 'boss 仅有 refund-void（V852 矩阵）→ 不可撤销');
eq(PF.canVoidPayment('owner', 'payment', '已核实'), false, 'owner 无 payment-void → 不可撤销');
eq(PF.canVoidPayment('finance-lead', 'refund', '已核实'), false, '退款行不走付款撤销');
eq(PF.canVoidPayment('finance-lead', 'receipt', '已登记'), false, '收款行不走付款撤销');
eq(PF.canVoidPayment('finance-lead', 'payment', '已作废'), false, '已作废行不再显示撤销');
eq(PF.canVoidPayment('', 'payment', '已核实'), false, '无岗位 → 不显示');
eq(PF.voidReasonError(''), '请填写撤销原因', '空原因拒绝');
eq(PF.voidReasonError('   '), '请填写撤销原因', '纯空白原因拒绝');
eq(PF.voidReasonError('x'.repeat(201)), '撤销原因请控制在 200 字以内', '超长原因拒绝');
eq(PF.voidReasonError('银行退回，重复登记'), '', '合法原因通过');
const notice = PF.voidNoticeText({ id: 'p-88', amountCents: 123400 });
ok(notice.indexOf('¥1234.00') >= 0, '联动提示含撤销金额（与台账 PF.yuan 同格式）');
ok(/应付/.test(notice) && /刷新/.test(notice), '联动提示说明应付余额恢复且台账已刷新');
eq(PF.errorMessage({ code: 1102000012 }), '付款记录不存在，请刷新列表', '1102000012 → 付款不存在');
eq(PF.errorMessage({ code: 1102000037 }), '该付款存在有效支出退回，请先作废退回记录', '1102000037 → 有有效退回先作废');

// ===================== E. 代码级接线断言 =====================
const OwnerHome = require('../miniprogram/utils/platform-owner-home');
const ownerHomeView = OwnerHome.view({
  period: '2026-11',
  operating: { trend: [{ period: '2026-11', incomeCents: 0, paidCents: 0, netCents: 0 }], totals: { receivableCents: 500000, payableOutstandingCents: 250000 } }
});
eq(ownerHomeView.ledger.receivableTotal, '¥5,000.00', '首页待收取后端真实字段 receivableCents（原键名缺省导致恒 ——）');
eq(ownerHomeView.ledger.payableTotal, '¥2,500.00', '首页待付=payableOutstandingCents');

const financeApi = read('miniprogram/api/finance.js');
ok(financeApi.includes("http.del('/franchise/finance/payments/' + id"), 'voidPayment 走 DELETE /finance/payments/{id}');
ok(/voidPayment,\s*$|voidPayment,/.test(financeApi), 'module.exports 导出 voidPayment');

const deskJs = read('miniprogram/pages/admin/finance-desk/index.js');
ok(deskJs.includes('canVoidPayment'), '台账行用 platform-finance.canVoidPayment 预计算可撤销位');
ok(deskJs.includes('voidNotice'), '撤销后台账联动提示落 data');

const actionsJs = read('miniprogram/pages/admin/finance-desk/platform-actions.js');
ok(actionsJs.includes('page.openPaymentVoid'), '撤销动作平台注入');
ok(actionsJs.includes('editable: true') || actionsJs.includes('editable:true'), '二次确认使用可编辑弹窗');
ok(actionsJs.includes('voidReasonError'), '原因校验复用纯函数');
ok(actionsJs.includes('financeApi.voidPayment('), '调用 voidPayment');
ok(/this\.loadPlatformFinance\(\)/.test(actionsJs.split('page.openPaymentVoid')[1] || ''), '撤销成功后刷新台账');

const deskWxml = read('miniprogram/pages/admin/finance-desk/index.wxml');
eq(deskWxml.split('bindtap="openPaymentVoid"').length - 1, 1, '撤销按钮唯一（台账行模板）');
ok(deskWxml.includes("wx:if=\"{{item.canVoid}}\""), '撤销按钮按行权限位显示');
ok(deskWxml.includes('电脑端对账'), '用业务名称标记电脑端入口');
const hintAt = deskWxml.indexOf('fin-card fin-web-hint');
const reconcileCardAt = deskWxml.indexOf("<view wx:if=\"{{flowTab==='reconcile'}}\" class=\"fin-card\">");
ok(reconcileCardAt >= 0, 'reconcile tab 月末对账卡存在');
ok(hintAt >= 0 && hintAt < reconcileCardAt, 'Web 协同提示卡位于 reconcile tab 顶部（月末对账操作卡之前）');
ok(deskWxml.slice(hintAt, reconcileCardAt).includes('bindtap="copyFrLink"') && deskWxml.slice(hintAt, reconcileCardAt).includes('在电脑浏览器登录查看'), '提示卡提供可点击的复制入口和登录说明');
ok(deskWxml.includes('fin-void-notice') && deskWxml.includes('voidNotice'), '联动提示卡渲染');

const appJson = JSON.parse(read('miniprogram/app.json'));
const adminPackage = (appJson.subPackages || []).find(pkg => pkg.root === 'pages/admin');
ok(adminPackage && adminPackage.pages.includes('brief/index'), '经营简报页注册进 admin 分包');
for (const ext of ['.js', '.json', '.wxml', '.wxss']) {
  ok(fs.existsSync(path.join(root, 'miniprogram/pages/admin/brief/index' + ext)), '简报页文件存在 ' + ext);
}
const briefJs = read('miniprogram/pages/admin/brief/index.js');
ok(briefJs.includes("Brief.briefView"), '简报页使用纯函数整形');
ok(briefJs.includes('PP.api.operating.analytics'), '当月/历史均直读 analytics（不落快照）');
ok(briefJs.includes('PP.api.operating.snapshots'), '历史月拉取快照列表');
ok(briefJs.includes('PP.api.operating.snapshot('), '归档动作走 operating.snapshot');
ok(briefJs.includes('requestGuard'), '异步守卫防换月/换岗串台');
const briefWxml = read('miniprogram/pages/admin/brief/index.wxml');
ok(briefWxml.includes('bindtap="archive"'), '归档按钮接线');
ok(briefWxml.includes('archive.disabled'), '归档按钮按规则禁用');
ok(briefWxml.includes('snapshots.cutoffNote'), '快照卡展示归档截止说明');
ok(briefWxml.includes('relationText') && briefWxml.includes('versionText') && briefWxml.includes('asOfText'),
  '快照行展示 version/supersedes/asOf');
for (const [file, source] of [['brief/index.wxml', briefWxml], ['finance-desk/index.wxml', deskWxml]]) {
  const bindings = [...source.matchAll(/\{\{([\s\S]*?)\}\}/g)].map(match => match[1]);
  ok(bindings.every(expr => !/[A-Za-z_$][\w$]*\s*\(/.test(expr)), 'WXML 绑定无方法调用（wcc 实编红线）：' + file);
  ok(source.indexOf('.find(') < 0 && source.indexOf('.map(') < 0, 'WXML 无 find()/map() 插值：' + file);
}
const operatingWxml = read('miniprogram/pages/admin/finance/index.wxml');
ok(/data-url="\/pages\/admin\/brief\/index\?period=\{\{desk.period\}\}" bindtap="go"/.test(operatingWxml), '经营页按所选月份打开简报');

// ===================== F. Web 协同链接（P1 §5.4：两处复制动作 + 无凭据） =====================
const FrLink = require('../miniprogram/utils/fr-web-link');
const CLEAN_LINK = 'https://fr.example.com/fr/finance?next=%2Ffr%2Ffinance';
const buildLink = host => FrLink.buildFrFinanceLink({ host });

// r5：单一白名单路径（结构性关闭「解析器比白名单宽松」这一类）
const frSource = read('miniprogram/utils/fr-web-link.js');
ok(!/typeof URL/.test(frSource), 'r5：无 URL/手工双分支（源码不含 typeof URL）');
ok(!/new URL\(|global\.URL/.test(frSource), 'r5：不做 URL 解析，也无 global.URL 依赖');
ok(!/decodedAuthority|decodedHost/.test(frSource), 'r5：无 decodedHost 迭代解码逻辑');
eq(frSource.split('function normalizeHost(').length - 1, 1, 'r5：单一 normalizeHost 实现');
ok(frSource.includes('端口不允许前导零'), 'r5：文档写明端口不允许前导零');
ok(frSource.includes('punycode'), 'r5：文档写明 IDN 需先转 punycode');

// ---- 允许向量（严格白名单内） ----
eq(buildLink('https://fr.example.com'), CLEAN_LINK, '链接形态=host+/fr/finance?next=');
eq(buildLink('https://fr.example.com/'), CLEAN_LINK, '主机尾斜杠归一');
eq(buildLink('https://FR.Example.COM/'), CLEAN_LINK, '主机名小写归一（含尾斜杠）');
eq(buildLink('https://fr.example.com '), CLEAN_LINK, '首尾空白 trim 后合法');
eq(buildLink('https://fr.example.com:443'), CLEAN_LINK, ':443 一律省略（https 默认）');
eq(buildLink('https://fr.example.com:8443'), 'https://fr.example.com:8443/fr/finance?next=%2Ffr%2Ffinance', ':8443 保留');
eq(buildLink('https://fr.example.com:1'), 'https://fr.example.com:1/fr/finance?next=%2Ffr%2Ffinance', ':1 保留');
eq(buildLink('https://fr.example.com:80'), 'https://fr.example.com:80/fr/finance?next=%2Ffr%2Ffinance', ':80 保留');
eq(buildLink('https://fr.example.com:65535'), 'https://fr.example.com:65535/fr/finance?next=%2Ffr%2Ffinance', ':65535 保留');
eq(buildLink('https://localhost:8080'), 'https://localhost:8080/fr/finance?next=%2Ffr%2Ffinance', 'localhost 允许');
eq(buildLink('https://xn--fsqu00a.xn--fiqs8s'), 'https://xn--fsqu00a.xn--fiqs8s/fr/finance?next=%2Ffr%2Ffinance', 'punycode 主机允许');

// ---- 拒绝向量（白名单结构性拒绝；不匹配即空串） ----
const rejectedHosts = [
  ['http://127.0.0.1:8080', '非 https'],
  ['https://fr.example.com/path', '带路径'],
  ['https://fr.example.com?x=1', '带查询'],
  ['https://fr.example.com#x', '带哈希'],
  ['https://user:pass@fr.example.com', 'user:pass'],
  ['https://x.example\\access%54oken=SECRET', '反斜杠+%54oken'],
  ['https://x.example/%54oken', '%54oken 单层编码'],
  ['https://x.example/%2574oken', '%2574oken 双重编码'],
  ['https://x.example/%252574oken', '%252574oken 三层编码'],
  ['https://%E4%BE%8B%E5%AD%90.%E4%B8%AD%E5%9B%BD', '编码 IDN'],
  ['https://%252525E4%BE%8B%E5%AD%90.%E4%B8%AD%E5%9B%BD', '四层编码 IDN'],
  ['https://ex%61mple.com', '编码主机名（含 %）'],
  ['https://t%6fken.example.com', '主机内 %6f 编码'],
  ['https://ｔｏｋｅｎ.example.com', '全角 token'],
  ['https://例子.中国', '中文 IDN（未转 punycode）'],
  ['https://fr_example.com', '下划线主机'],
  ['https://[::1]:8443', 'IPv6 方括号'],
  ['https://-bad.example.com', '主机首字符为连字符'],
  ['https://fr ex ample.com', '主机含空格'],
  ['https://fr.example.com/\u0000x', '控制字符'],
  ['https://', '空主机名'],
  ['https://fr.example.com:0', '端口 0'],
  ['https://fr.example.com:65536', '端口 65536'],
  ['https://fr.example.com:99999', '端口 99999'],
  ['https://fr.example.com:00001', '端口前导零（:00001）'],
  ['https://fr.example.com:0443', '端口前导零（:0443）'],
  ['https://fr.example.com:0080', '端口前导零（:0080）'],
  ['https://fr.example.com:0000001', '端口前导零 + 超长（:0000001）'],
  ['https://fr.example.com:%09443', '端口含 %09 制表符编码'],
  ['https://fr.example.com:443%20', '端口含 %20 尾空格编码'],
  ['https://access-refresh.example.com', 'access-refresh 主机'],
  ['https://access_refresh.example.com', 'access_refresh 主机'],
  ['https://accessRefresh.example.com', 'accessRefresh 主机'],
  ['https://ａｃｃｅｓｓ-ｒｅｆｒｅｓｈ.example.com', '全角 access-refresh 主机'],
  ['', '未配置主机']
];
for (const [host, label] of rejectedHosts) {
  eq(buildLink(host), '', '拒建：' + label + '（' + host + '）');
}

// ---- next 白名单与凭据兜底 ----
eq(FrLink.buildFrFinanceLink({ host: 'https://fr.example.com', next: '/fr/finance?accessToken=abc' }), CLEAN_LINK,
  'next 携带 accessToken → 查询串被剥离');
eq(FrLink.buildFrFinanceLink({ host: 'https://fr.example.com', next: '/fr/finance#refreshToken' }), CLEAN_LINK,
  'next 携带 refreshToken → 哈希被剥离');
eq(FrLink.buildFrFinanceLink({ host: 'https://fr.example.com', next: 'https://evil.example/x' }), CLEAN_LINK,
  '外链 next 回退站内路径');
eq(FrLink.buildFrFinanceLink({ host: 'https://fr.example.com', next: '//evil.example/fr/finance' }), CLEAN_LINK,
  '协议相对 next 回退站内路径');
eq(FrLink.sanitizeNext('/fr/customers'), '/fr/finance', '非白名单路径回退 /fr/finance');
eq(FrLink.sanitizeNext(''), '/fr/finance', '空 next 回退 /fr/finance');
ok(CLEAN_LINK.startsWith('https://fr.example.com/fr/finance?next='), '复制内容前缀契约');
ok(!/token|authorization|cookie|session|secret/i.test(CLEAN_LINK), '序列化后不含 token/accessToken/refreshToken/Authorization');
ok(FrLink.containsCredential('?accessToken=1') && !FrLink.containsCredential('?next=%2Ffr%2Ffinance'), '凭据样片段检测器');
ok(FrLink.containsCredential('access-refresh') && FrLink.containsCredential('access_refresh')
  && FrLink.containsCredential('accessRefresh') && FrLink.containsCredential('ａｃｃｅｓｓ-ｒｅｆｒｅｓｈ'),
  '凭据兜底词表含 access-refresh 各形态');
ok(FrLink.containsCredential('bad%zz'), '非法百分号编码（解码失败）按可疑处理');

// 真机 handler 级：两处复制动作（storage 覆写主机 + 已登录 token，验证凭据不进入链接）
const store = {};
const clipboard = [];
const toasts = [];
const clone = value => JSON.parse(JSON.stringify(value));
function setPath(target, key, value) {
  const parts = key.split('.');
  let node = target;
  for (const part of parts.slice(0, -1)) node = node[part] || (node[part] = {});
  node[parts[parts.length - 1]] = value;
}
function pageInstance(definition) {
  return {
    ...definition,
    data: clone(definition.data || {}),
    setData(patch) {
      for (const [key, value] of Object.entries(patch || {})) setPath(this.data, key, value);
    }
  };
}
global.wx = {
  getStorageSync: key => (store[key] === undefined ? '' : store[key]),
  setStorageSync: (key, value) => { store[key] = value; },
  removeStorageSync: key => { delete store[key]; },
  getStorageInfoSync: () => ({ keys: Object.keys(store) }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getApp: () => ({ globalData: {} }),
  showToast: options => { toasts.push(options && options.title); },
  showModal: options => { if (options && options.success) options.success({ confirm: true, content: '回归验证' }); },
  setClipboardData: options => { clipboard.push(options.data); if (options.success) options.success(); },
  redirectTo() {}, switchTab() {}, reLaunch() {}, navigateTo() {}, pageScrollTo() {}
};
const previousPage = global.Page;
let capturedFinance = null;
global.Page = definition => { capturedFinance = definition; };
require('../miniprogram/pages/admin/finance-desk/index.js');
global.Page = previousPage;
store['zs-fr-host'] = 'https://fr.example.com';
store['zs-api-token-v1'] = 'super-secret-token-value';
store['zs-api-post-v1'] = '42';
store['zs-api-posts-v1'] = [{ postId: '42', roleCode: 'boss' }];
const financePage = pageInstance(capturedFinance);
financePage.copyFrLink();
const ownerPageDef = require('../miniprogram/pages/admin/owner-page')('home');
const ownerPage = pageInstance(ownerPageDef);
ownerPage.copyFrLink();
eq(clipboard.length, 2, '两处（finance-desk reconcile / 老板首页）都执行了复制动作');
eq(clipboard[0], CLEAN_LINK, 'finance-desk 复制内容=host+/fr/finance?next=');
eq(clipboard[1], CLEAN_LINK, '老板首页复制内容与 finance-desk 一致');
eq(clipboard[0].startsWith('https://fr.example.com/fr/finance?next='), true, '复制内容以约定前缀开头');
ok(clipboard.every(text => !/token|authorization|cookie|session/i.test(text)), '复制内容不含任何凭据参数');
ok(clipboard.every(text => text.indexOf('super-secret-token-value') < 0), '已登录 token 值不出现在链接中');
// 未配置主机：不复制、显式提示（不硬编码域名）
delete store['zs-fr-host'];
const clipboardBefore = clipboard.length;
const toastsBefore = toasts.length;
financePage.copyFrLink();
ownerPage.copyFrLink();
eq(clipboard.length, clipboardBefore, '未配置主机时不写入剪贴板');
ok(toasts.slice(toastsBefore).every(title => /未配置/.test(String(title))), '未配置主机时提示配置入口');
ok(read('miniprogram/config/env.js').includes("FR_HOST: ''"), '集中配置项 FR_HOST 默认留空（无硬编码生产域名）');
eq(deskWxml.split('bindtap="copyFrLink"').length - 1, 1, 'finance-desk reconcile 顶部复制按钮唯一');
ok(read('miniprogram/pages/admin/finance-desk/journey.wxml').includes('bindtap="copyFrLink">在电脑端核对'), '门店财务对账页也可复制电脑入口');

// ===================== G. 归档迟到回调上下文守卫（P2-1，VM） =====================
(async () => {
const PP = require('../miniprogram/utils/platform-performance');
const httpApi = require('../miniprogram/api/http');
const briefPath = require.resolve('../miniprogram/pages/admin/brief/index.js');
const originals = {
  analytics: PP.api.operating.analytics,
  snapshots: PP.api.operating.snapshots,
  snapshot: PP.api.operating.snapshot
};
let analyticsCalls = 0;
let pendingArchive = null;
const pendingArchives = [];
PP.api.operating.analytics = () => { analyticsCalls += 1; return Promise.resolve({ trend: [], totals: {} }); };
PP.api.operating.snapshots = () => Promise.resolve({ snapshots: [] });
PP.api.operating.snapshot = () => new Promise((resolve, reject) => {
  pendingArchive = resolve;
  pendingArchives.push({ resolve, reject });
});
let capturedBrief = null;
global.Page = definition => { capturedBrief = definition; };
delete require.cache[briefPath];
require(briefPath);
global.Page = previousPage;
const tick = () => new Promise(resolve => setTimeout(resolve, 0));

store['zs-api-token-v1'] = 'session-token';
store['zs-api-post-v1'] = '42';
store['zs-api-posts-v1'] = [{ postId: '42', roleCode: 'boss' }, { postId: '43', roleCode: 'boss' }];

async function archiveThenSwitch(switchContext) {
  const page = pageInstance(capturedBrief);
  page.onLoad({ period: '2026-10' });
  page.load();
  await tick();
  await tick();
  ok(page.data.brief && page.data.brief.archive.disabled === false, '过去月简报就绪且归档可用');
  toasts.length = 0;
  const callsBefore = analyticsCalls;
  page.archive();
  ok(typeof pendingArchive === 'function', '归档请求已发出（挂起中）');
  switchContext(page);
  await tick();
  await tick();
  pendingArchive();
  await tick();
  await tick();
  return { page, callsBefore };
}

// G1 换月：旧归档回调必须丢弃
const monthCase = await archiveThenSwitch(page => page.month({ detail: { value: '2026-09' } }));
eq(monthCase.page.data.period, '2026-09', '换月后当前月份=2026-09');
eq(monthCase.page.data.archiveNotice, '', '换月后旧归档成功提示不写入新页面');
eq(monthCase.page.data.archiveBusy, false, '换月后不残留 busy');
ok(toasts.indexOf('快照已归档') < 0, '换月后不弹旧归档 toast');
eq(analyticsCalls - monthCase.callsBefore, 1, '旧归档回调不触发额外 load（仅换月本身 1 次）');

// G2 换岗：会话代次变化同样丢弃
const postCase = await archiveThenSwitch(() => { httpApi.saveSession({ postId: '43' }); });
eq(String(httpApi.currentPostId()), '43', '换岗后当前岗位=43');
eq(postCase.page.data.archiveNotice, '', '换岗后旧归档成功提示不写入');
ok(toasts.indexOf('快照已归档') < 0, '换岗后不弹旧归档 toast');
eq(postCase.page.data.archiveBusy, false, 'r2 P2-2：换岗后旧回调释放 busy（不卡死）');

// G2b 仅会话代次变化（同岗位）：同样丢弃且释放 busy
const sessionCase = await archiveThenSwitch(() => { httpApi.saveSession({ postId: '43' }); });
eq(sessionCase.page.data.archiveNotice, '', '仅会话代次变化时旧提示不写入');
eq(sessionCase.page.data.archiveBusy, false, 'r2 P2-2：仅会话代次变化时 busy 同样释放');
ok(toasts.indexOf('快照已归档') < 0, '仅会话代次变化时不弹旧 toast');

// G3 正常路径（对照）：未换上下文时必须写入提示，证明守卫不误杀
const okPage = pageInstance(capturedBrief);
okPage.onLoad({ period: '2026-10' });
okPage.load();
await tick();
await tick();
toasts.length = 0;
okPage.archive();
pendingArchive();
await tick();
await tick();
eq(okPage.data.archiveNotice, '已归档 2026年10月 快照', '未换上下文时正常写入归档提示');
ok(toasts.indexOf('快照已归档') >= 0, '未换上下文时正常弹归档 toast');

// ===================== H. 付款撤销回调岗位/会话守卫（r2 P2-3，VM） =====================
const attachVoidActions = require('../miniprogram/pages/admin/finance-desk/platform-actions');
function mergeData(target, patch) {
  for (const [key, value] of Object.entries(patch || {})) setPath(target, key, value);
}
function makeVoidPage(api) {
  let loads = 0;
  const page = {
    data: {
      busy: false, registerBusy: false, voidNotice: '', ledgerError: '',
      platformLedger: [{ id: 'p-9', amountCents: 123400, status: '已核实', sourceKind: 'payment', canVoid: true }],
      ledgerSource: []
    },
    setData(patch) { mergeData(this.data, patch); },
    loadPlatformFinance() { loads += 1; }
  };
  attachVoidActions(page, api, PF);
  return { page, loads: () => loads };
}
let pendingVoid = null;
let pendingVoidReject = null;
const fakeVoidApi = {
  idemKey: () => 'void-idem-key',
  voidPayment: () => new Promise((resolve, reject) => {
    pendingVoid = resolve;
    pendingVoidReject = reject;
  })
};

// H1 换岗：旧撤销成功回调必须静默丢弃（无提示/无 toast/不刷新）
store['zs-api-post-v1'] = '42';
store['zs-api-posts-v1'] = [{ postId: '42', roleCode: 'finance-lead' }, { postId: '43', roleCode: 'finance-lead' }];
toasts.length = 0;
const voidCase = makeVoidPage(fakeVoidApi);
voidCase.page.openPaymentVoid({ currentTarget: { dataset: { id: 'p-9' } } });
eq(voidCase.page.data.busy, true, '撤销请求在途时 busy=true');
ok(typeof pendingVoid === 'function', '撤销请求已发出（挂起中）');
httpApi.saveSession({ postId: '43' });
pendingVoid();
await tick();
await tick();
eq(voidCase.page.data.voidNotice, '', 'r2 P2-3：换岗后旧撤销不写联动提示');
ok(toasts.indexOf('付款已撤销') < 0, 'r2 P2-3：换岗后不弹旧撤销 toast');
eq(voidCase.loads(), 0, 'r2 P2-3：换岗后旧回调不刷新 B 的台账');
eq(voidCase.page.data.busy, false, 'r2 P2-3：换岗后旧回调释放 busy');

// H1b 换岗 + 失败分支（r3）：旧失败回调不写错误、不刷新、释放 busy
store['zs-api-post-v1'] = '42';
toasts.length = 0;
const voidFailCase = makeVoidPage(fakeVoidApi);
voidFailCase.page.openPaymentVoid({ currentTarget: { dataset: { id: 'p-9' } } });
httpApi.saveSession({ postId: '43' });
pendingVoidReject({ code: 1102000037 });
await tick();
await tick();
eq(voidFailCase.page.data.ledgerError, '', 'r3：换岗后旧撤销失败不写错误提示');
eq(voidFailCase.page.data.voidNotice, '', 'r3：换岗后旧撤销失败不写联动提示');
eq(voidFailCase.loads(), 0, 'r3：换岗后旧失败回调不刷新台账');
eq(toasts.length, 0, 'r3：换岗后旧失败回调不弹 toast');
eq(voidFailCase.page.data.busy, false, 'r3：换岗后旧失败回调释放 busy');

// H2 正常路径（对照）：上下文未变时提示/toast/刷新均发生
store['zs-api-post-v1'] = '43';
toasts.length = 0;
const voidOk = makeVoidPage(fakeVoidApi);
voidOk.page.openPaymentVoid({ currentTarget: { dataset: { id: 'p-9' } } });
pendingVoid();
await tick();
await tick();
ok(/付款已撤销/.test(voidOk.page.data.voidNotice), '同上下文时正常写入撤销提示');
ok(toasts.indexOf('付款已撤销') >= 0, '同上下文时正常弹撤销 toast');
eq(voidOk.loads(), 1, '同上下文时刷新台账一次');
eq(voidOk.page.data.busy, false, '同上下文时 busy 复位');

// G4 A/B 交错（r3 P2-1）：A 迟到（成功或失败）不得清 B 的 busy、不写通知
async function interleavedArchives(aFails) {
  const page = pageInstance(capturedBrief);
  page.onLoad({ period: '2026-10' });
  page.load();
  await tick();
  await tick();
  pendingArchives.length = 0;
  toasts.length = 0;
  page.archive(); // A
  eq(pendingArchives.length, 1, 'A 已发出');
  page.month({ detail: { value: '2026-09' } }); // 离开原月
  await tick();
  await tick();
  page.month({ detail: { value: '2026-10' } }); // 回到原月：stillHere 对 A 重新成立
  await tick();
  await tick();
  page.archive(); // B
  eq(pendingArchives.length, 2, 'B 已发出');
  eq(page.data.archiveBusy, true, 'B 在途时 busy=true');
  const label = aFails ? '失败' : '成功';
  if (aFails) pendingArchives[0].reject(new Error('A 归档失败'));
  else pendingArchives[0].resolve();
  await tick();
  await tick();
  eq(page.data.archiveBusy, true, 'A 迟到（' + label + '）不清 B 的 busy');
  eq(page.data.archiveNotice, '', 'A 迟到不写成功提示');
  eq(page.data.archiveError, '', 'A 迟到不写错误提示');
  ok(toasts.indexOf('快照已归档') < 0, 'A 迟到不弹归档 toast');
  pendingArchives[1].resolve();
  await tick();
  await tick();
  eq(page.data.archiveBusy, false, 'B 返回后 busy=false');
  eq(page.data.archiveNotice, '已归档 2026年10月 快照', 'B 正常写入提示');
  ok(toasts.indexOf('快照已归档') >= 0, 'B 正常弹归档 toast');
}
await interleavedArchives(false);
await interleavedArchives(true);

PP.api.operating.analytics = originals.analytics;
PP.api.operating.snapshots = originals.snapshots;
PP.api.operating.snapshot = originals.snapshot;

console.log('verify-operating-brief-payment-void: ' + passed + ' 断言全部 PASS');
})().catch(err => {
  console.error(err && err.stack || err);
  process.exit(1);
});
