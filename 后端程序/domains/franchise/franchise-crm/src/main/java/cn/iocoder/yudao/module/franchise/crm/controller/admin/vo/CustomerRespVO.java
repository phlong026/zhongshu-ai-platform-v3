package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "客户 Response VO")
@Data
public class CustomerRespVO {

    private Long id;
    private Long teamId;
    private Long ownerUserId;
    private String name;
    private String phone;
    private String need;
    private String source;
    @Schema(description = "版本号，办理时回传")
    private Long revision;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

}
