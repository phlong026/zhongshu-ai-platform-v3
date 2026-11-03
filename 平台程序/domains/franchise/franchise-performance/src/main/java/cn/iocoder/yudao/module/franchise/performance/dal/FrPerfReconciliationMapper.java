package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfReconciliationMapper extends BaseMapperX<FrPerfReconciliationDO> {

    default FrPerfReconciliationDO selectByPeriod(long companyId, String period) {
        List<FrPerfReconciliationDO> rows = selectList(new LambdaQueryWrapperX<FrPerfReconciliationDO>()
                .eq(FrPerfReconciliationDO::getCompanyId, companyId)
                .eq(FrPerfReconciliationDO::getPeriod, period).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<FrPerfReconciliationDO> selectAll(long companyId) {
        return selectList(new LambdaQueryWrapperX<FrPerfReconciliationDO>()
                .eq(FrPerfReconciliationDO::getCompanyId, companyId)
                .orderByDesc(FrPerfReconciliationDO::getPeriod));
    }
}
