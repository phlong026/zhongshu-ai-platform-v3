package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.identity.activation.ActivationRequestService;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminActivationDecisionReqVO;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminActivationRequestRespVO;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 开通申请审批（页面：用户与授权）")
@RestController
@RequestMapping("/design/v1/activation-requests")
public class ActivationRequestAdminController {

    @Resource
    private ActivationRequestService activationRequestService;

    @GetMapping
    @Operation(summary = "分页查询开通申请（按状态过滤，时间倒序）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACTIVATION_REQUEST_MANAGE + "')")
    public CommonResult<PageResult<AdminActivationRequestRespVO>> getRequestPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AdminActivationRequestRespVO> result = new PageResult<>();
        result.setTotal(activationRequestService.count(status));
        result.setList(activationRequestService.page(status, pageNo, pageSize).stream()
                .map(ActivationRequestAdminController::toRespVO).toList());
        return success(result);
    }

    @GetMapping("/stats")
    @Operation(summary = "开通申请三态计数（PENDING 待开通 / APPROVED 已开通 / REJECTED 已驳回）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACTIVATION_REQUEST_MANAGE + "')")
    public CommonResult<Map<String, Long>> getStatusCounts() {
        return success(activationRequestService.statusCounts());
    }

    /**
     * 审批：通过即开通使用权（与授权码兑换共用 design_access_grant）。
     * 充值设计点不在此接口——identity 与 commerce 互不依赖，管理端另行调人工调点接口。
     */
    @PostMapping("/{requestId}/decision")
    @Operation(summary = "审批开通申请：approve=true 开通使用权 / false 驳回（驳回须填原因）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACTIVATION_REQUEST_MANAGE + "')")
    public CommonResult<AdminActivationRequestRespVO> decide(
            @PathVariable("requestId") Long requestId,
            @Valid @RequestBody AdminActivationDecisionReqVO reqVO) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        ActivationRequestService.DecisionResult decision = Boolean.TRUE.equals(reqVO.getApprove())
                ? activationRequestService.approve(requestId, operator, reqVO.getReason())
                : reject(requestId, operator, reqVO.getReason());
        return success(latestAfterDecision(requestId, decision));
    }

    private ServiceException invalid(String message) {
        return new ServiceException(400, message);
    }

    private ActivationRequestService.DecisionResult reject(long requestId, String operator, String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid("驳回时必须填写原因");
        }
        return activationRequestService.reject(requestId, operator, reason);
    }

    /** 审批后返回申请行最新终态，前端无需再拉一次列表 */
    private AdminActivationRequestRespVO latestAfterDecision(long requestId,
                                                             ActivationRequestService.DecisionResult decision) {
        AdminActivationRequestRespVO vo = activationRequestService.findById(requestId)
                .map(ActivationRequestAdminController::toRespVO)
                .orElseGet(() -> {
                    AdminActivationRequestRespVO fallback = new AdminActivationRequestRespVO();
                    fallback.setId(String.valueOf(requestId));
                    return fallback;
                });
        vo.setAccountId(vo.getAccountId() == null ? String.valueOf(decision.accountId()) : vo.getAccountId());
        return vo;
    }

    private static AdminActivationRequestRespVO toRespVO(ActivationRequestService.ActivationRequestRow row) {
        AdminActivationRequestRespVO vo = new AdminActivationRequestRespVO();
        vo.setId(String.valueOf(row.id()));
        vo.setAccountId(String.valueOf(row.accountId()));
        vo.setCompanyName(row.companyName());
        vo.setContactName(row.contactName());
        vo.setContactPhone(row.contactPhone());
        vo.setStatus(row.status());
        vo.setRejectReason(row.rejectReason());
        vo.setNote(row.note());
        vo.setRequestedAt(row.requestedAt() == null ? null : LocalDateTime.ofInstant(row.requestedAt(), ZoneOffset.UTC));
        vo.setHandledAt(row.handledAt() == null ? null : LocalDateTime.ofInstant(row.handledAt(), ZoneOffset.UTC));
        vo.setHandledBy(row.handledBy());
        return vo;
    }

}
