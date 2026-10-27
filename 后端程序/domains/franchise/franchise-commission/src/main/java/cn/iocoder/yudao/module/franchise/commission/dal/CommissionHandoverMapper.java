package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 查询均受组织范围规则约束 */
@Mapper
public interface CommissionHandoverMapper extends BaseMapperX<CommissionHandoverDO> {

    default Integer selectMaxVersion(long commissionId) {
        CommissionHandoverDO latest = selectOne(new LambdaQueryWrapperX<CommissionHandoverDO>()
                .eq(CommissionHandoverDO::getCommissionId, commissionId)
                .orderByDesc(CommissionHandoverDO::getVersion)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getVersion();
    }

    default List<CommissionHandoverDO> selectByCommission(long commissionId) {
        return selectList(new LambdaQueryWrapperX<CommissionHandoverDO>()
                .eq(CommissionHandoverDO::getCommissionId, commissionId)
                .orderByDesc(CommissionHandoverDO::getVersion));
    }

}
