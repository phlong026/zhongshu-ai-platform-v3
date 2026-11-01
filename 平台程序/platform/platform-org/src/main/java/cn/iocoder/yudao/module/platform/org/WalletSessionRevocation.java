package cn.iocoder.yudao.module.platform.org;

import java.util.List;

/**
 * 钱包会话撤权回调（09 钱包会话桥接）：岗位/成员/组织失效时，同步吊销对应组织服务账号的钱包会话。
 *
 * <p>接口定义在 platform-org、实现在 franchise-org（组织服务账号的属主域，经 infra
 * {@code IdentitySessionIssuePort} 吊销）——方向与 franchise-api→platform-org 既存依赖一致，
 * 避免反向依赖成环。实现不存在时（组装裁剪）挂钧点静默跳过。
 *
 * <p>实现合同：<b>无副作用</b>——按 external_key 查既存账号后吊销，缺失幂等返回；
 * 禁止建号/补挂/复活（与 {@code StoreServiceAccountPort.requireServiceAccount} 的准入语义区分）。
 * 须在调用方事务内执行（状态修改与吊销同事务提交/回滚）。
 */
public interface WalletSessionRevocation {

    /** 吊销该用户在相关组织的全部钱包会话（成员停用/岗位撤销） */
    void revokeForUserOrgs(long userId, List<Long> orgIds);

    /** 吊销该组织全部钱包会话（组织停用） */
    void revokeForOrg(long orgId);

}
