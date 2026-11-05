#!/usr/bin/env node
/**
 * 批次#36 · M07-T04 工程凭据上传链规则（COS 前）R2 版。
 * A 段（真实现）：uploadVoucher 真函数 + 仅 mock 文件系统(wx)/http 传输边界——
 *   SHA 对原始字节（NIST 'abc' 向量经真实现全链比对）、complete REJECTED 拒绝、
 *   ACCEPTED 返回、响应丢失重试复用缓存资产（不再签票）、无文件拒绝。
 * B 段（perform 编排）：mock api.uploadVoucher 驱动 perform——十动作凭据接线、
 *   deliver 单次上传双引用、Σ 先于上传、拦截摘除。
 * C 段（recordView 逐 view）：start/node/delivery/collection(collect)/material(arrival)/prepare
 *   六视图 fields/actions 恢复断言（含角色门禁与状态门禁）。
 * D 段（静态）：downloadTicket 端点+消费链、后端 collect/site/arrival 域侧校验、finance 工具导出。
 * 用法：node scripts/verify-engineering-voucher-chain.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const engApi = require('../miniprogram/api/franchise/engineering');
const P = require('../miniprogram/utils/platform-engineering');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');

// ===== wx / http 边界桩（仅 mock 传输，不 mock 业务实现） =====
const FILES = { '/tmp/v.jpg': 'YWJj' }; // 'abc' 的 base64——NIST 向量
const ABC_SHA = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';
const httpLog = { posts: [] };
let completeStatus = 'ACCEPTED';
globalThis.wx = {
  getFileSystemManager: () => ({
    readFile: ({ filePath, success }) => success({ data: FILES[filePath] || '' }),
    statSync: p => ({ size: 3 })
  }),
  getStorageSync: () => ({}), setStorageSync: () => {} // R2 P2-8：access.context 组织维度读取（缓存键）
};
const httpApi = require('../miniprogram/api/http');
const realPost = httpApi.post;
httpApi.post = (p2, data) => {
  httpLog.posts.push({ path: String(p2), data });
  if (String(p2).indexOf('/upload-tickets') >= 0) return Promise.resolve({ assetId: 88001 });
  if (String(p2).indexOf('/bytes') >= 0) return Promise.resolve(true);
  if (String(p2).indexOf('/complete') >= 0) {
    if (completeStatus === 'REJECTED') return Promise.resolve({ uploadStatus: 'REJECTED', rejectedReason: 'SHA_MISMATCH: 实际内容与声明 SHA-256 不一致' });
    return Promise.resolve({ assetId: 88001, uploadStatus: 'ACCEPTED', rejectedReason: '' });
  }
  if (String(p2).indexOf('/download-tickets') >= 0) return Promise.resolve({ downloadUrl: 'local://dev/88001', expiresAt: null });
  return Promise.resolve({});
};

async function main() {
  // ===== A 段：真实现 =====
  // A1：SHA 对原始字节——ticket 请求的 sha256=NIST 'abc' 向量（base64 文本哈希必不符）
  const got = await engApi.uploadVoucher('/tmp/v.jpg', 'ENG_DRAWING');
  assert.equal(got, 88001, 'A1 ACCEPTED 返回 assetId');
  const ticketReq = httpLog.posts.find(p2 => p2.path.indexOf('/upload-tickets') >= 0);
  assert.equal(ticketReq.data.sha256, ABC_SHA, 'A1 票据 SHA=原始字节摘要（NIST abc 向量）');
  assert.equal(ticketReq.data.assetType, 'ENG_DRAWING', 'A1 动作类型透传');
  assert.ok(httpLog.posts.some(p2 => p2.path.indexOf('/88001/bytes') >= 0), 'A1 字节通道调用');
  assert.ok(httpLog.posts.some(p2 => p2.path.indexOf('/88001/complete') >= 0), 'A1 complete 调用');

  // A2：complete REJECTED——不返回 assetId、报拒绝原因
  completeStatus = 'REJECTED';
  await assert.rejects(engApi.uploadVoucher('/tmp/v2.jpg', 'ENG_SITE_PHOTO'),
    e => /SHA_MISMATCH/.test(e.msg) && /校验未通过/.test(e.msg), 'A2 REJECTED 报拒绝原因');

  // A3：响应丢失重试——同路径同内容复用缓存（不再签票；A2 REJECTED 不缓存属正确语义）
  completeStatus = 'ACCEPTED';
  const ticketsBefore = httpLog.posts.filter(p2 => p2.path.indexOf('/upload-tickets') >= 0).length;
  const again = await engApi.uploadVoucher('/tmp/v.jpg', 'ENG_DRAWING');
  assert.equal(again, 88001, 'A3 重试返回同 assetId');
  const ticketsAfter = httpLog.posts.filter(p2 => p2.path.indexOf('/upload-tickets') >= 0).length;
  assert.equal(ticketsAfter, ticketsBefore, 'A3 重试不再签票（deliver 指纹含资产 ID——同键重放合法的前提）');

  // A4：无文件拒绝
  await assert.rejects(engApi.uploadVoucher('', 'ENG_DRAWING'), e => /请选择凭据文件/.test(e.msg), 'A4 无文件拒绝');

  // A5（R2 P2-9）：REJECTED 后同路径重传——不缓存语义验证（同路径新内容可重新签票且成功）
  FILES['/tmp/v2.jpg'] = 'Yg=='; // 'b'（1 字节——尾余位解码路径）
  completeStatus = 'ACCEPTED';
  const ticketsBefore5 = httpLog.posts.filter(p2 => p2.path.indexOf('/upload-tickets') >= 0).length;
  const got5 = await engApi.uploadVoucher('/tmp/v2.jpg', 'ENG_SITE_PHOTO');
  assert.equal(got5, 88001, 'A5 REJECTED 同路径新内容重传成功');
  const ticket5 = httpLog.posts.filter(p2 => p2.path.indexOf('/upload-tickets') >= 0).slice(-1)[0];
  assert.equal(ticket5.data.sha256, '3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d', 'A5 单字节原始字节 SHA（b）');
  assert.ok(ticket5.data.assetType === 'ENG_SITE_PHOTO', 'A5 类型透传');
  const ticketsAfter5 = httpLog.posts.filter(p2 => p2.path.indexOf('/upload-tickets') >= 0).length;
  assert.equal(ticketsAfter5, ticketsBefore5 + 1, 'A5 REJECTED 后重传新签票');

  // ===== B 段：perform 编排（mock 编排层上传） =====
  const uploads = [];
  const calls = { start: [], deliver: [], collect: [] };
  engApi.uploadVoucher = (filePath, assetType) => {
    if (!filePath) throw { code: 0, msg: '请选择凭据文件' };
    uploads.push({ path: filePath, assetType });
    return Promise.resolve(88000 + uploads.length);
  };
  engApi.plan.start = (id, body) => { calls.start.push(body); return Promise.resolve({}); };
  engApi.closure.deliver = (id, body) => { calls.deliver.push(body); return Promise.resolve({}); };
  engApi.collections.register = (id, body) => { calls.collect.push(body); return Promise.resolve({}); };
  engApi.plan.complete = () => Promise.resolve({});
  engApi.plan.correct = () => Promise.resolve({});
  engApi.site.create = () => Promise.resolve({});
  engApi.materials.arrival = () => Promise.resolve({});
  const form = extra => Object.assign({ file: { path: '/tmp/v.jpg', name: 'v.jpg' }, note: 'n', date: '2026-11-05' }, extra);

  await P.perform('start', 501, form(), {}, 'k1');
  assert.deepEqual(uploads[0], { path: '/tmp/v.jpg', assetType: 'ENG_DRAWING' }, 'B1 start 类型/接线');
  assert.equal(calls.start[0].fileAssetId, 88001, 'B1 fileAssetId 透传');

  const n0 = uploads.length;
  await P.perform('deliver', 501, form(), {}, 'k2');
  assert.equal(uploads.length - n0, 1, 'B2 deliver 单次上传');
  assert.equal(calls.deliver[0].ownerAssetId, calls.deliver[0].fileAssetId, 'B2 双引用同 assetId');

  await P.perform('collect', 501, form({ stage: 'base', amount: '100', transaction: 'TXN-C1' }), {}, 'k3');
  assert.equal(calls.collect[0].stage, 'BASE_DONE', 'B3 collect 枚举');
  assert.ok(calls.collect[0].fileAssetId >= 88001, 'B3 collect 凭据接线');

  await assert.rejects(P.perform('start', 501, { date: '2026-11-05' }, {}, 'k4'),
    e => /请选择凭据文件/.test(e.msg), 'B4 无文件不发起业务');
  assert.equal(calls.start.length, 1, 'B4 业务未发起');

  try { await P.perform('start', 501, form(), {}, 'k5'); } catch (e) {
    assert.ok(!/随 COS 批开放/.test(e && e.msg || ''), 'B5 拦截已摘');
  }

  const n1 = uploads.length;
  await assert.rejects(P.perform('prepare', 501,
    { total: '10', signAfter: '6', baseDone: '2', capDone: '1', file: { path: '/tmp/x.jpg' } }, {}, 'k6'),
    e => /等于合同总额/.test(e.msg), 'B6 Σ 差额先报');
  assert.equal(uploads.length, n1, 'B6 Σ 错不触达上传');

  // ===== C 段：recordView 逐 view =====
  const pf = read('miniprogram/utils/platform-engineering.js');
  const mkBundle = over => {
    const nodes = (over && over.nodes) || [
      { id: '1', nodeIndex: 1, name: '基础', startDate: '2026-11-01', endDate: '2026-11-20', status: 'DOING', milestone: '' },
      { id: '2', nodeIndex: 2, name: '封顶', startDate: '2026-11-21', endDate: '2026-12-01', status: 'PENDING', milestone: 'TOPPING' }
    ];
    const bundle = {
      detail: { id: 5, name: 'x', status: 'PENDING_START', nodes,
        closure: (over && over.closure) || {}, actualStartAt: (over && over.actualStartAt) || null,
        contractTotalCents: 68000000 },
      collections: { stages: (over && over.stages) || [
        { stage: 'SIGN_AFTER', expectedCents: 40000000, confirmedCents: 0, remainingCents: 40000000 },
        { stage: 'BASE_DONE', expectedCents: 18000000, confirmedCents: 0, remainingCents: 18000000 },
        { stage: 'CAP_DONE', expectedCents: 10000000, confirmedCents: 0, remainingCents: 10000000 }],
        records: (over && over.records) || [] },
      materials: { plans: (over && over.plans) || [] }, settle: {}, followups: []
    };
    const ret = { detail: bundle.detail, collections: bundle.collections, materials: bundle.materials, settle: {}, followups: [] };
    ret.detail.siteRecords = (over && over.siteRecords) || [];
    return ret;
  };
  const ctx = (view, extra) => Object.assign({ view, stage: 'start', nodeId: '', planId: '', form: {},
    initialized: false, today: '2026-11-06', writeRole: true, financeRole: false }, extra);

  const rvStart = P.recordView(mkBundle(), ctx('start'));
  assert.equal(rvStart.actions.length, 1, 'C1 start 动作恢复');
  assert.ok(rvStart.fields.some(f => f.key === 'file'), 'C1 start 凭据字段');

  const rvPrep = P.recordView(mkBundle(), ctx('prepare'));
  assert.equal(rvPrep.actions.length, 1, 'C2 prepare 未开工出提交');
  const rvPrepStarted = P.recordView(mkBundle({ actualStartAt: '2026-11-02' }), ctx('prepare'));
  assert.equal(rvPrepStarted.actions.length, 0, 'C2 已开工不出（R1 P2-7 actualStart 适配）');

  const rvNodeDoing = P.recordView(mkBundle(), Object.assign(ctx('node'), { nodeId: '1' }));
  assert.equal(rvNodeDoing.actions.length, 2, 'C3 未完成节点=完成+现场照片（R2 P1-4）');
  assert.ok(rvNodeDoing.actions.some(a => a.action === 'site'), 'C3 site 动作在列');
  const rvNodeDone = P.recordView(mkBundle({ nodes: [
    { id: '1', nodeIndex: 1, name: '基础', startDate: '2026-11-01', endDate: '2026-11-20', status: 'DONE', milestone: '' }]}),
    Object.assign(ctx('node'), { nodeId: '1' }));
  assert.equal(rvNodeDone.actions.length, 1, 'C3 已完成节点=更正表单');

  const rvDeliver = P.recordView(mkBundle(), ctx('delivery'));
  assert.equal(rvDeliver.actions.length, 1, 'C4 delivery 动作恢复');
  const rvDelivered = P.recordView(mkBundle({ closure: { deliveredAt: '2026-11-03' } }), ctx('delivery'));
  assert.equal(rvDelivered.actions.length, 0, 'C4 已交付不出');

  const bundleFin = mkBundle({ records: [{ id: 9, stage: 'SIGN_AFTER', amountCents: 100, fileAssetId: 88001 }] });
  const rvCollect = P.recordView(bundleFin, Object.assign(ctx('collection'), { financeRole: true }));
  assert.equal(rvCollect.actions.length, 1, 'C5 finance 岗收款登记恢复');
  assert.ok(rvCollect.fields.some(f => f.key === 'file'), 'C5 收款凭据字段');
  const rvDunning = P.recordView(bundleFin, ctx('collection'));
  assert.ok(rvDunning.actions.every(a => a.action !== 'collect'), 'C5 非 finance 岗不出收款');
  assert.ok(P.pageData(bundleFin).p.collections[0].fileAssetId === 88001, 'C5 凭据 assetId 透出（授权查看数据源）');

  const bundleArr = mkBundle({ plans: [{ materialPlanId: 7, arrival: '部分到货', totalCents: 1, paidCents: 0, remainingCents: 1,
    lines: [{ materialId: 8, name: '钢筋', quantity: 10, received: 4, remaining: 6, unit: '吨', supplyId: 770 }] }] });
  const rvArrival = P.recordView(bundleArr, Object.assign(ctx('material'), { planId: '7' }));
  assert.equal(rvArrival.actions.length, 1, 'C6 arrival 动作恢复');
  assert.ok(rvArrival.fields.some(f => f.key === 'file'), 'C6 到货凭据字段');
  assert.equal(String(rvArrival.form.supplyId), '770', 'C7 默认选首个待到货行（R4 P1-2 显式选定+picker 可改）');
  assert.ok(Array.isArray(rvArrival.form.supplyOptions) && rvArrival.form.supplyOptions.length === 1, 'C7 supplyOptions（余量>0 行）');
  const rvTwo = P.recordView(mkBundle({ plans: [{ materialPlanId: 7, arrival: '部分到货', totalCents: 1, paidCents: 0, remainingCents: 1,
    lines: [{ materialId: 8, name: '首行', quantity: 5, received: 0, remaining: 5, unit: '吨', supplyId: 770 },
            { materialId: 9, name: '次行', quantity: 5, received: 0, remaining: 5, unit: '吨', supplyId: 771 }] }] }),
    Object.assign(ctx('material'), { planId: '7' }));
  assert.equal(rvTwo.form.supplyOptions.length, 2, 'C7b 两行待到货双选项（显式选定——不再自动错指）');
  assert.ok(rvTwo.fields.some(f => f.type === 'supply'), 'C7b 供应事项 picker 字段');
  // C7c（R5 P1-2）：刷新三态——手选保留/落选回落（选项每次按最新余量重建）
  const L3 = [{ materialId: 8, name: '钢筋', quantity: 5, received: 5, remaining: 0, unit: '吨', supplyId: 770 },
    { materialId: 9, name: '水泥', quantity: 5, received: 0, remaining: 5, unit: '吨', supplyId: 771 },
    { materialId: 10, name: '砂石', quantity: 3, received: 0, remaining: 3, unit: '吨', supplyId: 772 }];
  const rvInit = P.recordView(mkBundle({ plans: [{ materialPlanId: 7, arrival: '部分到货', totalCents: 1, paidCents: 0, remainingCents: 1, lines: L3 }] }),
    Object.assign(ctx('material'), { planId: '7' }));
  assert.equal(rvInit.form.supplyId, '771', 'C7c 初始选首个待到货行');
  const form2 = { ...rvInit.form, supplyId: '772', supplyLabel: '砂石 · 余 3吨' };
  const rvKeep = P.recordView(mkBundle({ plans: [{ materialPlanId: 7, arrival: '部分到货', totalCents: 1, paidCents: 0, remainingCents: 1, lines: L3 }] }),
    Object.assign(ctx('material'), { planId: '7', form: form2, initialized: true }));
  assert.equal(rvKeep.form.supplyId, '772', 'C7c 刷新保留仍有效的用户选择（不再指回首行）');
  const rvFall = P.recordView(mkBundle({ plans: [{ materialPlanId: 7, arrival: '部分到货', totalCents: 1, paidCents: 0, remainingCents: 1,
    lines: [L3[0], L3[1], { ...L3[2], received: 3, remaining: 0 }] }] }),
    Object.assign(ctx('material'), { planId: '7', form: form2, initialized: true }));
  assert.equal(rvFall.form.supplyId, '771', 'C7c 落选行回落剩余待到货行');
  assert.ok(String(bundleArr.materials.plans[0].lines[0].supplyId) === '770', 'C7 行级 supplyId 源');

  // C8（R2 P1-4）：现场记录链——site 动作+siteRecords 透出+更正入口
  const bundleSite = mkBundle({ siteRecords: [{ id: 900, node_id: '1', occurred_at: '2026-11-05T10:00:00+08:00',
    note: '现场', completion: false, file_asset_ids: '[901]', corrections: '[]' }] });
  const rvSite = P.recordView(bundleSite, Object.assign(ctx('node'), { nodeId: '1' }));
  assert.ok(rvSite.actions.some(a => a.action === 'site'), 'C8 现场照片动作恢复');
  assert.equal(rvSite.siteRecords.length, 1, 'C8 现场记录透出');
  assert.equal(rvSite.siteRecords[0].canCorrect, true, 'C8 更正入口');
  assert.equal(rvSite.siteRecords[0].files[0].assetId, 901, 'C8 凭据 assetId（授权查看数据源）');
  // C9（R2 P1-2）：收款凭据走 FIN_VOUCHER
  assert.ok(pf.indexOf("await uploadVoucher(f.file && f.file.path, 'FIN_VOUCHER')") > 0, 'C9 collect 改 FIN_VOUCHER 通道');
  const beColl2 = fs.readFileSync('/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/content/EngCollectionService.java', 'utf8');
  assert.ok(beColl2.includes('"FIN_VOUCHER"), "收款"'), 'C9 后端 collect 白名单含 FIN_VOUCHER');

  // ===== D 段：静态 =====
  const apiSrc = read('miniprogram/api/franchise/engineering.js');
  assert.ok(apiSrc.includes("'/download-tickets'"), 'D1 downloadTicket 端点');
  assert.ok(apiSrc.includes("uploadStatus !== 'ACCEPTED'"), 'D1 complete 状态检查（REJECTED 拒绝）');
  assert.ok(apiSrc.includes('base64ToBinary'), 'D1 原始字节解码（SHA 语义）');
  assert.ok(/voucherCache/.test(apiSrc), 'D1 重试缓存');
  assert.ok(apiSrc.includes("http.post('/platform/dev/design/assets/'"), 'D1 字节通道走统一 http（身份头由封装注入）');

  assert.ok(!pf.includes('VOUCHER_ACTIONS.includes(action)'), 'D2 拦截已摘');
  assert.ok(pf.includes("!p.actualStart) actions = [{ action: 'prepare', label: '提交开工准备' }"), 'D2 prepare 门禁字段');
  assert.ok(pf.includes('const financeRole = !!opts.financeRole'), 'D2 finance 角色位');
  assert.ok(pf.includes('fileAssetId: r.fileAssetId || null'), 'D2 收款记录 assetId 透出');

  const pageJs = read('miniprogram/pages/build/record/index.js');
  assert.ok(pageJs.includes("financeRole:O.has(X.context().role,'finance')"), 'D3 页面传 finance 岗');
  assert.ok(pageJs.includes('previewAsset'), 'D3 授权查看 handler');
  const wxml = read('miniprogram/pages/build/record/index.wxml');
  assert.ok(wxml.includes('previewAsset') && wxml.includes('data-asset-id'), 'D3 消费链按钮');

  const beDir = '/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/content/';
  const beColl = fs.readFileSync(beDir + 'EngCollectionService.java', 'utf8');
  assert.ok(beColl.includes('planService.requireValidAsset(post, cmd.fileAssetId()'), 'D4 后端 collect 凭据校验');
  const beSite = fs.readFileSync(beDir + 'EngSiteRecordService.java', 'utf8');
  assert.ok(/for \(Long assetId : cmd.fileAssetIds\(\)\)/.test(beSite), 'D4 后端 site 逐张校验');
  const beMat = fs.readFileSync(beDir + 'EngMaterialService.java', 'utf8');
  assert.ok(beMat.includes('requireValidAsset(post, cmd.fileAssetId(),'), 'D4 后端 arrival 凭据校验');
  const mig = fs.readFileSync('/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-org/src/main/resources/db/migration/franchise-system/V20261105.858__eng_voucher_design_use.sql', 'utf8');
  assert.ok(mig.includes('franchise:asset:bytes'), 'D4 窄码迁移（R2 P1-1：不授 design:use——AI 面不扩大）');
  assert.ok(!mig.includes("'franchise:design:use'"), 'D4 确认未授 design:use（授权字面量级检查）');
  const beProj = fs.readFileSync('/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/content/EngProjectService.java', 'utf8');
  assert.ok(beProj.includes('siteRecords'), 'D4 detail 透出现场记录（R2 P1-4 数据源）');
  const beSite2 = fs.readFileSync('/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/domains/franchise/franchise-engineering/src/main/java/cn/iocoder/yudao/module/franchise/engineering/content/EngSiteRecordService.java', 'utf8');
  assert.ok(beSite2.includes('VALUES (?,?,?,?,?,?,FALSE,CAST(? AS jsonb),?,now())'), 'D4 site INSERT 列值修正（R2 P1-6）');
  assert.ok(beMat.includes('planService.requireValidAsset(post, resolutionAssetId,'), 'D5 质检处置凭据无条件校验（R3 P1-5）');
  const devCtl = fs.readFileSync('/Users/honor.pei/Documents/企业FDE项目/众墅之家AI赋能平台/平台程序/yudao-server/src/main/java/cn/iocoder/yudao/server/dev/DesignDevController.java', 'utf8');
  assert.ok(devCtl.includes("@post.has('franchise:design:use') or @post.has('franchise:asset:bytes')"), 'D5 PUT 双通路（R3 P1-2：853 三岗原通路保持+858 四岗窄码）');
  const getSeg = devCtl.slice(devCtl.indexOf('@GetMapping("/assets/{assetId}/bytes")'), devCtl.indexOf('public CommonResult<Map<String, Object>> getBytes') + 80);
  assert.ok(getSeg.includes("@post.has('franchise:design:use')") && !getSeg.includes('asset:bytes'),
    'D5b GET bytes 撤四岗（R4 P1-1：读取仅 design:use——防组织内横向读取 FIN_VOUCHER 等跨类资产）');
  assert.ok(apiSrc.includes("method: 'PUT'") && apiSrc.includes('ticket.uploadUrl'), 'D6 生产 COS PUT 分支（R3 P1-3/20 号契约 §2）');
  assert.ok(apiSrc.includes('/platform/dev/design/assets/'), 'D6 local dev bytes 分支保留');
  assert.ok(wxml.includes('wx:for="{{item.files}}"'), 'D7 多图逐张查看（R3 P2-5）');

  console.log('verify-engineering-voucher-chain: A 真实现 5 + B 编排 6 + C 视图 17 + D 静态 23 = 57 断言 全部 PASS');
}

main().catch(e => { console.error(e && (e.msg || e.message) || e); process.exit(1); });
