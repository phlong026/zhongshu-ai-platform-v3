package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "客户分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomerPageReqVO extends PageParam {

    @Schema(description = "称呼或手机号")
    private String keyword;

}
