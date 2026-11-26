package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.Detail;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Customer;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.CustomerDetail;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.TransferCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.UpdateCommand;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper.FollowFilter;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import jakarta.annotation.Resource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController.IDEMPOTENCY_HEADER;

/**
 * 客户：查看、跟进与预约回访、编辑、参考方案与预算版本、责任转交。
 * 写接口带 revision（编辑、转交）防止覆盖他人改动；可带 Idempotency-Key 防止重复提交。
 */
@Tag(name = "加盟协同 - 客户")
@RestController
@RequestMapping("/franchise/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CurrentPost currentPost;
    private final SystemDirectory systemDirectory;

    public CustomerController(CustomerService customerService, CurrentPost currentPost,
                              SystemDirectory systemDirectory) {
        this.customerService = customerService;
        this.currentPost = currentPost;
        this.systemDirectory = systemDirectory;
    }

    /** 客资表格行：原 Customer 字段 + 负责人姓名（本组织在职名单映射）。 */
    public record CustomerRow(long id, String name, String phone, String wechat, String source,
                              String province, String city, String summary, String status,
                              long ownerUserId, String ownerName, java.time.LocalDate nextVisitDate,
                              java.time.OffsetDateTime lastFollowAt, long revision,
                              java.time.OffsetDateTime createTime, String district, String town, String village, String regionCode, String addressDetail, String designStatus,
                              cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Summary workflow) {
    }

    public record UpdateReq(String name, String wechat, String province, String city, String summary, long revision,
                            String district, String town, String village, String regionCode, String addressDetail) {
        public UpdateReq(String name,String wechat,String province,String city,String summary,long revision) {
            this(name,wechat,province,city,summary,revision,null,null,null,null,null);
        }
    }

    public record TransferReq(long targetUserId, String reason, long revision) {
    }

    @GetMapping
    @Operation(summary = "客户列表：业务员看本人经办，负责人看管理团队，老板看全部；支持状态/负责人筛选")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<PageResult<CustomerRow>> page(@Valid PageParam page,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Long ownerUserId,
                                                      @RequestParam(required = false) FollowFilter followFilter) {
        return success(pageRows(customerService.page(currentPost.require(), page, keyword,
                customerStatus(status), ownerUserId, followFilter)));
    }

    public record StablePageRows(java.util.List<CustomerRow> list, long total, String prefix, boolean changed) {}

    @GetMapping("/stable-page")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<StablePageRows> stablePage(@Valid PageParam page,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long ownerUserId,
                                                    @RequestParam(required = false) FollowFilter followFilter,
                                                    @RequestParam(required = false) String previousPrefix) {
        var result = customerService.stablePage(currentPost.require(), page, keyword,
                customerStatus(status), ownerUserId, followFilter, previousPrefix);
        var rows = pageRows(result.page());
        return success(new StablePageRows(rows.getList(), rows.getTotal(), result.prefix(), result.changed()));
    }

    private static String customerStatus(String value) {
        String status = cn.hutool.core.util.StrUtil.trimToNull(value);
        if (status != null
                && !CustomerDO.STATUS_REGISTERED.equals(status) && !CustomerDO.STATUS_FOLLOWING.equals(status)) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "状态只支持 REGISTERED/FOLLOWING");
        }
        return status;
    }

    private PageResult<CustomerRow> pageRows(PageResult<CustomerDO> result) {
        var ownerNames = result.getList().isEmpty() ? java.util.Map.<Long, String>of()
                : systemDirectory.usersByIds(result.getList().stream().map(CustomerDO::getOwnerUserId).distinct().toList())
                        .stream().collect(java.util.stream.Collectors.toMap(
                                SystemDirectory.UserBrief::userId, SystemDirectory.UserBrief::name));
        return new PageResult<>(result.getList().stream().map(c -> new CustomerRow(
                c.getId(), c.getName(), c.getPhone(), c.getWechat(), c.getSource(), c.getProvince(),
                c.getCity(), c.getSummary(), c.getStatus(), c.getOwnerUserId(),
                ownerNames.getOrDefault(c.getOwnerUserId(), ""), c.getNextVisitDate(), c.getLastFollowAt(),
                c.getRevision(), c.getCreateTime(),c.getDistrict(),c.getTown(),c.getVillage(),c.getRegionCode(),c.getAddressDetail(),c.getDesignStatus(),c.getWorkflow())).toList(), result.getTotal());
    }

    @GetMapping("/owner-options")
    @Operation(summary = "负责人筛选名册：可见范围内出现过的负责人")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<java.util.List<CustomerService.OwnerOption>> ownerOptions() {
        return success(customerService.ownerOptions(currentPost.require()));
    }

    @GetMapping("/transfer-candidates")
    @Operation(summary = "接手人候选：本组织在岗 sales/sales-lead；非老板按授权团队收窄，停用账号排除")
    @PreAuthorize("@post.has('franchise:customer:transfer')")
    public CommonResult<java.util.List<CustomerService.TransferCandidate>> transferCandidates(
            @RequestParam long customerId) {
        return success(customerService.transferCandidates(currentPost.require(), customerId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "客户详情（含跟进记录、方案与预算版本）")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<CustomerDetail> get(@PathVariable long id) {
        return success(CustomerDetail.of(customerService.get(currentPost.require(), id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑客户基本信息（手机号不可改）")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerDetail> update(@PathVariable long id, @RequestBody UpdateReq req,
                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        UpdateCommand command = new UpdateCommand(req.name(),req.wechat(),req.province(),req.city(),req.summary(),req.district(),req.town(),req.village(),req.regionCode(),req.addressDetail());
        return success(CustomerDetail.of(customerService.update(currentPost.require(), id, command, req.revision(), key)));
    }

    @PostMapping("/{id}/follows")
    @Operation(summary = "登记跟进，可预约回访日期")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerDetail> follow(@PathVariable long id, @RequestBody FollowCommand req,
                                            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CustomerDetail.of(customerService.follow(currentPost.require(), id, req, key)));
    }

    @PostMapping("/{id}/schemes")
    @Operation(summary = "保存参考方案或预算版本（BUDGET / PLAN / FACADE）")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Scheme> saveScheme(@PathVariable long id, @RequestBody SchemeCommand req,
                                                     @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Scheme.of(
                customerService.saveScheme(currentPost.require(), id, req, key)));
    }

    @PostMapping("/{id}/budget-confirmation")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerDetail> confirmBudget(@PathVariable long id,@RequestBody CustomerService.BudgetConfirmation req,
            @RequestHeader(value=IDEMPOTENCY_HEADER) String key) {
        return success(CustomerDetail.of(customerService.confirmBudget(currentPost.require(),id,req,key)));
    }

    @PostMapping("/{id}/transfer")
    @Operation(summary = "责任转交（人员变动时由老板或业务负责人办理）")
    @PreAuthorize("@post.has('franchise:customer:transfer')")
    public CommonResult<CustomerDetail> transfer(@PathVariable long id, @RequestBody TransferReq req,
                                         @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CustomerDetail.of(customerService.transfer(currentPost.require(), id,
                new TransferCommand(req.targetUserId(), req.reason()), req.revision(), key)));
    }

}
