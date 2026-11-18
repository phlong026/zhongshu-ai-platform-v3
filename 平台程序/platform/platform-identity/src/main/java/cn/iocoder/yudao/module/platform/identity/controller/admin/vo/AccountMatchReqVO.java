package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AccountMatchReqVO {
    @NotBlank(message = "请输入账号、姓名或门店名称")
    @Size(max = 100, message = "查询内容最多100个字符")
    private String query;
}
