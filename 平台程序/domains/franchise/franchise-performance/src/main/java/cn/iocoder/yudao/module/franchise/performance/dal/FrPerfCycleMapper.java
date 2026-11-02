package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfCycleMapper extends BaseMapperX<FrPerfCycleDO> {

    default FrPerfCycleDO selectByPeriodAndUser(long companyId, String period, long userId) {
        List<FrPerfCycleDO> rows = selectList(new LambdaQueryWrapperX<FrPerfCycleDO>()
                .eq(FrPerfCycleDO::getCompanyId, companyId)
                .eq(FrPerfCycleDO::getPeriod, period)
                .eq(FrPerfCycleDO::getUserId, userId).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<FrPerfCycleDO> selectByPeriod(long companyId, String period) {
        return selectList(new LambdaQueryWrapperX<FrPerfCycleDO>()
                .eq(FrPerfCycleDO::getCompanyId, companyId)
                .eq(FrPerfCycleDO::getPeriod, period));
    }
}
