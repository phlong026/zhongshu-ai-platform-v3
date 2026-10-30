package cn.iocoder.yudao.module.platform.identity.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 员工微信绑定审计；同一事件按员工任职过的组织各记一行 */
@TableName("pf_wechat_binding_event")
@TenantIgnore
@Data
@Accessors(chain = true)
public class WechatBindingEventDO {

    public static final String TYPE_BOUND = "BOUND";
    public static final String TYPE_REBOUND = "REBOUND";
    public static final String TYPE_REVOKED = "REVOKED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orgId;
    private Long userId;
    private String appid;
    private Long bindingId;
    private Long previousBindingId;
    private String eventType;
    /** REVOKED 时的原因：MOBILE_CHANGED / USER_DISABLED / REBOUND */
    private String reason;
    /** 人员管理触发时的操作人；登录时自动检测到的失效为空 */
    private Long operatorUserId;
    private OffsetDateTime occurredAt;

}
