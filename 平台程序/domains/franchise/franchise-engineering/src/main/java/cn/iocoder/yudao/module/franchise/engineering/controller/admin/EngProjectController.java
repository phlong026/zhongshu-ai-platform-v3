package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService;
import cn.iocoder.yudao.module.franchise.engineering.content.EngSiteRecordService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 工程档案（建档/列表/详情/换负责人）+ 现场记录（记录/更正）。 */
@Tag(name = "加盟 - 工程全周期（档案）")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngProjectController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngProjectService projects;
    @Resource
    private EngSiteRecordService siteRecords;

    private PostSnapshot post() {
        return currentPost.require();
    }

    static String requireIdem(String idem) {
        if (idem == null || idem.isBlank()) {
            throw exception0(400, "缺少 Idempotency-Key 请求头");
        }
        return idem.trim();
    }

    @GetMapping("/projects/contract-filing")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Boolean> supportsContractFiling() {
        return success(true);
    }

    @PostMapping({"/projects", "/projects/contract-filing"})
    @Operation(summary = "按正式合同新建或核对原工程档案")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        var cmd = new EngProjectService.CreateCommand(
                str(body.get("name")), str(body.get("customer")), str(body.get("city")), str(body.get("phone")),
                decimal(body.get("area")), integer(body.get("floors")), longOrNull(body.get("parentProjectId")),
                Boolean.TRUE.equals(body.get("independent")));
        return success(projects.create(post, cmd, requireIdem(idem)));
    }

    @GetMapping("/projects")
    @Operation(summary = "工程列表（?status=；工程岗仅本人，财务线全量）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(defaultValue="50") int pageSize,
            @RequestParam(required=false) String keyword) {
        return success(projects.list(post(), status, page, pageSize, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "工程详情聚合（档案+计划+文档+尾段，按身份脱敏 D-13）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> detail(@PathVariable long id) {
        return success(projects.detail(post(), id));
    }

    @GetMapping("/{id}/manager-candidates")
    @Operation(summary = "原负责人查看可接手工程的在岗同事")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<List<Map<String, Object>>> managerCandidates(@PathVariable long id) {
        return success(projects.managerCandidates(post(), id));
    }

    @PostMapping("/{id}/manager")
    @Operation(summary = "换负责人（在岗校验 010+CAS+审计；旧负责人立即失权）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> changeManager(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        Object engineer = body.get("engineerUserId");
        return success(projects.changeManager(post(), id,
                new EngProjectService.ManagerCommand(
                        engineer instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(engineer)),
                        str(body.get("note"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/site-records")
    @Operation(summary = "普通现场记录（note+多图资产；前置=已开工）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> addSiteRecord(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(Map.of("records", siteRecords.create(post(), id,
                new EngSiteRecordService.SiteRecordCommand(
                        longOrNull(body.get("nodeId")), localDate(body.get("occurredAt")),
                        str(body.get("note")), longList(body.get("fileAssetIds"))), requireIdem(idem))));
    }

    @GetMapping("/{id}/site-records")
    @Operation(summary = "现场记录列表（含 completion 留痕与更正历史）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> siteRecords(@PathVariable long id) {
        return success(Map.of("records", siteRecords.list(post(), id)));
    }

    @PostMapping("/site-records/{recordId}/correct")
    @Operation(summary = "现场记录更正（原值入 corrections；completion 记录走节点更正）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> correctSiteRecord(@PathVariable long recordId,
                                                               @RequestBody Map<String, Object> body,
                                                               @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(Map.of("records", siteRecords.correct(post(), recordId,
                new EngSiteRecordService.CorrectCommand(str(body.get("note")),
                        longList(body.get("fileAssetIds")), str(body.get("reason"))), requireIdem(idem))));
    }

    // ===================== 请求解析小工具（content 控制器同款宽松口径） =====================

    static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    static BigDecimal decimal(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? null : new BigDecimal(String.valueOf(raw));
    }

    static Integer integer(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? null
                : Double.valueOf(String.valueOf(raw)).intValue();
    }

    static Long longOrNull(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        // 大雪花 id 字符串禁走 Double（2^53 外丢精度）——BigDecimal 精确转换
        return new java.math.BigDecimal(String.valueOf(raw)).longValueExact();
    }

    static java.time.LocalDate localDate(Object raw) {
        return raw == null || String.valueOf(raw).isBlank() ? null : java.time.LocalDate.parse(String.valueOf(raw));
    }

    static List<Long> longList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> x instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(x))).toList();
    }
}
