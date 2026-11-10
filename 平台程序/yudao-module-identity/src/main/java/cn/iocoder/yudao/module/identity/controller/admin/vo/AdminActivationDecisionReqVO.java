package cn.iocoder.yudao.module.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 开通申请审批 Request VO")
@Data
public class AdminActivationDecisionReqVO {

    @Schema(description = "是否通过：true 开通使用权 / false 驳回", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "审批结论不能为空")
    private Boolean approve;

    @Schema(description = "驳回原因（驳回时必填）；通过时作为备注，可空")
    @Size(max = 200, message = "说明不能超过200字")
    private String reason;

}
