package cn.iocoder.yudao.module.platform.org;

import java.util.Set;

/**
 * 一条有效任职在当前时刻的完整视图，供授权判断使用（对应小程序 authority-v2.js 的 context()）。
 *
 * @param managedTeamIds 负责人管理的团队：所在团队及其全部下级团队；非负责人或未指定团队时为空，
 *                       由业务域决定「负责人未分团队」时的含义（如视为管理本组织本条线）
 */
public record PostSnapshot(long postId, long userId, long orgId, String orgType, String roleCode,
                           Long teamId, boolean lead, Set<Long> managedTeamIds) {
}
