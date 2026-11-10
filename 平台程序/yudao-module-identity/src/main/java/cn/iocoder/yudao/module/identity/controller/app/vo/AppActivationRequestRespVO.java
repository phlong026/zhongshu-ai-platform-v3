package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "小程序 - 开通申请状态 Response VO")
@Data
public class AppActivationRequestRespVO {

    @Schema(description = "申请状态：PENDING 审核中 / APPROVED 已开通 / REJECTED 已驳回；从未申请时接口返回空")
    private String status;

    @Schema(description = "申请的公司名称")
    private String companyName;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "提交时间")
    private LocalDateTime requestedAt;

    @Schema(description = "处理时间（未处理为 null）")
    private LocalDateTime handledAt;

    @Schema(description = "驳回原因（仅 REJECTED）")
    private String rejectReason;

}
