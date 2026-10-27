package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/** 查询均受组织范围规则约束，只返回当前工作身份所在组织的数据 */
@Mapper
public interface CustomerMapper extends BaseMapperX<CustomerDO> {

    default CustomerDO selectByPhone(String phone) {
        return selectOne(CustomerDO::getPhone, phone);
    }

    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(CustomerDO::getName, keyword.trim()).or().like(CustomerDO::getPhone, keyword.trim()));
        }
        return selectPage(page, query.orderByDesc(CustomerDO::getUpdateTime).orderByDesc(CustomerDO::getId));
    }

    /** 预约回访到期（含今天）的客户 */
    default List<CustomerDO> selectDueVisits(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility, LocalDate today) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query.le(CustomerDO::getNextVisitDate, today).orderByAsc(CustomerDO::getNextVisitDate));
    }

    default List<CustomerDO> selectVisible(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        return selectList(query);
    }

    /**
     * 按版本更新：只改 changes 中 set 的列，并把版本号加一；影响 0 行表示已被他人改动。
     * 用显式 set 而不是实体，才能把列改为空（如清空预约回访日期）。
     */
    default int updateByRevision(Long id, long expectedRevision, Consumer<LambdaUpdateWrapper<CustomerDO>> changes) {
        LambdaUpdateWrapper<CustomerDO> update = new LambdaUpdateWrapper<CustomerDO>()
                .eq(CustomerDO::getId, id)
                .eq(CustomerDO::getRevision, expectedRevision)
                .set(CustomerDO::getRevision, expectedRevision + 1)
                .set(CustomerDO::getUpdateTime, OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }

}
