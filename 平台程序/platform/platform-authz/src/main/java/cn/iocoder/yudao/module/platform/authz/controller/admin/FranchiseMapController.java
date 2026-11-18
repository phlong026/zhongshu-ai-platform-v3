package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseMapService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
public class FranchiseMapController {
    private final FranchiseMapService maps;
    public FranchiseMapController(FranchiseMapService maps) { this.maps=maps; }

    @GetMapping("/franchise/store-map")
    public CommonResult<Map<String,Object>> map(HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return CommonResult.success(maps.snapshot());
    }
}
