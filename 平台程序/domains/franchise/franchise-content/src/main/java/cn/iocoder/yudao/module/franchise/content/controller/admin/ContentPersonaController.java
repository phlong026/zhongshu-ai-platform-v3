package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentPersonaService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPersonaDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 人设组织库（D-1.5-10）：读=query（sales 可选用）；写=persona（sales-lead/boss/owner）。 */
@Tag(name = "加盟 - 内容创作（人设）")
@RestController
@RequestMapping("/franchise/content/personas")
@Validated
public class ContentPersonaController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private ContentPersonaService personas;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping
    @Operation(summary = "人设列表（组织共享库）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list() {
        PostSnapshot post = post();
        return success(personas.list(post.orgId()).stream().map(ContentPersonaController::view).toList());
    }

    @PostMapping
    @Operation(summary = "新建人设")
    @PreAuthorize("@post.has('" + ContentPermissions.PERSONA + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        return success(view(personas.create(post.orgId(), post.userId(), body)));
    }

    @PutMapping("{id}")
    @Operation(summary = "更新人设（不影响已生成产物——产物持快照）")
    @PreAuthorize("@post.has('" + ContentPermissions.PERSONA + "')")
    public CommonResult<Map<String, Object>> update(@PathVariable("id") Long id,
                                                    @RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        return success(view(personas.update(post.orgId(), id, body)));
    }

    @DeleteMapping("{id}")
    @Operation(summary = "删除人设（软删）")
    @PreAuthorize("@post.has('" + ContentPermissions.PERSONA + "')")
    public CommonResult<Map<String, Object>> delete(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        personas.delete(post.orgId(), id);
        return success(Map.of("ok", true));
    }

    static Map<String, Object> view(FrContentPersonaDO row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("name", row.getName());
        view.put("who", row.getWho() == null ? "" : row.getWho());
        view.put("sell", row.getSell() == null ? "" : row.getSell());
        view.put("audience", row.getAudience() == null ? "" : row.getAudience());
        view.put("cta", row.getCta() == null ? "" : row.getCta());
        view.put("ipType", row.getIpType() == null ? "" : row.getIpType());
        view.put("track", row.getTrack() == null ? "" : row.getTrack());
        view.put("differentiator", row.getDifferentiator() == null ? "" : row.getDifferentiator());
        view.put("expressionStyle", row.getExpressionStyle() == null ? "" : row.getExpressionStyle());
        view.put("audienceNeeds", row.getAudienceNeeds() == null ? "" : row.getAudienceNeeds());
        view.put("factualBackground", row.getFactualBackground() == null ? "" : row.getFactualBackground());
        view.put("sampleScript", row.getSampleScript() == null ? "" : row.getSampleScript());
        view.put("forbiddenClaims", row.getForbiddenClaims() == null ? "" : row.getForbiddenClaims());
        view.put("enabled", row.getEnabled() == null || row.getEnabled());
        return view;
    }

}
