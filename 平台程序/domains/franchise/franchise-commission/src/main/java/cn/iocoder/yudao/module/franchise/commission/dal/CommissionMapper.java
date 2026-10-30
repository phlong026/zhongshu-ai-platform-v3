package cn.iocoder.yudao.module.franchise.commission.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import java.util.function.Consumer;

/** 查询均受组织范围规则约束（company_id + design_org_id） */
@Mapper
public interface CommissionMapper extends BaseMapperX<CommissionDO> {

    default CommissionDO selectByCustomer(long customerId) {
        return selectOne(CommissionDO::getCustomerId, customerId);
    }

    default PageResult<CommissionDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CommissionDO>> visibility,
                                                String keyword) {
        LambdaQueryWrapperX<CommissionDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        if (keyword != null && !keyword.isBlank()) {
            query.like(CommissionDO::getCustomerName, keyword.trim());
        }
        return selectPage(page, query.orderByDesc(CommissionDO::getUpdateTime).orderByDesc(CommissionDO::getId));
    }

    default List<CommissionDO> selectVisible(Consumer<LambdaQueryWrapperX<CommissionDO>> visibility) {
        LambdaQueryWrapperX<CommissionDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query);
    }

    /**
     * 按版本更新：只改 changes 中 set 的列，并把版本号加一；影响 0 行表示已被他人改动。
     */
    default int updateByRevision(Long id, long expectedRevision, Consumer<LambdaUpdateWrapper<CommissionDO>> changes) {
        LambdaUpdateWrapper<CommissionDO> update = new LambdaUpdateWrapper<CommissionDO>()
                .eq(CommissionDO::getId, id)
                .eq(CommissionDO::getRevision, expectedRevision)
                .set(CommissionDO::getRevision, expectedRevision + 1)
                .set(CommissionDO::getUpdateTime, java.time.OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }

}
