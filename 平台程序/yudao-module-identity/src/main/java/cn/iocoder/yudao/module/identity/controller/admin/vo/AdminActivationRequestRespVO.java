package cn.iocoder.yudao.module.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 开通申请 Response VO")
@Data
public class AdminActivationRequestRespVO {

    @Schema(description = "申请编号")
    private String id;

    @Schema(description = "账号编号")
    private String accountId;

    @Schema(description = "申请公司名称")
    private String companyName;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "状态：PENDING / APPROVED / REJECTED")
    private String status;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "通过备注")
    private String note;

    @Schema(description = "提交时间")
    private LocalDateTime requestedAt;

    @Schema(description = "处理时间")
    private LocalDateTime handledAt;

    @Schema(description = "处理人")
    private String handledBy;

}
