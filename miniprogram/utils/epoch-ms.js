/**
 * 时间戳三输入归一（20 号任务书 F3/D-1 同口径，阶段 4 先例抽公共纯函数）：
 * 毫秒数字（主形态——后端 OffsetDateTime/LocalDateTime 序列化落 epoch 毫秒）/
 * 12 位以上纯数字串（防御）/ ISO 字符串；秒级数字不在支持面。
 * 返回有限毫秒数或 NaN（调用方自行判 Number.isFinite）。
 */
function epochMsOf(value) {
  if (typeof value === 'number') return value;
  if (typeof value === 'string' && value) return /^\d{12,}$/.test(value) ? Number(value) : Date.parse(value);
  return NaN;
}

module.exports = { epochMsOf };
