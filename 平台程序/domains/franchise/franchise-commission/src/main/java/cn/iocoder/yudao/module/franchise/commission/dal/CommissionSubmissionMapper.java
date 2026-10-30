package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 查询均受组织范围规则约束 */
@Mapper
public interface CommissionSubmissionMapper extends BaseMapperX<CommissionSubmissionDO> {

    default Integer selectMaxVersion(long commissionId) {
        CommissionSubmissionDO latest = selectOne(new LambdaQueryWrapperX<CommissionSubmissionDO>()
                .eq(CommissionSubmissionDO::getCommissionId, commissionId)
                .orderByDesc(CommissionSubmissionDO::getVersion)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getVersion();
    }

    default List<CommissionSubmissionDO> selectByCommission(long commissionId) {
        return selectList(new LambdaQueryWrapperX<CommissionSubmissionDO>()
                .eq(CommissionSubmissionDO::getCommissionId, commissionId)
                .orderByDesc(CommissionSubmissionDO::getVersion));
    }

}
