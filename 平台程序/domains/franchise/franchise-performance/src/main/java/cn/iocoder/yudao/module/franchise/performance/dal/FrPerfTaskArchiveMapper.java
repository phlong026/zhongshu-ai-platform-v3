package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfTaskArchiveMapper extends BaseMapperX<FrPerfTaskArchiveDO> {

    default List<FrPerfTaskArchiveDO> selectByPeriod(long companyId, String period) {
        return selectList(new LambdaQueryWrapperX<FrPerfTaskArchiveDO>()
                .eq(FrPerfTaskArchiveDO::getCompanyId, companyId)
                .eq(FrPerfTaskArchiveDO::getPeriod, period)
                .orderByDesc(FrPerfTaskArchiveDO::getArchiveNo));
    }
}
