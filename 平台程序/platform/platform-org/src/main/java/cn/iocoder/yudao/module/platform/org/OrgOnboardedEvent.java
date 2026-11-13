package cn.iocoder.yudao.module.platform.org;

/**
 * 组织开通完成事件（24 号任务书 B-8）。
 *
 * <p>平台侧（platform-authz）开通组织后发布；积分池归属方（commerce）监听并建池/赠点。
 * 事件放在 platform-org（两侧共同依赖的合同模块），避免 platform 反向依赖业务模块。
 *
 * @param designEnabled 是否同时开通设计能力（false/省略 = 不改变既有开通状态）
 * @param initialCredits 开通赠点（仅首次生效，额度幂等）
 */
public record OrgOnboardedEvent(long orgId, boolean designEnabled, long initialCredits) {
}
