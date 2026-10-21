package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "员工微信绑定审计 Response VO")
@Data
public class WechatBindingEventRespVO {

    private Long id;
    private Long userId;
    private String appid;
    @Schema(description = "BOUND 首次绑定 / REBOUND 换微信绑定 / REVOKED 失效")
    private String eventType;
    @Schema(description = "失效原因：MOBILE_CHANGED 手机号变更 / USER_DISABLED 账号停用 / REBOUND 被新微信顶替")
    private String reason;
    @Schema(description = "人员管理中的操作人；登录时自动检测到的失效为空")
    private Long operatorUserId;
    private OffsetDateTime occurredAt;

}
