package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 首页聚合 Response VO（页面 01）")
@Data
public class AppHomeRespVO {

    @Schema(description = "精选案例")
    private List<AppCaseSummaryRespVO> featuredCases;

    @Schema(description = "未读消息数")
    private Long unreadCount;

    @Schema(description = "使用权状态：ACTIVE / REVOKED / NONE")
    private String accessGrantStatus;

    @Schema(description = "绑定信息是否完整（公司/联系人/电话齐全）；null=不适用（未登录或受限会话），"
            + "false 时前端展示补绑定提醒（需求 3d：仅提醒可关闭）")
    private Boolean bindingComplete;

}
