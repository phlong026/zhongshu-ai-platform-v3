// 凭据 MIME 声明回归（2026-11-24 现场验收：开工准备上传报
// "MAGIC_NUMBER: 声明 image/png 实际 image/jpeg"）。
// 根因：客户端按文件路径后缀猜 MIME，微信保存路径后缀与真实字节格式不一致时，
// 票据声明与 complete 阶段的魔数校验对不上，资产被判 REJECTED。
// 约束：MIME 必须由字节魔数决定；后缀只在读不到字节时兜底。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const vm = require('vm');

function finance() {
  const context = { module: { exports: {} }, require: () => ({}) };
  vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/api/finance.js'), 'utf8'), context);
  return context.module.exports;
}

const JPEG = [0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01];
const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d];
const PDF = [0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a];
const binary = bytes => String.fromCharCode(...bytes);

test('按字节魔数识别凭据格式（二进制字符串 / ArrayBuffer / 字节视图）', () => {
  const { sniffMime } = finance();
  assert.equal(sniffMime(binary(JPEG)), 'image/jpeg');
  assert.equal(sniffMime(binary(PNG)), 'image/png');
  assert.equal(sniffMime(binary(PDF)), 'application/pdf');
  assert.equal(sniffMime(Buffer.from(JPEG)), 'image/jpeg');
  assert.equal(sniffMime(new Uint8Array(PNG).buffer), 'image/png');
  assert.equal(sniffMime(new DataView(new Uint8Array(PDF).buffer)), 'application/pdf');
  // 空值/不足长度的脏数据不得误判
  assert.equal(sniffMime(null), '');
  assert.equal(sniffMime(''), '');
  assert.equal(sniffMime(binary([0x00, 0x01, 0x02, 0x03])), '');
});

test('真实 JPEG 字节 + .png 后缀必须声明 jpeg（本次现场事故的回归用例）', () => {
  const { guessMime } = finance();
  // 微信 saveFile 返回的路径后缀是 .png，字节却是重新编码后的 JPEG
  assert.equal(guessMime('wxfile://usr/1620000000000.png', binary(JPEG)), 'image/jpeg');
  assert.equal(guessMime('现场照片-1.png', Buffer.from(JPEG)), 'image/jpeg');
  // 反方向同样成立：PNG 字节 + .jpg 后缀
  assert.equal(guessMime('现场照片-1.jpg', binary(PNG)), 'image/png');
  // PDF 内容不受无关后缀影响
  assert.equal(guessMime('合同.png', binary(PDF)), 'application/pdf');
});

test('读不到字节时回落后缀猜测，且凭据链路只声明三种格式', () => {
  const { guessMime } = finance();
  assert.equal(guessMime('a.png'), 'image/png');
  assert.equal(guessMime('a.PDF'), 'application/pdf');
  assert.equal(guessMime('a.jpg'), 'image/jpeg');
  assert.equal(guessMime('wxfile://usr/no-extension'), 'image/jpeg');
  // 垃圾字节不能让后缀猜测失效（后端仍会以自身魔数结论拒收，但客户端不该瞎猜第三种格式）
  assert.equal(guessMime('a.png', binary([0x00, 0x01, 0x02])), 'image/png');
  assert.ok(['image/jpeg', 'image/png', 'application/pdf'].includes(guessMime('x.heic', binary(JPEG))));
});

test('识别不出的格式在本地前置拒绝，不再浪费一次票据与上传', () => {
  const { voucherMime } = finance();
  const GIF = binary([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00]);
  const WEBP = binary([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50]);
  // 后缀是 .png、内容是 GIF：必须本地拒绝，而不是声明 jpeg 再被后端断言
  for (const [name, bytes] of [['现场照片-1.png', GIF], ['x.webp', WEBP], ['wxfile://usr/abc', GIF]]) {
    const err = (() => { try { voucherMime(name, bytes); return null; } catch (e) { return e; } })();
    assert.ok(err, name + ' 的无法识别字节必须被拒绝');
    assert.match(err.msg, /JPG、PNG 图片或 PDF/);
    assert.match(err.message, /HEIC、WebP、GIF/);
    assert.equal(err.code, 0);
  }
  // 现场照片等纯图类型（allowPdf:false）连 PDF 后缀都不放行
  assert.throws(() => voucherMime('合同.pdf', GIF, { allowPdf: false }), /JPG 或 PNG 图片/);
  // PDF 后缀 + 头部带填充的字节仍按 PDF 放行（后端做最终内容判定）
  assert.equal(voucherMime('合同.pdf', GIF), 'application/pdf');
  // 字节可识别时不受后缀影响
  assert.equal(voucherMime('现场照片-1.png', binary(JPEG)), 'image/jpeg');
  assert.equal(voucherMime('无后缀', binary(PNG)), 'image/png');
  assert.equal(voucherMime('随便.png', binary(PDF)), 'application/pdf');
});

test('后端拒收原因转成一线能看懂的话', () => {
  const { voucherRejectText } = finance();
  const text = voucherRejectText('MAGIC_NUMBER: 声明 image/png 实际 image/jpeg');
  assert.match(text, /JPG 图片/);
  assert.match(text, /前缀|后缀|不一致/);
  // 实际格式识别不出来（sniffMime 返回 null 时后端拼的是 "实际 null"）
  assert.match(voucherRejectText('MAGIC_NUMBER: 声明 image/png 实际 null'), /无法识别/);
  assert.match(voucherRejectText('PIXEL_GUARD: 12000x9000 超限'), /尺寸超出上限/);
  assert.match(voucherRejectText('DECODE: 图片解码失败（疑似损坏或炸弹）'), /损坏/);
  assert.match(voucherRejectText('MALWARE: EICAR 测试样本'), /安全检测/);
  assert.match(voucherRejectText(''), /未通过校验/);
  // 未知原因原样透出，避免吞掉后端新增的诊断码
  assert.equal(voucherRejectText('SHA_MISMATCH: 摘要不一致'), 'SHA_MISMATCH: 摘要不一致');
});
