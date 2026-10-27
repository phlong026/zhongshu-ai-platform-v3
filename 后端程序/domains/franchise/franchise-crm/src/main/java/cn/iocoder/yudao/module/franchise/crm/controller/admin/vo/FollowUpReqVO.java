package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "记录跟进 Request VO")
@Data
public class FollowUpReqVO {

    @Schema(description = "客户当前版本号（乐观锁）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long revision;

    @NotBlank(message = "请填写跟进内容")
    @Size(max = 1000)
    private String note;

    @Schema(description = "下次回访日期")
    private LocalDate nextVisitDate;

}
