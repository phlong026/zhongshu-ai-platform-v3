package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.provider.FakeContentProviders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * e2e 钩子（仅 zsdev，对标 DevDataSeeder/SmsDevController 先例）：运行时切换 fake provider
 * 结局（ok|failed|uncertain）与直链探测结果，供 AC5/AC7 负路径断言。
 * 生产无 fake Bean 本端点亦不装配（@Profile 双保险）。
 */
@Tag(name = "开发 - 内容 fake 通道钩子")
@Profile("zsdev")
@RestController
@RequestMapping("/platform/dev/content-fake")
public class ContentFakeDevController {

    @PostMapping("/outcome")
    @Operation(summary = "切换 fake 结局：outcome=ok|failed|uncertain，probeExpired=true|false（null 清除）")
    public CommonResult<Map<String, Object>> outcome(@RequestBody Map<String, Object> body) {
        String outcome = body.get("outcome") == null ? null : String.valueOf(body.get("outcome"));
        Boolean probeExpired = body.get("probeExpired") == null ? null
                : Boolean.parseBoolean(String.valueOf(body.get("probeExpired")));
        FakeContentProviders.override(outcome, probeExpired);
        return success(Map.of("outcome", outcome == null ? "" : outcome,
                "probeExpired", probeExpired == null ? false : probeExpired));
    }

}
