/**
 * 员工本人资料视图模型（纯函数，零 wx/Storage 依赖——verify 依赖此约束）。
 * 契约：monorepo docs/backend-parity/08-阶段0-员工profile接口-需求与验收.md（PUT /platform/profile/mine）。
 * 与后端同一口径：nickname 必填，trim 后 1–20 字；手机号只读；头像上传通道未就绪前不落 URL。
 */

/** 姓名校验 + trim 归一化：{ok:true,value} | {ok:false,error} */
function normalizeNickname(input) {
  const name = String(input == null ? '' : input).trim();
  if (!name || name.length > 20) return { ok: false, error: '姓名须为1–20个字' };
  return { ok: true, value: name };
}

/**
 * 后端 Profile（GET/PUT data：{id,nickname,mobile,avatar}）合入本地 profile。
 * 姓名/手机号以后端为真源；头像保留本地路径（wx.saveFile 路径不是 URL，不上传不覆盖——待基-1 上传通道）。
 */
function mergeProfile(local, remote) {
  const next = Object.assign({}, local);
  if (remote && typeof remote.nickname === 'string' && remote.nickname) next.name = remote.nickname;
  if (remote && typeof remote.mobile === 'string' && remote.mobile) next.phone = remote.mobile;
  return next;
}

/** 手机号打码：前3后4（138****2688）；空/短串（<7 位）原样返回（20 号任务书 AC4，syncApiProfile 回填展示用） */
function maskPhone(phone) {
  const s = String(phone == null ? '' : phone);
  return s.length >= 7 ? s.slice(0, 3) + '****' + s.slice(-4) : s;
}

module.exports = { normalizeNickname, mergeProfile, maskPhone };
