package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "员工微信绑定审计分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class WechatBindingEventPageReqVO extends PageParam {

    @Schema(description = "只看某名员工")
    private Long userId;

}
