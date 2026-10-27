package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "登记客资 Response VO")
@Data
public class LeadCaptureRespVO {

    private Long leadId;
    private Long customerId;
    @Schema(description = "true：同门店已有该手机号客户，本次客资已关联原客户，负责人不变")
    private Boolean linkedExisting;
    private Long customerOwnerUserId;

}
