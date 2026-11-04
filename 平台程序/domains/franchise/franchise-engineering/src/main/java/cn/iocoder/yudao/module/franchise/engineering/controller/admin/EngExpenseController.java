package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngExpenseService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * M07-T07/M09-T04：直接项目费用端点——登记（确认即计成本）/采购凭据+应付挂接/金额修订/取消/清单。
 * 劳务费用（purpose=LABOR）走同一链：劳务原单→费用确认→应付→多笔付款（finance 侧）→退回关联。
 */
@Tag(name = "平台 - 工程直接项目费用")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngExpenseController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngExpenseService expenses;

    private PostSnapshot post() {
        return currentPost.require();
    }

    public record ExpenseRegisterReq(@NotBlank(message = "费用名称必填") @Size(max = 100) String name,
                                     @NotBlank(message = "费用用途必填")
                                     @Pattern(regexp = "LABOR|TRAVEL|OTHER", message = "费用用途须为 劳务/差旅/其它") String purpose,
                                     BigDecimal amount, @Size(max = 500) String note) {
    }

    public record ExpenseTermsReq(@NotBlank(message = "收款方必填") @Size(max = 64) String supplier,
                                  @NotBlank(message = "费用凭据必填") @Size(max = 128) String basis) {
    }

    public record ExpenseReviseReq(BigDecimal newAmount, @Size(max = 500) String note) {
    }

    public record ExpenseCancelReq(@NotBlank(message = "取消原因必填") @Size(max = 500) String reason) {
    }

    @PostMapping("/{id}/expenses")
    @Operation(summary = "登记直接项目费用（确认即计成本；劳务费用 purpose=LABOR——M07-T07）")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "body: {name, purpose(LABOR/TRAVEL/OTHER), amount(元), note}")
    public CommonResult<Map<String, Object>> register(@PathVariable long id, @Valid @RequestBody ExpenseRegisterReq req,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(expenses.register(post(), id,
                new EngExpenseService.RegisterCommand(req.name(), req.purpose(), req.amount(), req.note()),
                EngProjectController.requireIdem(idem)));
    }

    @PostMapping("/expenses/{expenseId}/terms")
    @Operation(summary = "采购凭据+应付挂接（付款不重计成本；应付清偿走 finance 资金侧）")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> terms(@PathVariable long expenseId, @Valid @RequestBody ExpenseTermsReq req,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(expenses.terms(post(), expenseId,
                new EngExpenseService.TermsCommand(req.supplier(), req.basis()), EngProjectController.requireIdem(idem)));
    }

    @PostMapping("/expenses/{expenseId}/revise")
    @Operation(summary = "确认费用变更（金额修订——独立于现金退回的成本调整路径；退多付款不减正确费用）")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> revise(@PathVariable long expenseId, @Valid @RequestBody ExpenseReviseReq req,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(expenses.revise(post(), expenseId, req.newAmount(), req.note(), EngProjectController.requireIdem(idem)));
    }

    @PostMapping("/expenses/{expenseId}/cancel")
    @Operation(summary = "取消费用（已挂应付拒绝——应付须财务侧原子关闭）")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Boolean> cancel(@PathVariable long expenseId, @Valid @RequestBody ExpenseCancelReq req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        expenses.cancel(post(), expenseId, req.reason(), EngProjectController.requireIdem(idem));
        return success(true);
    }


    @GetMapping("/{id}/expenses")
    @Operation(summary = "项目费用清单（M07-T07 劳务联动数据源；金额按 D-13 脱敏——服务层 full 判定）")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@PathVariable long id) {
        return success(expenses.listByProject(post(), id));
    }
}
