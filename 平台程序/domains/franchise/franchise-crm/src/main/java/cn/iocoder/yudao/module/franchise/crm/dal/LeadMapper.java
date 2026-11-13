package cn.iocoder.yudao.module.franchise.crm.dal;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/** 查询均受组织范围规则约束，只返回当前工作身份所在组织的数据 */
@Mapper
public interface LeadMapper extends BaseMapperX<LeadDO> {

    default boolean existsPending(String phone) {
        return selectCount(new LambdaQueryWrapperX<LeadDO>()
                .eq(LeadDO::getPhone, phone)
                .eq(LeadDO::getStatus, LeadDO.STATUS_LINK_REQUIRED)) > 0;
    }

    /** 导入用：本组织内该手机号是否已有待关联客资（显式 company 过滤，不依赖线程内岗位）。 */
    default boolean existsPendingInOrg(long companyId, String phone) {
        return selectCount(new LambdaQueryWrapperX<LeadDO>()
                .eq(LeadDO::getCompanyId, companyId)
                .eq(LeadDO::getPhone, phone)
                .eq(LeadDO::getStatus, LeadDO.STATUS_LINK_REQUIRED)) > 0;
    }

    /** 导入用：本组织内该手机号最近一条客资（显式 company 过滤）。 */
    default LeadDO selectLatestByPhoneInOrg(long companyId, String phone) {
        List<LeadDO> list = selectList(new LambdaQueryWrapperX<LeadDO>()
                .eq(LeadDO::getCompanyId, companyId)
                .eq(LeadDO::getPhone, phone)
                .orderByDesc(LeadDO::getCreateTime)
                .orderByDesc(LeadDO::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 待关联客资，visibility 追加可见范围条件 */
    default List<LeadDO> selectPending(Consumer<LambdaQueryWrapperX<LeadDO>> visibility) {
        LambdaQueryWrapperX<LeadDO> query = new LambdaQueryWrapperX<LeadDO>().eq(LeadDO::getStatus, LeadDO.STATUS_LINK_REQUIRED);
        visibility.accept(query);
        return selectList(query.orderByAsc(LeadDO::getCreateTime));
    }

    /**
     * 按版本更新：只改 changes 中 set 的列，并把版本号加一；影响 0 行表示已被他人改动。
     * 用显式 set 而不是实体，才能把列改为空（如清空预约回访日期）。
     */
    default int updateByRevision(Long id, long expectedRevision, Consumer<LambdaUpdateWrapper<LeadDO>> changes) {
        LambdaUpdateWrapper<LeadDO> update = new LambdaUpdateWrapper<LeadDO>()
                .eq(LeadDO::getId, id)
                .eq(LeadDO::getRevision, expectedRevision)
                .set(LeadDO::getRevision, expectedRevision + 1)
                .set(LeadDO::getUpdateTime, OffsetDateTime.now());
        changes.accept(update);
        return update(null, update);
    }

}
