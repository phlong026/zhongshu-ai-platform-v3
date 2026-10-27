package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.franchise.crm.CustomerDetail;
import cn.iocoder.yudao.module.franchise.crm.CustomerQuery;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadInput;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.*;
import cn.iocoder.yudao.module.franchise.org.FranchisePermissions;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 客资与客户。所有接口按当前工作身份（请求头 X-Post-Id）授权，数据自动限定在本门店。
 */
@Tag(name = "加盟协同 - 客户")
@RestController
@RequestMapping("/franchise/customers")
@Validated
public class CustomerController {

    @Resource
    private CustomerService customerService;
    @Resource
    private CurrentPost currentPost;

    @PostMapping("/leads")
    @Operation(summary = "登记客资（同手机号自动关联原客户）")
    @PreAuthorize("@post.has('" + FranchisePermissions.CUSTOMER_CREATE + "')")
    public CommonResult<LeadCaptureRespVO> captureLead(@Valid @RequestBody LeadCaptureReqVO req) {
        var result = customerService.captureLead(currentPost.require(),
                new LeadInput(req.getName(), req.getPhone(), req.getNeed(), req.getSource()));
        LeadCaptureRespVO resp = new LeadCaptureRespVO();
        resp.setLeadId(result.leadId());
        resp.setCustomerId(result.customerId());
        resp.setLinkedExisting(result.linkedExisting());
        resp.setCustomerOwnerUserId(result.customerOwnerUserId());
        return success(resp);
    }

    @PostMapping("/{id}/follow-ups")
    @Operation(summary = "记录跟进")
    @PreAuthorize("@post.has('" + FranchisePermissions.CUSTOMER_FOLLOW + "')")
    public CommonResult<Long> addFollowUp(@PathVariable("id") Long id, @Valid @RequestBody FollowUpReqVO req) {
        return success(customerService.addFollowUp(currentPost.require(), id, req.getRevision(), req.getNote(),
                req.getNextVisitDate()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "客户详情（含客资与跟进）")
    @PreAuthorize("@post.has('" + FranchisePermissions.CUSTOMER_QUERY + "')")
    public CommonResult<CustomerDetailRespVO> get(@PathVariable("id") Long id) {
        CustomerDetail detail = customerService.getCustomer(currentPost.require(), id);
        CustomerDetailRespVO resp = new CustomerDetailRespVO();
        resp.setCustomer(BeanUtils.toBean(detail.customer(), CustomerRespVO.class));
        resp.setLeads(BeanUtils.toBean(detail.leads(), CustomerDetailRespVO.Lead.class));
        resp.setFollowUps(BeanUtils.toBean(detail.followUps(), CustomerDetailRespVO.FollowUp.class));
        return success(resp);
    }

    @GetMapping("/page")
    @Operation(summary = "客户列表（按当前身份的可见范围）")
    @PreAuthorize("@post.has('" + FranchisePermissions.CUSTOMER_QUERY + "')")
    public CommonResult<PageResult<CustomerRespVO>> page(@Valid CustomerPageReqVO req) {
        var page = customerService.pageCustomers(currentPost.require(),
                new CustomerQuery(req.getPageNo(), req.getPageSize(), req.getKeyword()));
        return success(BeanUtils.toBean(page, CustomerRespVO.class));
    }

}
