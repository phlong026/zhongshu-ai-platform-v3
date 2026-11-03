package cn.iocoder.yudao.module.franchise.performance.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrPerfTaskMapper extends BaseMapperX<FrPerfTaskDO> {

    default List<FrPerfTaskDO> selectByPeriod(long companyId, String period) {
        return selectList(new LambdaQueryWrapperX<FrPerfTaskDO>()
                .eq(FrPerfTaskDO::getCompanyId, companyId)
                .eq(FrPerfTaskDO::getPeriod, period));
    }

    default List<FrPerfTaskDO> selectByPeriodAndAssignee(long companyId, String period, long userId) {
        return selectList(new LambdaQueryWrapperX<FrPerfTaskDO>()
                .eq(FrPerfTaskDO::getCompanyId, companyId)
                .eq(FrPerfTaskDO::getPeriod, period)
                .eq(FrPerfTaskDO::getAssigneeUserId, userId));
    }

    /** CAS 条件更新（命令事件 revision 对齐——阶段 2 教训） */
    default int updateByRevision(Long id, long expectedRevision,
                                 java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrPerfTaskDO>> changes) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrPerfTaskDO> update =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrPerfTaskDO>()
                        .eq(FrPerfTaskDO::getId, id)
                        .eq(FrPerfTaskDO::getRevision, expectedRevision)
                        .set(FrPerfTaskDO::getRevision, expectedRevision + 1)
                        .set(FrPerfTaskDO::getUpdateTime, java.time.OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }
}
