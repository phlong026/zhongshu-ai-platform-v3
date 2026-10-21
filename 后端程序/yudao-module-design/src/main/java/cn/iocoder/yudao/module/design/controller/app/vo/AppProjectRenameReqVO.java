package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "小程序 - 方案重命名 Request VO（2026-10-05 整改）")
@Data
public class AppProjectRenameReqVO {

    @Schema(description = "方案名称（去首尾空白后 1~60 字符）")
    @NotBlank(message = "请填写方案名称")
    @Size(max = 60, message = "方案名称不能超过 60 字")
    private String name;

}
