package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfOperatingSnapshotMapper extends BaseMapperX<FrPerfOperatingSnapshotDO> {

    default List<FrPerfOperatingSnapshotDO> selectByPeriod(long companyId, String period) {
        return selectList(new LambdaQueryWrapperX<FrPerfOperatingSnapshotDO>()
                .eq(FrPerfOperatingSnapshotDO::getCompanyId, companyId)
                .eq(FrPerfOperatingSnapshotDO::getPeriod, period)
                .orderByDesc(FrPerfOperatingSnapshotDO::getVersion));
    }
}
