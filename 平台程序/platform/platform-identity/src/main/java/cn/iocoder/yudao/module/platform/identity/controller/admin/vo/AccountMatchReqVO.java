package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccountMatchReqVO {
    @NotBlank(message = "请输入账号或简写")
    @Size(min = 2, max = 100, message = "账号或简写需为2至100个字符")
    @Pattern(regexp = "\\s*[\\p{L}\\p{N}-]+\\s*", message = "账号或简写格式不正确")
    private String query;
}
