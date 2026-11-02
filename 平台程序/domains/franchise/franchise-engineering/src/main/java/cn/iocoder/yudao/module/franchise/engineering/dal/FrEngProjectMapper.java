package cn.iocoder.yudao.module.franchise.engineering.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FrEngProjectMapper extends BaseMapperX<FrEngProjectDO> {

    /** 行锁（换负责人/prepare 等并发敏感动作） */
    @org.springframework.transaction.annotation.Transactional
    default FrEngProjectDO lockById(long id) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrEngProjectDO>()
                .eq(FrEngProjectDO::getId, id).last("FOR UPDATE"));
    }

    /** 同组织未归档项目名查重（008；部分唯一索引兜底并发） */
    default FrEngProjectDO findActiveByName(long companyId, String name) {
        java.util.List<FrEngProjectDO> rows = selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngProjectDO>()
                        .eq(FrEngProjectDO::getCompanyId, companyId)
                        .eq(FrEngProjectDO::getName, name)
                        .ne(FrEngProjectDO::getStatus, FrEngProjectDO.STATUS_ARCHIVED)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default java.util.List<FrEngProjectDO> selectByCommission(long companyId, long commissionId) {
        return selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FrEngProjectDO>()
                .eq(FrEngProjectDO::getCompanyId, companyId)
                .eq(FrEngProjectDO::getSourceCommissionId, commissionId));
    }

    /** CAS 条件更新（revision 乐观锁；CommissionMapper 同款） */
    default int updateByRevision(Long id, long expectedRevision,
                                 java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrEngProjectDO>> changes) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrEngProjectDO> update =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FrEngProjectDO>()
                        .eq(FrEngProjectDO::getId, id)
                        .eq(FrEngProjectDO::getRevision, expectedRevision)
                        .set(FrEngProjectDO::getRevision, expectedRevision + 1)
                        .set(FrEngProjectDO::getUpdateTime, java.time.OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }
}
