package cn.iocoder.yudao.module.platform.workflow.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import static cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO.*;

@Mapper
public interface ApprovalMapper extends BaseMapperX<ApprovalDO> {

    /** 有效审批（PENDING 或 APPROVED），至多一条 */
    default ApprovalDO selectLive(String domain, String subjectType, Long subjectId) {
        return selectOne(subject(domain, subjectType, subjectId).in(ApprovalDO::getStatus, STATUS_PENDING, STATUS_APPROVED));
    }

    /** 最近一条未作废的审批（可能是已退回） */
    default ApprovalDO selectLatestNotSuperseded(String domain, String subjectType, Long subjectId) {
        List<ApprovalDO> rows = selectList(subject(domain, subjectType, subjectId).ne(ApprovalDO::getStatus, STATUS_SUPERSEDED)
                .orderByDesc(ApprovalDO::getCreateTime).orderByDesc(ApprovalDO::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<ApprovalDO> selectPending(Long orgId, String approverRoleCode) {
        return selectList(new LambdaQueryWrapperX<ApprovalDO>()
                .eq(ApprovalDO::getOrgId, orgId)
                .eq(ApprovalDO::getApproverRoleCode, approverRoleCode)
                .eq(ApprovalDO::getStatus, STATUS_PENDING)
                .orderByAsc(ApprovalDO::getCreateTime));
    }

    /** 仅当仍处于 expectedStatus 时更新，返回影响行数（用于并发保护） */
    default int updateIfStatus(ApprovalDO update, String expectedStatus) {
        return update(update, new LambdaUpdateWrapper<ApprovalDO>()
                .eq(ApprovalDO::getId, update.getId())
                .eq(ApprovalDO::getStatus, expectedStatus));
    }

    private static LambdaQueryWrapperX<ApprovalDO> subject(String domain, String subjectType, Long subjectId) {
        return new LambdaQueryWrapperX<ApprovalDO>()
                .eq(ApprovalDO::getDomain, domain)
                .eq(ApprovalDO::getSubjectType, subjectType)
                .eq(ApprovalDO::getSubjectId, subjectId);
    }

}
