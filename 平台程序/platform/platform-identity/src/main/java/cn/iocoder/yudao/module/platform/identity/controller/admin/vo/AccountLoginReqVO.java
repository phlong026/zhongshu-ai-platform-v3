package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccountLoginReqVO {
    @NotBlank(message = "请选择加盟商账号")
    @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "加盟商账号标识不正确")
    private String accountId;
    @NotBlank(message = "请输入密码")
    @Size(max = 72, message = "密码过长")
    private String password;
    @Size(max = 64, message = "新密码过长")
    private String newPassword;
}
