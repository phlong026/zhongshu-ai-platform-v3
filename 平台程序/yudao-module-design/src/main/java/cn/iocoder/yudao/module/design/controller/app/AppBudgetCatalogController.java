package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.budget.BudgetPriceProfileService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetCatalogRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/design/v1/budget")
@PermitAll
public class AppBudgetCatalogController {
    @Resource
    private BudgetCatalogService budgetCatalogService;
    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping("/regions")
    public CommonResult<List<AppBudgetCatalogRespVO.Region>> regions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUnrestricted(authorization);
        return success(budgetCatalogService.publicRegions());
    }

    @GetMapping("/my-regions")
    @Operation(summary = "本人地区报价配置列表（仅创建人可见）")
    public CommonResult<List<AppBudgetCatalogRespVO.MyRegion>> myRegions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.myRegions(accountId));
    }

    @PostMapping("/my-regions")
    @Operation(summary = "创建本人地区报价配置：名称 + 系数，服务端带入武汉基准价")
    public CommonResult<AppBudgetCatalogRespVO.MyRegion> createMyRegion(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.createMyRegion(accountId, body));
    }

    @GetMapping("/my-regions/{code}")
    @Operation(summary = "读取本人地区报价配置头（仅创建人可见）")
    public CommonResult<AppBudgetCatalogRespVO.MyRegion> myRegion(
            @PathVariable("code") String code,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.getMyRegion(accountId, code));
    }

    @PatchMapping("/my-regions/{code}")
    @Operation(summary = "改名/改系数/归档本人地区报价配置")
    public CommonResult<AppBudgetCatalogRespVO.MyRegion> updateMyRegion(
            @PathVariable("code") String code,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.updateMyRegion(accountId, code, body));
    }

    @PostMapping("/my-regions/{code}/prices:copy")
    @Operation(summary = "按系数重算本人地区报价配置（ALL 覆盖手工价；COEFFICIENT_ONLY 保留手工价）")
    public CommonResult<BudgetPriceProfileService.CopyResult> copyMyRegionPrices(
            @PathVariable("code") String code,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.copyMyRegionPrices(accountId, code, body));
    }

    @PutMapping("/my-regions/{code}/prices")
    @Operation(summary = "批量保存本人地区报价配置；服务端按系数重算，保存即新版本")
    public CommonResult<AppBudgetCatalogRespVO.MyRegion> saveMyRegionPrices(
            @PathVariable("code") String code,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.saveMyRegionPrices(accountId, code, body));
    }

    @GetMapping("/options")
    public CommonResult<AppBudgetCatalogRespVO> options(@RequestParam String regionCode,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // T14: 可用性按当前账号判定——本人覆盖价同样算本地价
        long accountId = identitySessionPort.requireUnrestricted(bearerToken(authorization)).accountId();
        return success(budgetCatalogService.publicOptions(regionCode, accountId));
    }

    private void requireUnrestricted(String authorization) {
        identitySessionPort.requireUnrestricted(bearerToken(authorization));
    }

    private String bearerToken(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
    }
}
