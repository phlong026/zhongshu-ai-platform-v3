package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import cn.iocoder.yudao.module.platform.identity.LoginPolicy;

@Data
public class AccountLoginReqVO {
    @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "登录账号标识不正确")
    private String accountId;
    @Size(max = 100, message = "登录账号过长")
    private String loginName;
    @NotBlank(message = "请输入密码")
    @Size(max = 72, message = "密码过长")
    private String password;
    @Size(max = 64, message = "新密码过长")
    private String newPassword;
    private Integer loginVersion;
    private LoginPolicy.Consent consent;

    @AssertTrue(message = "请输入完整登录账号")
    public boolean isSingleAccountProvided() {
        return (accountId != null && !accountId.isBlank()) ^ (loginName != null && !loginName.isBlank());
    }
}
