#!/usr/bin/env node
/**
 * 报价导出平台快照链静态验证（Codex R7 P2 适配重写）：
 * 旧版断言本地报价链（无 quote-id 的本地表单/演示登录/预算入口死按钮）已随切生产收口下线；
 * 现契约=quote-id 必填、exportSheet 冻结快照渲染、导出动作三件套。
 * 原生 Canvas/PDF 渲染验证（verify-quote-export-native.cjs）另跑，需微信开发者工具环境。
 * 用法：node scripts/verify-quote-export.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

const js = read('miniprogram/pages/workflow/quote-export/index.js');
const wxml = read('miniprogram/pages/workflow/quote-export/index.wxml');
const entry = read('miniprogram/pages/workflow/index.js');
const sheet = read('miniprogram/pages/workflow/quote-export/sheet.js');

// 1) 纯平台快照态：无 quote-id 即错误态，本地报价链不得复活
assert(js.includes("this.quoteId=o['quote-id']"), 'quote-export 页从 URL 参数取 quoteId');
assert(/if\(!this\.quoteId\)/.test(js), '无 quote-id 显示错误态（不回落本地报价）');
assert(!js.includes("require('../../../utils/business')"), '本地报价链（utils/business）不得再被导出页引用');
assert(!js.includes('loginDemo'), '不得引用已下线的演示登录');

// 2) 平台数据源与一致性校验保留
assert(js.includes('export-sheet'), '数据源为 /export-sheet 冻结快照端点');
assert(js.includes('报价分项与小计不一致') && js.includes('报价分项与总价不一致'), '分项/小计/总价一致性校验保留');
assert(js.includes('分项超出打印模板容量'), '打印模板容量上限校验保留');

// 3) 导出动作三件套与 canvas 管线
assert(js.includes('exportImage') && js.includes('exportPdf') && js.includes('sharePdf'), '图片/PDF/分享导出动作保留');
assert(js.includes('quote-canvas'), 'canvas 渲染管线在位');
assert(wxml.includes('quote-canvas') && wxml.includes('exportImage') && wxml.includes('exportPdf'), 'wxml 导出入口绑定在位');
assert(wxml.includes('刷新报价单预览'), '刷新预览按钮保留');
assert(!wxml.includes('补充表格信息'), '本地表单编辑区（补充表格信息）不得复活');

// 4) 入口与 sheet 契约
assert(entry.includes('exportPlatformQuote') && entry.includes('quote-id='), 'workflow 结果页经 exportPlatformQuote 携 quote-id 进入');
assert(sheet.includes('module.exports'), 'sheet 渲染模块在位（money/filename/clean 由导出页复用）');
assert(js.includes('S.money') && js.includes('S.filename'), '导出页复用 sheet 的金额与文件名工具');

console.log('verify-quote-export ✔  全部断言通过（报价导出平台快照链：quote-id 必填/一致性校验/导出三件套/无本地回落）');
