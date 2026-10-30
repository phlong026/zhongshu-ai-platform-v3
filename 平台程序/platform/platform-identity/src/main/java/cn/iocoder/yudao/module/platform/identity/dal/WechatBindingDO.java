package cn.iocoder.yudao.module.platform.identity.dal;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/** 员工微信绑定；只存手机号摘要，不存明文 */
@TableName("pf_wechat_binding")
@TenantIgnore
@Data
@Accessors(chain = true)
public class WechatBindingDO {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String REASON_MOBILE_CHANGED = "MOBILE_CHANGED";
    public static final String REASON_USER_DISABLED = "USER_DISABLED";
    public static final String REASON_REBOUND = "REBOUND";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String appid;
    private String openid;
    private Long userId;
    /** 绑定时手机号的 SHA-256 */
    private String mobileHash;
    private String status;
    private String revokeReason;
    private OffsetDateTime boundAt;
    private OffsetDateTime revokedAt;

}
