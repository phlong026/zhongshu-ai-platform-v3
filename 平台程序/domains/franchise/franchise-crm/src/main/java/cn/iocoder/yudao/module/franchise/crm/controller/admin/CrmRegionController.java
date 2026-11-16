package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.crm.CrmRegions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="加盟协同 - 客户地址")
@RestController
@RequestMapping("/franchise/crm/regions")
public class CrmRegionController {
    @GetMapping
    @Operation(summary="按上级编码读取省市区县乡镇村社区；省级不传编码")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<List<CrmRegions.Option>> children(@RequestParam(required=false) String parentCode) {
        return success(CrmRegions.children(parentCode));
    }
}
