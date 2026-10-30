package cn.iocoder.yudao.module.platform.workflow;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO.*;
import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.*;

@Service
@Slf4j
public class ApprovalServiceImpl implements ApprovalService {

    private static final int NOTE_MAX_LENGTH = 500;

    @Resource
    private ApprovalMapper approvalMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalDO submit(ApprovalRequest request, PostSnapshot submitter) {
        ApprovalDO live = approvalMapper.selectLive(request.domain(), request.subjectType(), request.subjectId());
        if (live != null) {
            if (live.getSignature().equals(request.signature())) {
                return live;
            }
            Revisions.requireUpdated(approvalMapper.updateIfStatus(
                    new ApprovalDO().setId(live.getId()).setStatus(STATUS_SUPERSEDED), live.getStatus()));
        }
        ApprovalDO approval = new ApprovalDO()
                .setDomain(request.domain()).setOrgId(request.orgId())
                .setSubjectType(request.subjectType()).setSubjectId(request.subjectId())
                .setSubjectVersion(request.subjectVersion()).setSignature(request.signature())
                .setAmountCents(request.amountCents()).setApproverRoleCode(request.approverRoleCode())
                .setStatus(STATUS_PENDING)
                .setSubmitterUserId(submitter.userId()).setSubmitterPostId(submitter.postId())
                .setCreateTime(OffsetDateTime.now());
        try {
            approvalMapper.insert(approval);
        } catch (DuplicateKeyException e) { // 并发发起同一事项，被「每事项一条有效审批」唯一索引拦下
            log.warn("[submit][{}/{}#{}] 并发发起审批冲突", request.domain(), request.subjectType(), request.subjectId());
            throw exception(STATE_VERSION_CONFLICT);
        }
        return approval;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApprovalDO decide(long approvalId, boolean approve, String note, PostSnapshot approver) {
        ApprovalDO approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw exception(APPROVAL_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(approval.getStatus())) {
            throw exception(APPROVAL_NOT_PENDING);
        }
        if (approver.orgId() != approval.getOrgId() || !Objects.equals(approver.roleCode(), approval.getApproverRoleCode())) {
            throw exception(APPROVER_NOT_QUALIFIED);
        }
        if (approver.userId() == approval.getSubmitterUserId()) {
            throw exception(APPROVAL_SELF_FORBIDDEN);
        }
        if (StrUtil.isBlank(note)) {
            throw exception(APPROVAL_NOTE_REQUIRED);
        }
        ApprovalDO update = new ApprovalDO().setId(approvalId)
                .setStatus(approve ? STATUS_APPROVED : STATUS_RETURNED)
                .setApproverUserId(approver.userId()).setApproverPostId(approver.postId())
                .setNote(StrUtil.maxLength(note.trim(), NOTE_MAX_LENGTH - 3))
                .setDecidedAt(OffsetDateTime.now());
        // 并发办理或刚被新内容作废时，影响行数为 0
        if (approvalMapper.updateIfStatus(update, STATUS_PENDING) != 1) {
            throw exception(APPROVAL_NOT_PENDING);
        }
        return approvalMapper.selectById(approvalId);
    }

    @Override
    public Optional<ApprovalDO> latest(String domain, String subjectType, long subjectId) {
        return Optional.ofNullable(approvalMapper.selectLatestNotSuperseded(domain, subjectType, subjectId));
    }

    @Override
    public boolean isApproved(String domain, String subjectType, long subjectId, String signature) {
        ApprovalDO live = approvalMapper.selectLive(domain, subjectType, subjectId);
        return live != null && STATUS_APPROVED.equals(live.getStatus()) && live.getSignature().equals(signature);
    }

    @Override
    public List<ApprovalDO> listPending(long orgId, String approverRoleCode) {
        return approvalMapper.selectPending(orgId, approverRoleCode);
    }

}
