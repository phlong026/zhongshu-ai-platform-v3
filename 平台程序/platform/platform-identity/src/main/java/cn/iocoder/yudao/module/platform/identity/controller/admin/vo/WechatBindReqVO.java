package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WechatBindReqVO {
    @NotBlank(message = "小程序 AppID 不能为空")
    private String appid;
    @NotBlank(message = "微信登录凭证不能为空")
    private String loginCode;
}
