package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 资产衍生图运维入口：历史资产（缩略图/预览图功能上线前入库）没有 thumb_key/preview_key，
 * 首次被读取时会按需补生成；发版后先调用本接口批量回填，可避免首批用户承担生成耗时。
 */
@Tag(name = "管理后台 - 资产衍生图")
@RestController
@RequestMapping("/design/v1/admin/assets")
public class AssetAdminController {

    @Resource
    private AssetService assetService;

    @PostMapping("/renditions/backfill")
    @Operation(summary = "回填历史资产的缩略图/预览图：单次最多 50 条，返回本次生成成功条数")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_WRITE + "')")
    public CommonResult<Map<String, Object>> backfillRenditions(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        int generated = assetService.backfillRenditions(limit);
        return success(Map.of("generated", generated, "limit", Math.max(1, Math.min(limit, 50))));
    }
}
