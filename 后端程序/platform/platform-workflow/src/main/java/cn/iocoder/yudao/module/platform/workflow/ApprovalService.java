package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO;

import java.util.List;
import java.util.Optional;

/**
 * 审批中心。平台保证：一个事项同一时刻只有一条有效审批；关键内容变化即作废；只有本组织指定岗位能审批；
 * 不能审批本人提交的事项（按实际人员判断，兼岗切换身份也不行）；必须填写审批意见。
 * 谁有权发起、发起时机由业务域判断。
 */
public interface ApprovalService {

    /**
     * 发起审批。已有同内容的有效审批（待审批或已批准）时直接返回它；内容不同则作废旧审批后新建。
     */
    ApprovalDO submit(ApprovalRequest request, PostSnapshot submitter);

    /** 审批：approve=true 批准，false 退回 */
    ApprovalDO decide(long approvalId, boolean approve, String note, PostSnapshot approver);

    /** 最近一条未作废的审批（可能已退回） */
    Optional<ApprovalDO> latest(String domain, String subjectType, long subjectId);

    /** 当前内容（signature）是否已获批准 */
    boolean isApproved(String domain, String subjectType, long subjectId, String signature);

    List<ApprovalDO> listPending(long orgId, String approverRoleCode);

}
