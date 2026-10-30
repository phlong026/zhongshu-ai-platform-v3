package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 查询均受组织范围规则约束 */
@Mapper
public interface CommissionProgressMapper extends BaseMapperX<CommissionProgressDO> {

    default Integer selectMaxSeq(long commissionId, String kind) {
        CommissionProgressDO latest = selectOne(new LambdaQueryWrapperX<CommissionProgressDO>()
                .eq(CommissionProgressDO::getCommissionId, commissionId)
                .eq(CommissionProgressDO::getKind, kind)
                .orderByDesc(CommissionProgressDO::getSeq)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getSeq();
    }

    default List<CommissionProgressDO> selectByCommission(long commissionId) {
        return selectList(new LambdaQueryWrapperX<CommissionProgressDO>()
                .eq(CommissionProgressDO::getCommissionId, commissionId)
                .orderByAsc(CommissionProgressDO::getCreateTime));
    }

}
