package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfGoalMapper extends BaseMapperX<FrPerfGoalDO> {

    default FrPerfGoalDO selectByPeriod(long companyId, String period) {
        List<FrPerfGoalDO> rows = selectList(new LambdaQueryWrapperX<FrPerfGoalDO>()
                .eq(FrPerfGoalDO::getCompanyId, companyId)
                .eq(FrPerfGoalDO::getPeriod, period).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
