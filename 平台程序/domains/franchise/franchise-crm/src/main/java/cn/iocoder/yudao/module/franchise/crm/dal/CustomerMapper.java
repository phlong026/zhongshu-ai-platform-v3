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

    /** 导入用：本组织内该手机号客户（显式 company 过滤；手机号在本组织唯一）。 */
    default CustomerDO selectByPhoneInOrg(long companyId, String phone) {
        List<CustomerDO> list = selectList(new LambdaQueryWrapperX<CustomerDO>()
                .eq(CustomerDO::getCompanyId, companyId)
                .eq(CustomerDO::getPhone, phone));
        return list.isEmpty() ? null : list.get(0);
    }

    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword) {
        return selectPage(page, visibility, keyword, null, null);
    }

    /**
     * Web 客资表格：可见范围 + keyword + 状态 + 负责人组合筛选（旧 3 参方法保持兼容）。
     * 空值不参与过滤；状态只接受 REGISTERED/FOLLOWING（由控制器校验）。
     */
    default PageResult<CustomerDO> selectPage(PageParam page, Consumer<LambdaQueryWrapperX<CustomerDO>> visibility,
                                              String keyword, String status, Long ownerUserId) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            query.and(q -> q.like(CustomerDO::getName, term).or().like(CustomerDO::getPhone, term)
                    .or().like(CustomerDO::getProvince, term).or().like(CustomerDO::getCity, term)
                    .or().like(CustomerDO::getDistrict, term).or().like(CustomerDO::getTown, term)
                    .or().like(CustomerDO::getVillage, term).or().like(CustomerDO::getAddressDetail, term));
        }
        query.eqIfPresent(CustomerDO::getStatus, status);
        query.eqIfPresent(CustomerDO::getOwnerUserId, ownerUserId);
        return selectPage(page, query.orderByDesc(CustomerDO::getUpdateTime).orderByDesc(CustomerDO::getId));
    }

    /** 当前可见范围内出现过的负责人（去重），供 Web 负责人筛选下拉。 */
    default List<Long> selectOwnerIds(Consumer<LambdaQueryWrapperX<CustomerDO>> visibility) {
        LambdaQueryWrapperX<CustomerDO> query = new LambdaQueryWrapperX<>();
        visibility.accept(query);
        query.select(CustomerDO::getOwnerUserId).groupBy(CustomerDO::getOwnerUserId);
        return selectList(query).stream().map(CustomerDO::getOwnerUserId).distinct().toList();
    }

    /** 行锁读取（B2 板块 A / P-9：AI 回写的并发版本分配要求锁内重读客户） */
    default CustomerDO lockById(Long id) {
        return selectOne(new LambdaQueryWrapperX<CustomerDO>().eq(CustomerDO::getId, id).last("FOR UPDATE"));
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
