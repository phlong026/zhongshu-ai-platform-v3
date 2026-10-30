package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "小程序 - 补充需求 AI 优化 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppNotePolishRespVO {

    @Schema(description = "润色后的补充需求")
    private String polished;
}
