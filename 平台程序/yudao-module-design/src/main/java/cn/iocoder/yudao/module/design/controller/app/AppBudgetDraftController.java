package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.budget.*;
import cn.iocoder.yudao.module.design.controller.app.vo.AppItemizedBudgetRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;

@RestController
@RequestMapping("/design/v1/budget-drafts")
@PermitAll
public class AppBudgetDraftController {
    @Resource private BudgetDraftService drafts;
    @Resource private ItemizedBudgetService budgets;
    @Resource private IdentitySessionPort identities;
    @PostMapping
    public CommonResult<BudgetDraftService.Draft> create(@RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="Authorization",required=false) String auth) { return success(drafts.create(user(auth),key)); }
    @GetMapping
    public CommonResult<List<BudgetDraftService.Draft>> list(@RequestParam(value="cursor",required=false) String cursor,
            @RequestParam(value="limit",defaultValue="20") int limit,@RequestHeader(value="Authorization",required=false) String auth) {
        return success(drafts.list(user(auth),cursor==null?null:BudgetInputs.positiveId(cursor),limit));
    }
    @GetMapping("/{id}")
    public CommonResult<BudgetDraftService.Draft> get(@PathVariable("id") String id,@RequestHeader(value="Authorization",required=false) String auth) {
        return success(drafts.get(user(auth),BudgetInputs.positiveId(id)));
    }
    @PutMapping("/{id}")
    public CommonResult<BudgetDraftService.Draft> save(@PathVariable("id") String id,@RequestBody Map<String,Object> body,
            @RequestHeader(value="Authorization",required=false) String auth) {
        int version=expected(body,Set.of("expectedVersion","state"));
        if(!(body.get("state") instanceof Map<?,?>)) throw exception(BUDGET_INPUT_INVALID);
        return success(drafts.save(user(auth),BudgetInputs.positiveId(id),version,BudgetInputs.readJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(body.get("state")))));
    }
    @PutMapping("/{id}/name")
    public CommonResult<BudgetDraftService.Draft> rename(@PathVariable("id") String id,@RequestBody Map<String,Object> body,
            @RequestHeader(value="Authorization",required=false) String auth) {
        if(body==null || !Set.of("name").equals(body.keySet()) || !(body.get("name") instanceof String)) throw exception(BUDGET_INPUT_INVALID);
        return success(drafts.rename(user(auth),BudgetInputs.positiveId(id),(String)body.get("name")));
    }
    @PostMapping("/{id}/import")
    public CommonResult<BudgetDraftService.Draft> importProject(@PathVariable("id") String id,@RequestBody Map<String,Object> body,
            @RequestHeader(value="Authorization",required=false) String auth) {
        int version=expected(body,Set.of("expectedVersion","projectId","resultVersionId"));
        return success(drafts.importProject(user(auth),BudgetInputs.positiveId(id),version,BudgetInputs.positiveId(String.valueOf(body.get("projectId"))),
                body.get("resultVersionId")==null?null:BudgetInputs.positiveId(String.valueOf(body.get("resultVersionId")))));
    }
    @PostMapping("/{id}/estimates")
    public CommonResult<AppItemizedBudgetRespVO> createEstimate(@PathVariable("id") String id,@RequestBody Map<String,Object> body,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader(value="Authorization",required=false) String auth) {
        return success(budgets.createIndependent(user(auth),BudgetInputs.positiveId(id),body,key));
    }
    private int expected(Map<String,Object> body,Set<String> allowed) {
        if(body==null || !allowed.containsAll(body.keySet()) || !(body.get("expectedVersion") instanceof Number n)
                || n.longValue()<1 || n.longValue()>Integer.MAX_VALUE || n.doubleValue()!=n.longValue()) throw exception(BUDGET_INPUT_INVALID);
        return n.intValue();
    }
    private long user(String auth) { return identities.requireUnrestricted(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):auth).accountId(); }
}
