package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingEventDO;

/**
 * 员工微信绑定的变更与审计。每次变更都写入 pf_wechat_binding_event（按员工任职过的组织各一行），
 * 员工所在组织的老板经组织范围过滤只看到本组织的记录。
 */
public interface StaffWechatBindingService {

    /** 绑定；该员工在此小程序已有其他微信绑定时先作废旧绑定（记 REBOUND） */
    WechatBindingDO bind(String appid, String openid, long userId, String mobile);

    /**
     * 作废一条绑定并写审计。reason 为 MOBILE_CHANGED 或 USER_DISABLED 时同时吊销该员工全部令牌，
     * 避免旧微信已签发的令牌在刷新期内继续续用。
     *
     * @param operatorUserId 人员管理中的操作人；登录时自动检测到的失效传 null
     */
    void revoke(WechatBindingDO binding, String reason, Long operatorUserId);

    /**
     * 人员管理改手机号、停用账号时调用（M5 人员管理必须调用）：作废该员工在所有小程序的绑定、写审计并吊销其全部令牌。
     *
     * @param reason MOBILE_CHANGED 或 USER_DISABLED
     */
    void revokeAllForUser(long userId, String reason, Long operatorUserId);

    /** 分页查询绑定审计；自动限定在当前工作身份所在组织 */
    PageResult<WechatBindingEventDO> pageEvents(PageParam page, Long userId);

}
